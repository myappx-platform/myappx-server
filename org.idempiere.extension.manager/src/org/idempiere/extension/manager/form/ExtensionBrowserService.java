/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 **********************************************************************/
package org.idempiere.extension.manager.form;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.adempiere.base.Core;
import org.adempiere.base.markdown.IMarkdownRenderer;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Callback;
import org.adempiere.util.ServerContext;
import org.compiere.model.MExtension;
import org.compiere.model.MExtensionEntity;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.MPackageImp;
import org.compiere.model.MPackageImpDetail;
import org.compiere.model.Query;
import org.compiere.model.SystemProperties;
import org.compiere.util.CacheMgt;
import org.compiere.util.DB;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.idempiere.extension.manager.ExtensionManagerActivator;
import org.idempiere.extension.manager.event.PackageImpDelegate;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ExtensionBrowserService {

	private static final CLogger log = CLogger.getCLogger(ExtensionBrowserService.class);
	/** Placeholder for an extension row created before any bundle is installed */
	private static final String NOT_INSTALLED_VERSION = "0.0.0";
	/** An install that has been "in progress" for longer than this is treated as abandoned */
	private static final long STALE_INSTALL_MINUTES = 10;
	private static volatile boolean staleInstallsChecked = false;
	private HttpClient httpClient;

	public ExtensionBrowserService() {
		httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
		recoverStaleInstalls();
	}

	/**
	 * Turn abandoned EXTENSIONSTATE_Installing rows into EXTENSIONSTATE_Error.
	 *
	 * InstallExtension.doIt sets Installing before touching the bundles and clears it in a finally
	 * block. When the install tears the framework down (an OSGi refresh that restarts Jetty), that
	 * finally block cannot write any more - BaseActivator.getBundleContext() is already null - and
	 * the row stays Installing forever, which blocks every later install with
	 * "Extension is already being installed". Recover here: the operator opening Extension
	 * Management is a reliable moment with the DB up and the framework healthy.
	 */
	private void recoverStaleInstalls() {
		if (staleInstallsChecked)
			return;
		synchronized (ExtensionBrowserService.class) {
			if (staleInstallsChecked)
				return;
			staleInstallsChecked = true;
		}
		try {
			java.sql.Timestamp cutoff = new java.sql.Timestamp(
					System.currentTimeMillis() - STALE_INSTALL_MINUTES * 60L * 1000L);
			List<MExtension> stale = new Query(Env.getCtx(), MExtension.Table_Name,
					"ExtensionState=? AND Updated<?", null)
					.setParameters(MExtension.EXTENSIONSTATE_Installing, cutoff)
					.setOnlyActiveRecords(true)
					.list();
			for (MExtension extension : stale) {
				log.warning("Install of " + extension.getExtensionId() + " did not finish (state Installing since "
						+ extension.getUpdated() + "); marking it Error so it can be installed again");
				extension.setExtensionState(MExtension.EXTENSIONSTATE_Error);
				extension.saveEx();
			}
			if (!stale.isEmpty())
				CacheMgt.get().reset(MExtension.Table_Name);
		} catch (Exception e) {
			// Never block opening the form because the cleanup failed.
			log.log(Level.WARNING, "Failed to recover abandoned extension installs", e);
		}
	}

	/**
	 * Fetch extensions from the iDempiere Extension Repository
	 * @return JsonArray of extensions
	 * @throws Exception
	 */
	public JsonArray fetchRepositoryExtensions() throws Exception {
		String repoUrl = SystemProperties.getIDempiereRepositoryUrl();
		if (repoUrl == null || repoUrl.trim().isEmpty()) {
			throw new IllegalStateException(Msg.getMsg(Env.getCtx(), "ExtensionRepositoryNotSet")); //iDempiere extension repository URL is not set
		}

		String indexUrl = toRawGithubURL(repoUrl, "index.json");

		String body = fetchString(indexUrl);
		
		JsonObject indexObj = JsonParser.parseString(body).getAsJsonObject();
		return indexObj.getAsJsonArray("extensions");
	}

	/**
	 * Get installed extensions
	 * @return List of installed extensions
	 */
	public List<MExtension> getInstalledExtensions() {
		return new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionState IN (?,?)", null)
				.setParameters(MExtension.EXTENSIONSTATE_Installed, MExtension.EXTENSIONSTATE_Disabled)
				.setOnlyActiveRecords(true)
				.list();
	}

	/**
	 * Fetch and render markdown to html
	 * @param url URL to fetch markdown from
	 * @return Rendered markdown
	 */
	public String fetchAndRenderMarkdown(String url) {
		if (url == null || url.isEmpty()) return "";
		
		String rawUrl = toRawGithubPath(url);
		
		try {
			String markdown = fetchString(rawUrl);
			
			IMarkdownRenderer renderer = Core.getMarkdownRenderer();
			String baseUrl = rawUrl;
			if (baseUrl.contains("/")) {
				baseUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
			}
			return renderer.renderToHtml(markdown, true, baseUrl);
		} catch (Exception e) {
			log.log(Level.SEVERE, "Failed to fetch or render documentation from " + rawUrl, e);
			String safeMessage = e.getMessage() == null ? "" : e.getMessage()
					.replace("&", "&amp;")
					.replace("<", "&lt;")
					.replace(">", "&gt;")
					.replace("\"", "&quot;")
					.replace("'", "&#39;");
			return (
					"<div style=\"padding: 20px; color: '#721c24'; background-color: '#f8d7da'; border: 1px solid '#f5c6cb'; border-radius: 4px;\">" +
					"<h4>%s</h4><p>%s</p></div>"
					).formatted(Msg.getMsg(Env.getCtx(), "Error"), safeMessage);
		}
	}

	/**
	 * Fetch extension metadata from URL
	 * @param url
	 * @return ExtensionMetadata
	 * @throws Exception
	 */
	public ExtensionMetadata fetchExtensionMetadata(String url) throws Exception {
		if (url == null || url.isEmpty()) return null;
		
		// if url starts with file:, read from file
		String rawUrl = toRawGithubPath(url);
		String responseBody = fetchString(rawUrl);
		
		List<String> errors = ExtensionMetadataValidator.validate(responseBody);
		if (!errors.isEmpty()) {
			throw new AdempiereException("Metadata Validation Error:\n" + String.join("\n", errors));
		}
		JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
		return new ExtensionMetadata(json);
	}

	/**
	 * Convert github repository URL to raw github URL
	 * @param repoUrl
	 * @param relativePath
	 * @return
	 */
	public String toRawGithubURL(String repoUrl, String relativePath) {
		if (repoUrl.startsWith("file:")) {
			return repoUrl.endsWith("/") ? repoUrl + relativePath : repoUrl + "/" + relativePath;
		}
		if (repoUrl.contains("github.com"))
			repoUrl = repoUrl.replace("github.com", "raw.githubusercontent.com");
		String rawUrl = repoUrl.endsWith("/") ? repoUrl + "main/" + relativePath : repoUrl + "/main/" + relativePath;
		return rawUrl;
	}

	/**
	 * Convert github absolute URL to raw github absolute URL
	 * @param absolutePath
	 * @return
	 */
	private String toRawGithubPath(String absolutePath) {
		if (absolutePath.startsWith("file:")) {
			return absolutePath;
		}
		if (absolutePath.contains("github.com") && absolutePath.contains("/blob/")) {
			absolutePath = absolutePath.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/");
		}
		return absolutePath;
	}

	/**
	 * Wait until a package refresh finishes. A singleton stays selected until this returns.
	 * @param bundles bundles to refresh; empty does nothing
	 * @param symbolicName used in timeout and error text
	 * @throws Exception refresh timed out or the framework reported an error
	 */
	private void refreshBundlesAndWait(Collection<Bundle> bundles, String symbolicName) throws Exception {
		if (bundles == null || bundles.isEmpty())
			return;
		if (ExtensionManagerActivator.context == null) {
			throw new AdempiereException(Msg.getMsg(Env.getCtx(), "BundleContextNotFound"));
		}
		FrameworkWiring wiring = ExtensionManagerActivator.context.getBundle(0).adapt(FrameworkWiring.class);
		if (wiring == null) {
			throw new AdempiereException("FrameworkWiring is not available");
		}
		CountDownLatch latch = new CountDownLatch(1);
		FrameworkEvent[] outcome = new FrameworkEvent[1];
		wiring.refreshBundles(bundles, event -> {
			outcome[0] = event;
			latch.countDown();
		});
		if (!latch.await(60, TimeUnit.SECONDS)) {
			throw new AdempiereException("Timed out refreshing bundle " + symbolicName);
		}
		FrameworkEvent event = outcome[0];
		if (event != null && event.getType() == FrameworkEvent.ERROR) {
			Throwable cause = event.getThrowable();
			if (cause == null)
				throw new AdempiereException("Failed to refresh bundle " + symbolicName);
			throw new AdempiereException("Failed to refresh bundle " + symbolicName + ": " + cause.getMessage(), cause);
		}
	}

	private List<Bundle> bundlesNamed(String symbolicName) {
		List<Bundle> found = new ArrayList<>();
		if (ExtensionManagerActivator.context == null)
			return found;
		for (Bundle bundle : ExtensionManagerActivator.context.getBundles()) {
			if (bundle.getBundleId() != 0 && symbolicName.equals(bundle.getSymbolicName()))
				found.add(bundle);
		}
		return found;
	}

	private List<Bundle> removalPendingNamed(String symbolicName) {
		List<Bundle> found = new ArrayList<>();
		if (ExtensionManagerActivator.context == null)
			return found;
		FrameworkWiring wiring = ExtensionManagerActivator.context.getBundle(0).adapt(FrameworkWiring.class);
		if (wiring == null)
			return found;
		for (Bundle bundle : wiring.getRemovalPendingBundles()) {
			if (symbolicName.equals(bundle.getSymbolicName()))
				found.add(bundle);
		}
		return found;
	}

	/**
	 * Catalog version used to reject an already-newer install. Bundle version wins when present;
	 * otherwise the extension version. Missing both means no comparison.
	 */
	private Version declaredBundleVersion(ExtensionMetadata extension, JsonObject bundleObj) {
		if (bundleObj != null && bundleObj.has("version") && !bundleObj.get("version").isJsonNull()) {
			String bundleVersion = bundleObj.get("version").getAsString();
			if (bundleVersion != null && !bundleVersion.isBlank())
				return Version.parseVersion(bundleVersion.trim());
		}
		if (extension != null && extension.hasVersion()) {
			String metadataVersion = extension.getVersion();
			if (metadataVersion != null && !metadataVersion.isBlank())
				return Version.parseVersion(metadataVersion.trim());
		}
		return null;
	}

	private Bundle preferLiveBundle(List<Bundle> live) {
		Bundle resolved = null;
		Bundle installed = null;
		for (Bundle bundle : live) {
			int state = bundle.getState();
			if (state == Bundle.ACTIVE || state == Bundle.STARTING)
				return bundle;
			if (resolved == null && state == Bundle.RESOLVED)
				resolved = bundle;
			else if (installed == null && state == Bundle.INSTALLED)
				installed = bundle;
		}
		if (resolved != null)
			return resolved;
		if (installed != null)
			return installed;
		return live.get(0);
	}

	/**
	 * Update the current bundle in place, or install one new bundle after pending removals are gone.
	 * A second install while the old singleton revision is still selected cannot resolve.
	 */
	private Bundle replaceOrInstallBundle(String symbolicName, String downloadUrl, File jarFile) throws Exception {
		if (ExtensionManagerActivator.context == null) {
			throw new AdempiereException(Msg.getMsg(Env.getCtx(), "BundleContextNotFound"));
		}
		List<Bundle> pending = removalPendingNamed(symbolicName);
		refreshBundlesAndWait(pending, symbolicName);

		List<Bundle> live = new ArrayList<>();
		for (Bundle bundle : bundlesNamed(symbolicName)) {
			if (bundle.getState() != Bundle.UNINSTALLED)
				live.add(bundle);
		}
		if (live.isEmpty()) {
			if (!removalPendingNamed(symbolicName).isEmpty()) {
				throw new AdempiereException("Bundle " + symbolicName + " is still selected after refresh");
			}
			try (java.io.FileInputStream fis = new java.io.FileInputStream(jarFile)) {
				return ExtensionManagerActivator.context.installBundle(downloadUrl, fis);
			}
		}

		Bundle target = preferLiveBundle(live);
		List<Bundle> extras = new ArrayList<>();
		for (Bundle bundle : live) {
			if (bundle.getBundleId() != target.getBundleId())
				extras.add(bundle);
		}
		if (!extras.isEmpty()) {
			// Duplicate revisions of a singleton cannot both resolve. Dropping the extras needs a
			// framework refresh, and a refresh propagates to every bundle that depends on this one -
			// including org.adempiere.ui.zk and org.adempiere.server, which tears the running
			// Jetty down. Refuse instead of crashing the server.
			throw new AdempiereException("Cannot update " + symbolicName + ": " + extras.size()
					+ " additional revision(s) of this bundle are still installed. Remove them by restarting the server"
					+ " with only the new jar in place, then install again.");
		}

		// Bundle.update(InputStream) replaces this bundle's current revision, and the previous
		// revision stays selected until a refresh retires it - so a refresh is unavoidable. But a
		// refresh also restarts every bundle that depends on this one. Check the blast radius
		// BEFORE touching the bundle, so a refused update leaves it exactly as it was.
		assertRefreshIsSafe(target, symbolicName);

		int state = target.getState();
		if (state == Bundle.ACTIVE || state == Bundle.STARTING)
			target.stop();
		try (java.io.FileInputStream fis = new java.io.FileInputStream(jarFile)) {
			target.update(fis);
		}
		refreshBundlesAndWait(Collections.singletonList(target), symbolicName);
		for (Bundle bundle : bundlesNamed(symbolicName)) {
			if (bundle.getBundleId() != target.getBundleId() && bundle.getState() != Bundle.UNINSTALLED)
				throw new AdempiereException("Bundle " + symbolicName + " is still selected after refresh");
		}
		return target;
	}

	/**
	 * Bundles that must survive a refresh for the server to stay usable. Refreshing any of them
	 * restarts Jetty/ZK/Hazelcast and takes the running application down, which is not something
	 * an extension install should ever do implicitly.
	 */
	private static final java.util.Set<String> REFRESH_FORBIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
			"org.eclipse.osgi",
			"org.adempiere.server",
			"org.adempiere.base",
			"org.adempiere.ui.zk",
			"org.adempiere.pipo",
			"org.eclipse.jetty.server",
			"org.eclipse.jetty.ee8.server",
			"com.hazelcast"));

	/**
	 * Reject an update whose refresh would restart a server bundle.
	 * @param target bundle about to be refreshed
	 * @param symbolicName extension being updated, used in the message
	 * @throws AdempiereException when the dependency closure contains a bundle that cannot restart
	 */
	private void assertRefreshIsSafe(Bundle target, String symbolicName) throws Exception {
		if (ExtensionManagerActivator.context == null)
			throw new AdempiereException(Msg.getMsg(Env.getCtx(), "BundleContextNotFound"));
		FrameworkWiring wiring = ExtensionManagerActivator.context.getBundle(0).adapt(FrameworkWiring.class);
		if (wiring == null)
			return;

		List<String> blockers = new ArrayList<>();
		Collection<Bundle> closure = wiring.getDependencyClosure(Collections.singletonList(target));
		if (closure != null) {
			for (Bundle dependent : closure) {
				String name = dependent.getSymbolicName();
				if (name == null || name.equals(symbolicName))
					continue;
				if (REFRESH_FORBIDDEN.contains(name)) {
					blockers.add(name);
					continue;
				}
				for (String prefix : REFRESH_FORBIDDEN) {
					if (name.startsWith(prefix + ".")) {
						blockers.add(name);
						break;
					}
				}
			}
		}
		if (!blockers.isEmpty()) {
			throw new AdempiereException("Cannot hot update " + symbolicName
					+ ": refreshing it would also restart " + String.join(", ", blockers)
					+ ". Stop the server, replace the bundle, then start it again.");
		}
	}

	/**
	 * Uninstall all OSGi bundles of an extension
	 * @param extension
	 */
	public void uninstallBundles(ExtensionMetadata extension) {
		if (!extension.hasBundles()) return;
		JsonArray bundles = extension.getBundles();
		for (JsonElement bel : bundles) {
			JsonObject b = bel.getAsJsonObject();
			if (b.has("symbolicName")) {
				String symbolicName = b.get("symbolicName").getAsString();
				for (Bundle bundle : ExtensionManagerActivator.context.getBundles()) {
					if (symbolicName.equals(bundle.getSymbolicName())) {
						try {
							if (bundle.getState() != Bundle.UNINSTALLED) {
								if (bundle.getState() == Bundle.ACTIVE) {
									bundle.stop();
								}
								bundle.uninstall();
							}
						} catch (Exception e) {
							log.log(Level.WARNING, "Stop/Uninstall failed for " + symbolicName, e);
						}
					}
				}
			}
		}
	}

	/**
	 * Validate optional database requirement
	 * @param extension
	 * @return error message or null if fulfill
	 * @throws SQLException
	 */
	public String validateDatabaseRequirement(ExtensionMetadata extension) throws SQLException {
		JsonArray array = extension.getDatabase();
		if (array == null || array.isEmpty())
			return null;

		JsonObject dbObject = null;
		for (JsonElement el : array) {
			if (el.isJsonObject()) {
				var jsonObject = el.getAsJsonObject();
				if (jsonObject.has("id")) {
					String id = jsonObject.get("id").getAsString();
					if (DB.isPostgreSQL() && "postgresql".equals(id))
						dbObject = jsonObject;
					else if (DB.isOracle() && "oracle".equals(id))
						dbObject = jsonObject;
				}
			}
			if (dbObject != null)
				break;
		}
		if (dbObject == null)
			return Msg.getMsg(Env.getCtx(), "DatabaseTypeNotSupported", new Object[] {DB.getDatabase().getName()}); //Database {0} not supported
		
		//validate version
		if (dbObject.has("version")) {
			String version = dbObject.get("version").getAsString();
			try (Connection conn = DB.getConnection()) {
				DatabaseMetaData dbmd = conn.getMetaData();
				String fullVersion = dbmd.getDatabaseProductVersion();
				Version requiredVersion = Version.parseVersion(version);
				Version currentVersion = Version.parseVersion(fullVersion.indexOf(" ") > 0 ? fullVersion.substring(0, fullVersion.indexOf(" ")) : fullVersion);
				if (currentVersion.compareTo(requiredVersion) < 0) {
					//{0} version {1} is too old, {2} and above is required
					return Msg.getMsg(Env.getCtx(), "DatabaseVersionTooOld", new Object[] {DB.getDatabase().getName(), currentVersion, requiredVersion});
				}
			}
		}

		//validate PostgreSQL extensions
		if (DB.isPostgreSQL() && dbObject.has("extensions")) {
			JsonArray dbExtensions = dbObject.getAsJsonArray("extensions");
			for (JsonElement el : dbExtensions) {
				String dbExtensionName = el.getAsString();
				int count = DB.getSQLValue(null, "SELECT count(*) FROM pg_available_extensions WHERE name = ?", dbExtensionName.trim());
				if (count == 0)
					//The required extension {0} is not found on this {1} server
					return Msg.getMsg(Env.getCtx(), "DatabaseExtensionNotFound", new Object[] {dbExtensionName, DB.getDatabase().getName()});
			}
		}

		return null;
	}

	/**
	 * Uninstall an extension
	 * @param extension
	 * @throws Exception
	 */
	public void uninstallExtension(ExtensionMetadata extension) throws Exception {
		String extensionId = extension.getId();
		MExtension mExtension = null;
		if (extensionId != null) {
			mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
					.setParameters(extensionId)
					.setOnlyActiveRecords(true)
					.first();
		}
		if (mExtension == null) {
			throw new AdempiereException("Extension not found: " + extensionId);				
		}
		
		try {
			disableExtensionEntities(mExtension);
		} catch (Exception e) {
			log.log(Level.SEVERE, "Error disabling extension entities for " + extensionId, e);
		}
		uninstallBundles(extension);
		mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Uninstalled);
		mExtension.saveEx();
	}

	/**
	 * Disable and stop an extension
	 * @param extension
	 * @throws Exception
	 */
	public void disableExtension(ExtensionMetadata extension) throws Exception {
		if (extension.hasBundles()) {
			JsonArray bundles = extension.getBundles();
			for (JsonElement bel : bundles) {
				JsonObject b = bel.getAsJsonObject();
				if (b.has("symbolicName")) {
					String symbolicName = b.get("symbolicName").getAsString();
					for (Bundle bundle : ExtensionManagerActivator.context.getBundles()) {
						if (symbolicName.equals(bundle.getSymbolicName())) {
							bundle.stop();
						}
					}
				}
			}
		}
		String extensionId = extension.getId();
		if (extensionId != null) {
			MExtension mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
					.setParameters(extensionId)
					.first();
			if (mExtension != null) {
				mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Disabled);
				mExtension.saveEx();
				
				disableExtensionEntities(mExtension);
			}
		}
		CacheMgt.get().reset();
	}

	private void disableExtensionEntities(MExtension mExtension) {
		List<MExtensionEntity> entities = new Query(Env.getCtx(), MExtensionEntity.Table_Name, "AD_Extension_ID=?", null)
				.setParameters(mExtension.getAD_Extension_ID())
				.list();
		for (MExtensionEntity entity : entities) {
			MTable table = MTable.get(Env.getCtx(), entity.getAD_Table_ID());
			if (table == null)
				continue;
			POInfo poInfo = POInfo.getPOInfo(Env.getCtx(), table.getAD_Table_ID());
			if (poInfo == null || poInfo.getTableName() == null)
				continue;
			PO po = table.getPOByUU(entity.getRecord_UU(), null);
			if (po != null) {
				if (po.get_ColumnIndex("IsActive") >= 0) {
					if (po.set_Value("IsActive", false))
						po.saveEx();
				}
			}
		}
	}

	/**
	 * Enable and start an extension
	 * @param extension
	 * @throws Exception
	 */
	public void enableExtension(ExtensionMetadata extension) throws Exception {
		String extensionId = extension.getId();
		MExtension mExtension = null;
		if (extensionId != null) {
			mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
					.setParameters(extensionId)
					.setOnlyActiveRecords(true)
					.first();
		}
		if (mExtension == null) {
			throw new AdempiereException("Extension not found: " + extensionId);				
		}

		validateDependencies(extension);

		if (extension.hasBundles()) {
			JsonArray bundles = extension.getBundles();
			for (JsonElement bel : bundles) {
				JsonObject b = bel.getAsJsonObject();
				if (b.has("symbolicName")) {
					String symbolicName = b.get("symbolicName").getAsString();
					for (Bundle bundle : ExtensionManagerActivator.context.getBundles()) {
						if (symbolicName.equals(bundle.getSymbolicName())) {
							bundle.start();
						}
					}
				}
			}
		}
		
		mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Installed);
		mExtension.saveEx();
		
		enableExtensionEntities(mExtension);
		
		CacheMgt.get().reset();
	}

	/**
	 * Set extension entities to active
	 * @param mExtension
	 */
	public void enableExtensionEntities(MExtension mExtension) {
		List<MExtensionEntity> entities = new Query(Env.getCtx(), MExtensionEntity.Table_Name, "AD_Extension_ID=?", null)
				.setParameters(mExtension.getAD_Extension_ID())
				.list();
		for (MExtensionEntity entity : entities) {
			MTable table = MTable.get(Env.getCtx(), entity.getAD_Table_ID());
			if (table == null)
				continue;
			POInfo poInfo = POInfo.getPOInfo(Env.getCtx(), table.getAD_Table_ID());
			if (poInfo == null || poInfo.getTableName() == null)
				continue;
			PO po = table.getPOByUU(entity.getRecord_UU(), null);
			if (po != null) {
				if (po.get_ColumnIndex("IsActive") >= 0) {
					if (po.set_Value("IsActive", true))
						po.saveEx();
				}
			}
		}
	}

	/**
	 * Create extension archive (.idext)
	 * @param extension
	 * @return
	 * @throws Exception
	 */
	public File buildArchive(ExtensionMetadata extension) throws Exception {
		File tempFile = File.createTempFile("extension", ".idext");
		try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempFile))) {
			// 1. metadata.json
			zos.putNextEntry(new ZipEntry("metadata.json"));
			zos.write(extension.toString().getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();

			// 2. info.md
			if (extension.hasInfoUrl()) {
				String infoUrl = extension.getInfoUrl();
				infoUrl = toRawGithubPath(infoUrl);
				byte[] data = null;
				try {
					data = fetchBytes(infoUrl);
				} catch (Exception e) {
					log.log(Level.WARNING, "Failed to fetch info: " + infoUrl, e);
				}
				if (data != null) {
					zos.putNextEntry(new ZipEntry("info.md"));
					zos.write(data);
					zos.closeEntry();
				}
			}

			// 3. CHANGELOG.md
			if (extension.hasChangeLogUrl()) {
				String changelogUrl = extension.getChangeLogUrl();
				changelogUrl = toRawGithubPath(changelogUrl);
				byte[] data = null;
				try {
					data = fetchBytes(changelogUrl);
				} catch (Exception e) {}
				if (data != null) {
					zos.putNextEntry(new ZipEntry("CHANGELOG.md"));
					zos.write(data);
					zos.closeEntry();
				}
			}

			// 4. assets
			if (extension.hasAssets()) {
				JsonArray assets = extension.getAssets();
				for (JsonElement ael : assets) {
					JsonObject a = ael.getAsJsonObject();
					if (a.has("url") && a.has("name")) {
						String aUrl = a.get("url").getAsString();
						aUrl = toRawGithubPath(aUrl);
						String assetName = Paths.get(a.get("name").getAsString()).getFileName().toString();
						try {
							byte[] data = null;
							try {
								data = fetchBytes(aUrl);
							} catch (Exception e) {}
							if (data != null) {
								zos.putNextEntry(new ZipEntry("assets/" + assetName));
								zos.write(data);
								zos.closeEntry();
							}
						} catch (Exception e) {
							log.log(Level.WARNING, "Failed to fetch asset: " + aUrl, e);
						}
					}
				}
			}

			// 5. bundles
			if (extension.hasBundles()) {
				JsonArray bundles = extension.getBundles();
				for (JsonElement bel : bundles) {
					JsonObject b = bel.getAsJsonObject();
					if (b.has("downloadUrl")) {
						String dUrl = toRawGithubPath(b.getAsJsonPrimitive("downloadUrl").getAsString());
						var fileNamePath = Paths.get(URI.create(dUrl).getPath()).getFileName();
						String fileName = fileNamePath != null ? fileNamePath.toString() : "";
						if (!fileName.endsWith(".jar") && b.has("symbolicName")) {
							fileName = b.getAsJsonPrimitive("symbolicName").getAsString() + ".jar";
						}
						if (!fileName.endsWith(".jar")) {
							log.log(Level.WARNING, "Failed to get file name: " + dUrl);
							continue;
						}
						
						zos.putNextEntry(new ZipEntry("bundles/" + fileName));
						streamTo(dUrl, zos);
						zos.closeEntry();
					}
				}
			}
		} catch (Exception e) {
			tempFile.delete();
			throw e;
		}
		return tempFile;
	}

	/**
	 * Check if all OSGi bundles of an extension are installed
	 * @param extension
	 * @return true if all OSGi bundles of an extension are installed
	 */
	public boolean checkAllBundlesInstalled(ExtensionMetadata extension) {
		JsonArray bundles = extension.getBundles();
		if (bundles == null || bundles.size() == 0) return false;
		
		for (JsonElement bel : bundles) {
			String symbolicName = bel.getAsJsonObject().get("symbolicName").getAsString();
			boolean found = false;
			for (Bundle bundle : ExtensionManagerActivator.context.getBundles()) {
				if (symbolicName.equals(bundle.getSymbolicName())) {
					found = true;
					break;
				}
			}
			if (!found) return false;
		}
		return true;
	}

	/**
	 * Create MExtension record
	 * @param extension
	 * @return
	 */
	public MExtension autoRegisterExtension(ExtensionMetadata extension) {
		// update existing state if exits
		MExtension mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
				.setParameters(extension.getId())
				.first();
		if (mExtension != null) {
			mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Installed);
			mExtension.setIsActive(true);
			mExtension.saveEx();
			return mExtension;
		}
		mExtension = new MExtension(Env.getCtx(), 0, null);
		mExtension.setExtensionId(extension.getId());
		mExtension.setName(extension.getName());
		mExtension.setExtensionVersion(extension.getVersion());
		mExtension.setExtensionMetadata(extension.toString());
		mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Installed);
		mExtension.saveEx();
		return mExtension;
	}

	/**
	 * Create MExtensionEntity records for an extension from 2Pack installed histories
	 * @param mExtension
	 * @param extension
	 */
	public void syncExtensionEntities(MExtension mExtension, ExtensionMetadata extension) {
		final Properties ctx = new Properties();
		ctx.putAll(ServerContext.getCurrentInstance());
		CompletableFuture.runAsync(() -> {
			try {
				ServerContext.setCurrentInstance(ctx);
				if (!extension.hasBundles()) return;
				JsonArray bundles = extension.getBundles();
				for (JsonElement bel : bundles) {
					String symbolicName = bel.getAsJsonObject().get("symbolicName").getAsString();
					List<MPackageImp> imps = new Query(Env.getCtx(), MPackageImp.Table_Name, "Name=? AND PK_Status=?", null)
							.setParameters(symbolicName, MPackageImp.PACKAGE_STATUS_COMPLETED)
							.setOnlyActiveRecords(true)
							.list();
					for (MPackageImp imp : imps) {
						List<MPackageImpDetail> details = new Query(Env.getCtx(), MPackageImpDetail.Table_Name, "AD_Package_Imp_ID=? AND Success=? AND Action IN (?,?)", null)
								.setParameters(imp.getAD_Package_Imp_ID(), MPackageImpDetail.ACTION_STATUS_SUCCESS, MPackageImpDetail.ACTION_INSERT, MPackageImpDetail.ACTION_UPDATE)
								.setOnlyActiveRecords(true)
								.list();
						for (MPackageImpDetail detail : details) {
							MExtensionEntity entity = new Query(Env.getCtx(), MExtensionEntity.Table_Name, "AD_Extension_ID=? AND AD_Table_ID=? AND Record_UU=?", null)
									.setParameters(mExtension.getAD_Extension_ID(), detail.getAD_Table_ID(), detail.getRecord_UU())
									.setOnlyActiveRecords(true)
									.first();
							if (entity == null) {
								entity = new MExtensionEntity(Env.getCtx(), 0, null);
								entity.setAD_Extension_ID(mExtension.getAD_Extension_ID());
								entity.setAD_Table_ID(detail.getAD_Table_ID());
								entity.setRecord_UU(detail.getRecord_UU());
								entity.saveEx();
							}
						}
					}
				}
			} finally {
				ServerContext.dispose();
			}
		}).exceptionally(e -> {
			log.log(Level.SEVERE, "Failed to sync extension entities for " + extension.getId(), e);
			return null;
		});
	}

	/**
	 * Prepare MExtension record for installation of extension
	 * @param extension
	 * @return MExtension record with Installing state
	 * @throws Exception
	 */
	public MExtension prepareInstall(ExtensionMetadata extension) throws Exception {
		String id = extension.getId();

		MExtension mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
				.setParameters(id)
				.setOnlyActiveRecords(true)
				.first();
		if (mExtension != null && MExtension.EXTENSIONSTATE_Installing.equals(mExtension.getExtensionState())) {
			throw new IllegalStateException(Msg.getMsg(Env.getCtx(), "ExtensionAlreadyInstalling")); //Extension is already being installed
		}

		if (extension.hasBundles() && ExtensionManagerActivator.context != null) {
			JsonArray bundles = extension.getBundles();
			for (JsonElement bel : bundles) {
				JsonObject b = bel.getAsJsonObject();
				if (!b.has("symbolicName"))
					continue;
				Version declared = declaredBundleVersion(extension, b);
				if (declared == null)
					continue;
				String symbolicName = b.get("symbolicName").getAsString();
				for (Bundle bundle : ExtensionManagerActivator.context.getBundles()) {
					if (!symbolicName.equals(bundle.getSymbolicName()) || bundle.getState() == Bundle.UNINSTALLED)
						continue;
					Version currentVersion = bundle.getVersion();
					if (currentVersion != null && currentVersion.compareTo(declared) > 0) {
						throw new IllegalStateException(Msg.getMsg(Env.getCtx(), "ExtensionBundleNewerVersionInstalled", new Object[] {symbolicName, currentVersion})); //A newer version of bundle {0} ({1}) is already installed
					}
				}
			}
		}

		if (mExtension == null) {
			mExtension = new MExtension(Env.getCtx(), 0, null);
			mExtension.setExtensionId(id);
			mExtension.setName(extension.getName());
			// ExtensionVersion is mandatory; the installed version is written after a successful start
			mExtension.setExtensionVersion(NOT_INSTALLED_VERSION);
		}
		mExtension.setExtensionMetadata(extension.toString());
		mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Installing);
		mExtension.saveEx();
		
		return mExtension;
	}

	/**
	 * Update MExtension record to Installed state
	 * @param extension
	 */
	public void handleInstallationSuccess(ExtensionMetadata extension) {
		String extensionId = extension.getId();
		if (extensionId != null) {
			MExtension mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
					.setParameters(extensionId)
					.first();
			if (mExtension != null) {
				if (extension.hasVersion())
					mExtension.setExtensionVersion(extension.getVersion());
				mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Installed);
				mExtension.saveEx();
			}
		}
	}

	/**
	 * Update MExtension record to Error state
	 * @param extension
	 */
	public void handleInstallationFailure(ExtensionMetadata extension) {
		try {
			String extensionId = extension.getId();
			if (extensionId != null) {
				MExtension mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
						.setParameters(extensionId)
						.first();
				if (mExtension != null) {
					mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Error);
					mExtension.saveEx();
				}
			}
		} catch (Exception e) {
			log.log(Level.SEVERE, "Failed to update extension state to Error", e);
		} finally {
			try {
				uninstallBundles(extension);
			} catch (Exception e) {
				log.log(Level.SEVERE, "Failed to cleanup bundles after installation failure", e);
			}
		}
	}

	/**
	 * Validate dependencies of an extension
	 * @param metadata
	 * @throws AdempiereException if validation fail
	 */
	public void validateDependencies(ExtensionMetadata metadata) {
		// Dependency Check
		if (metadata.hasDependencies()) {
			JsonArray dependencies = metadata.getDependencies();
			for (JsonElement depEl : dependencies) {
				JsonObject depObj = depEl.getAsJsonObject();
				if (!depObj.has("id") || !depObj.has("version")) {
					continue;
				}
				String depId = depObj.get("id").getAsString();
				String depVersionStr = depObj.get("version").getAsString();
				VersionRange depVersionRange = new VersionRange(depVersionStr);
				
				MExtension depExt = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
						.setParameters(depId)
						.setOnlyActiveRecords(true)
						.first();
				
				if (depExt == null || !MExtension.EXTENSIONSTATE_Installed.equals(depExt.getExtensionState())) {
					//Missing dependency: {0}. Please install it first
					throw new AdempiereException(Msg.getMsg(Env.getCtx(), "MissingDependency", new Object[] {depId}));
				}
				
				Version installedDepVersion = Version.parseVersion(depExt.getExtensionVersion());
				if (!depVersionRange.includes(installedDepVersion)) {
					//Incompatible dependency version: {0}. Required {1} and above but installed {2}
					throw new AdempiereException(Msg.getMsg(Env.getCtx(), "IncompatibleDependencyVersion", new Object[] {depId, depVersionStr, depExt.getExtensionVersion()}));
				}
			}
		}
	}

	/**
	 * Validate no other extension depends on this extension
	 * @param metadata
	 * @throws AdempiereException if validation fail
	 */
	public void validateNoDependentExtensions(ExtensionMetadata metadata) {
		String extensionId = metadata.getId();
		String sql = null;
		Object[] params = null;
		
		if (DB.isPostgreSQL()) {
			sql = "SELECT Name FROM AD_Extension " +
				  "WHERE jsonb_path_exists(ExtensionMetadata, '$.dependencies[*].id ? (@ == $extId)', CAST(? AS jsonb)) " +
				  "AND ExtensionState = 'IN' " +
				  "AND ExtensionId != ? AND IsActive='Y' AND AD_Client_ID=0";
			String jsonVars = "{\"extId\": \"" + extensionId + "\"}";
			params = new Object[] { jsonVars, extensionId };
		} else if (DB.isOracle()) {
			sql = "SELECT Name FROM AD_Extension " +
				  "WHERE JSON_EXISTS(ExtensionMetadata, '$.dependencies[*]?(@.id == $extId)' PASSING ? AS \"extId\") " +
				  "AND ExtensionState = 'IN' " +
				  "AND ExtensionId != ? AND IsActive='Y' AND AD_Client_ID=0";
			params = new Object[] { extensionId, extensionId };
		}
		
		if (sql != null) {
			List<String> dependents = new ArrayList<>();
			try (java.sql.PreparedStatement pstmt = DB.prepareStatement(sql, null)) {
				for (int i = 0; i < params.length; i++) {
					pstmt.setObject(i + 1, params[i]);
				}
				try (java.sql.ResultSet rs = pstmt.executeQuery()) {
					while (rs.next()) {
						dependents.add(rs.getString(1));
					}
				}
			} catch (Exception e) {
				log.log(Level.SEVERE, "Failed to execute dependency validation SQL: " + sql, e);
				throw new AdempiereException("Failed to validate dependencies via SQL: " + e.getMessage());
			}
			
			if (!dependents.isEmpty()) {
				// Cannot uninstall extension {0} because the following extensions depend on it: {1}
				throw new AdempiereException(Msg.getMsg(Env.getCtx(), "ExtensionHasDependents", new Object[] {metadata.getName(), String.join(", ", dependents)}));
			}
		} else {
			// Fallback to slower Java-based check for other databases
			List<MExtension> installedExtensions = getInstalledExtensions();
			List<String> dependents = new ArrayList<>();
			for (MExtension ext : installedExtensions) {
				if (ext.getExtensionId().equals(extensionId)) {
					continue;
				}
				String metadataJson = ext.getExtensionMetadata();
				if (metadataJson == null || metadataJson.isEmpty()) {
					continue;
				}
				try {
					ExtensionMetadata extMetadata = new ExtensionMetadata(JsonParser.parseString(metadataJson).getAsJsonObject());
					if (extMetadata.hasDependencies()) {
						JsonArray dependencies = extMetadata.getDependencies();
						for (JsonElement depEl : dependencies) {
							JsonObject depObj = depEl.getAsJsonObject();
							if (depObj.has("id") && extensionId.equals(depObj.get("id").getAsString())) {
								dependents.add(ext.getName());
								break;
							}
						}
					}
				} catch (Exception e) {
					log.log(Level.WARNING, "Failed to parse metadata for extension: " + ext.getExtensionId(), e);
				}
			}
			if (!dependents.isEmpty()) {
				// Cannot uninstall extension {0} because the following extensions depend on it: {1}
				throw new AdempiereException(Msg.getMsg(Env.getCtx(), "ExtensionHasDependents", new Object[] {metadata.getName(), String.join(", ", dependents)}));
			}
		}
	}

	/**
	 * Validate iDempiere version compatibility
	 * @param metadata
	 * @throws AdempiereException if validation fail
	 */
	public void validateIDempiereVersion(ExtensionMetadata metadata) {
		String requiredVersion = metadata.getIDempiereVersion();
		VersionRange requiredVersionRange = new VersionRange(requiredVersion);		
		String currentVersion = org.compiere.Adempiere.getVersion();
		Version currentVersionObj = Version.parseVersion(currentVersion);
		if (!requiredVersionRange.includes(currentVersionObj)) {
			//Incompatible iDempiere version. Extension requires {0} and above but current version is {1}"
			throw new AdempiereException(Msg.getMsg(Env.getCtx(), "IncompatibleIdempiereVersion", new Object[] { requiredVersion, currentVersion }));
		}
	}

	public void installExtension(ExtensionMetadata metadata, Callback<String> statusCallback) throws Exception {
		List<Bundle> installedBundles = new ArrayList<>();
				
		// Download and Install OSGi Bundles
		for (JsonElement el : metadata.getBundles()) {
			JsonObject bundleObj = el.getAsJsonObject();
			if (!bundleObj.has("downloadUrl")) {
				//Bundle {0} is missing downloadUrl
				throw new AdempiereException(Msg.getMsg(Env.getCtx(), "BundleNoDownloadURL", new Object[]{bundleObj.get("symbolicName").getAsString()}));
			}
			String downloadUrl = bundleObj.get("downloadUrl").getAsString();
			String symbolicName = bundleObj.get("symbolicName").getAsString();
			String id = metadata.getId();
			PackageImpDelegate.addExtension(symbolicName, id);
			
			// convert github blob to raw if needed
			if (downloadUrl.contains("github.com") && downloadUrl.contains("/blob/")) {
				downloadUrl = downloadUrl.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/");
			}
		
			if (statusCallback != null)
				statusCallback.onCallback(Msg.getMsg(Env.getCtx(), "DownloadingBundleFrom", new Object[]{bundleObj.get("symbolicName").getAsString(), downloadUrl}));

			File tempZip = File.createTempFile("extbundle_", ".jar");
			try {
				try (FileOutputStream fos = new FileOutputStream(tempZip)) {
					streamTo(downloadUrl, fos);
				}

				if (bundleObj.has("sha256")) {
					String expectedSha256 = bundleObj.get("sha256").getAsString();
					String actualSha256 = calculateSHA256(tempZip);
					if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
						//BundleSHA256HashNotMatch SHA256 hash mismatch for bundle {0}. Expected {1} but got {2}
						throw new AdempiereException(Msg.getMsg(Env.getCtx(), "BundleSHA256HashNotMatch", new Object[]{downloadUrl, expectedSha256, actualSha256}));
					}
				}

				if (statusCallback != null)
					statusCallback.onCallback(Msg.getMsg(Env.getCtx(), "InstallingExtensionBundle", new Object[]{downloadUrl}));
				Bundle bundle = replaceOrInstallBundle(symbolicName, downloadUrl, tempZip);
				installedBundles.add(bundle);
			} finally {
				tempZip.delete();
			}
		}
		
		// Start (Activate) Bundles and Check for 2pack		
		List<String> twoPackBundles = new ArrayList<>();
		for (Bundle bundle : installedBundles) {
			Enumeration<URL> entries = bundle.findEntries("META-INF", "2Pack_*.zip", false);
			if (entries != null && entries.hasMoreElements()) {
				twoPackBundles.add(bundle.getSymbolicName());
			}
			if (statusCallback != null)
				statusCallback.onCallback(Msg.getMsg(Env.getCtx(), "StartingExtensionBundle", new Object[]{bundle.getSymbolicName()}));
			bundle.start();			
		}

		if (twoPackBundles.size() > 0) {
			StringBuilder whereBuilder = new StringBuilder("Name IN (");
			for (int i = 0; i < twoPackBundles.size(); i++) {
				whereBuilder.append("?");
				if (i < twoPackBundles.size() - 1) {
					whereBuilder.append(",");
				}
			}
			whereBuilder.append(") AND PK_Status = ?");
			List<Object> params = new ArrayList<>(twoPackBundles);
			params.add(MPackageImp.PACKAGE_STATUS_INSTALLING);
			Query query = new Query(Env.getCtx(), MPackageImp.Table_Name, whereBuilder.toString(), null)
					.setParameters(params.toArray());
			int count = query.count();
			if (count > 0) {
				if (statusCallback != null)
					statusCallback.onCallback(Msg.getMsg(Env.getCtx(), "ImportingExtensionDataInBackground"));
			}
		}

		handleInstallationSuccess(metadata);
	}

	private byte[] fetchBytes(String url) throws Exception {
		if (url.startsWith("file:")) {
			validateFileURL(url);
			try (InputStream is = new URL(url).openStream()) {
				return is.readAllBytes();
			}
		} else {
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofSeconds(30)).build();
			HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() == 200) {
				return response.body();
			}
			throw new AdempiereException(Msg.getMsg(Env.getCtx(), "HttpFetchFailed", new Object[] {response.statusCode(), url}));
		}
	}

	private String fetchString(String url) throws Exception {
		return new String(fetchBytes(url), StandardCharsets.UTF_8);
	}

	private void streamTo(String url, OutputStream out) throws Exception {
		if (url.startsWith("file:")) {
			validateFileURL(url);
			try (InputStream is = new URL(url).openStream()) {
				is.transferTo(out);
			}
		} else {
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofSeconds(30)).build();
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				throw new AdempiereException(Msg.getMsg(Env.getCtx(), "HttpFetchFailed", new Object[] {response.statusCode(), url}));
			}
			try (InputStream is = response.body()) {
				is.transferTo(out);
			}
		}
	}

	private void validateFileURL(String downloadUrl) {
		String repoUrl = SystemProperties.getIDempiereRepositoryUrl();
		// disallow download of local file if repourl is not file:
		if (!repoUrl.startsWith("file:")) {
			throw new AdempiereException("Local file download not supported when iDempiere repository is remote.");
		}
	}

	private String calculateSHA256(File file) throws Exception {
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
		try (java.io.InputStream is = new java.io.FileInputStream(file)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = is.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}
		byte[] hash = digest.digest();
		StringBuilder hexString = new StringBuilder();
		for (byte b : hash) {
			String hex = Integer.toHexString(0xff & b);
			if (hex.length() == 1) {
				hexString.append('0');
			}
			hexString.append(hex);
		}
		return hexString.toString();
	}

}
