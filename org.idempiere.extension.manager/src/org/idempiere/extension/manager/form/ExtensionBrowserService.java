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
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CompletableFuture;

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
				if (hasStagedBundle(extension)) {
					// Not abandoned: the new revision is parked and waiting for a restart.
					continue;
				}
				if (isDeclaredVersionRunning(extension)) {
					// The update already applied; reconcileStagedInstalls promotes this row.
					continue;
				}
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
	 * Bundles named by symbolic name.
	 * @param symbolicName bundle to look up
	 * @return live bundles with that name, never null
	 */
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

	/**
	 * The currently running instance of a bundle, if any.
	 * @param symbolicName bundle to look up
	 * @return a live bundle, or null when none is installed
	 */
	private Bundle liveBundleNamed(String symbolicName) {
		for (Bundle bundle : bundlesNamed(symbolicName)) {
			if (bundle.getState() != Bundle.UNINSTALLED)
				return bundle;
		}
		return null;
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

	/**
	 * Result of installing one bundle.
	 * <p>
	 * {@code created} is true only when this attempt is the one that put the bundle there, so a
	 * rollback may remove it. A bundle that was already installed belongs to the operator and must
	 * survive a failed install untouched.
	 * <p>
	 * {@code deployed} means the new revision was written to {@code plugins/} and registered in
	 * {@code bundles.info}. It takes effect at the next server start; nothing in the running
	 * framework was touched.
	 */
	public static final class BundleInstallResult {
		private final Bundle bundle;
		private final boolean created;
		private final boolean deployed;
		private final boolean dependentsAwaitRestart;

		BundleInstallResult(Bundle bundle, boolean created, boolean deployed) {
			this(bundle, created, deployed, false);
		}

		BundleInstallResult(Bundle bundle, boolean created, boolean deployed, boolean dependentsAwaitRestart) {
			this.bundle = bundle;
			this.created = created;
			this.deployed = deployed;
			this.dependentsAwaitRestart = dependentsAwaitRestart;
		}

		public Bundle getBundle() {
			return bundle;
		}

		public boolean isCreated() {
			return created;
		}

		public boolean isDeployed() {
			return deployed;
		}

		/**
		 * Whether the bundle is running but some dependents keep their previous wiring.
		 * <p>
		 * The install succeeded; only a refresh would rewire those dependents, so the operator is
		 * told that a restart is needed for full effect.
		 * @return true when at least one dependent is not wired to this bundle yet
		 */
		public boolean isDependentsAwaitRestart() {
			return dependentsAwaitRestart;
		}
	}

	/**
	 * Outcome of installing a whole extension.
	 */
	public static final class InstallResult {
		private final List<String> deployedBundles;
		private final List<String> dependentsAwaitRestart;

		InstallResult(List<String> deployedBundles) {
			this(deployedBundles, Collections.emptyList());
		}

		InstallResult(List<String> deployedBundles, List<String> dependentsAwaitRestart) {
			this.deployedBundles = deployedBundles;
			this.dependentsAwaitRestart = dependentsAwaitRestart;
		}

		/**
		 * @return symbolic names that were written to disk and need a restart to take effect
		 */
		public List<String> getDeployedBundles() {
			return deployedBundles;
		}

		/**
		 * Bundles that are already running but whose dependents keep their previous wiring.
		 * <p>
		 * Distinct from {@link #getDeployedBundles()}: those are not loaded at all, while these are
		 * serving - only a refresh would connect their dependents.
		 * @return symbolic names needing a restart for full effect
		 */
		public List<String> getDependentsAwaitRestart() {
			return dependentsAwaitRestart;
		}

		public boolean isPendingRestart() {
			return !deployedBundles.isEmpty();
		}

		/**
		 * Whether something is running but incomplete.
		 * @return true when a restart is only needed to rewire dependents
		 */
		public boolean isPartiallyWired() {
			return deployedBundles.isEmpty() && !dependentsAwaitRestart.isEmpty();
		}
	}

	/**
	 * Write a bundle to {@code plugins/} in directory form and register it in {@code bundles.info}.
	 * <p>
	 * This replaces the previous in-place {@code Bundle.update} plus {@code refreshBundles} path.
	 * That path cannot be used here: refreshing a web bundle expands to every bundle wired to it
	 * through Jetty's {@code DeploymentManager}, measured at 119 bundles including
	 * {@code org.adempiere.ui.zk} and {@code org.adempiere.server}. Doing so tears down the
	 * deployed web contexts and the application becomes unreachable.
	 * <p>
	 * Deploying on disk instead has no runtime effect at all: the running revision keeps serving,
	 * and the new one is picked up by the next start. Directory form is what the bundle asks for
	 * ({@code Eclipse-BundleShape: dir}) and matches how
	 * {@code org.idempiere.myappx.extension.repository} is already deployed.
	 * @param extensionId extension the bundle belongs to, recorded in the staged marker
	 * @param symbolicName bundle to deploy
	 * @param jarFile downloaded bundle
	 * @return what was deployed, or a result flagged not-deployed when nothing was written
	 * @throws Exception when the jar cannot be read or written
	 */
	private BundleInstallResult deployToPlugins(String extensionId, String symbolicName, File jarFile) throws Exception {
		File installRoot = bundleInstallRoot();
		if (installRoot == null)
			throw new AdempiereException("Cannot locate the server install directory");

		String version = bundleVersionFromJar(jarFile);
		if (version == null || version.isBlank())
			throw new AdempiereException("Bundle " + symbolicName + " has no Bundle-Version; refusing to deploy");
		String dirName = symbolicName + "_" + version;
		File pluginsDir = new File(installRoot, "plugins");
		File targetDir = new File(pluginsDir, dirName);

		// Evidence for a future in-place path; must run while the old revision is still live.
		diagnoseBeforeDeploy(liveBundleNamed(symbolicName), symbolicName);

		if (!pluginsDir.exists() && !pluginsDir.mkdirs())
			throw new AdempiereException("Cannot create " + pluginsDir);

		// A half-written directory would make the bundle unresolvable at the next start, so it is
		// extracted beside the final name and moved into place only once complete.
		File tempDir = new File(pluginsDir, dirName + ".tmp");
		deleteRecursively(tempDir);
		extractJar(jarFile, tempDir);
		deleteRecursively(targetDir);
		if (!tempDir.renameTo(targetDir)) {
			deleteRecursively(tempDir);
			throw new AdempiereException("Cannot move " + tempDir + " to " + targetDir);
		}

		removeOtherVersions(pluginsDir, symbolicName, dirName);
		updateBundlesInfo(installRoot, symbolicName, version, "plugins/" + dirName + "/");

		// Written last: the marker now means "on disk but not loaded by this process", which is what
		// the UI and reconcileStagedInstalls read. Any failure above leaves no marker behind, so a
		// partial deploy is never reported as pending.
		writePendingMarker(extensionId, symbolicName, version, jarFile);

		log.warning("DEPLOYED " + symbolicName + " " + version + " to " + targetDir
				+ " (takes effect at the next server start)");
		return new BundleInstallResult(null, false, true);
	}

	/**
	 * Whether this bundle was deployed as files and is therefore owned by {@code bundles.info}.
	 * <p>
	 * Matters because a bundle must have exactly one source of truth. A bundle that is present in
	 * {@code bundles.info} is loaded from {@code plugins/} at start; installing it dynamically as
	 * well would register a second revision under a different location and leave two records that
	 * disagree. Such bundles are always updated through a file deployment.
	 * @param symbolicName bundle to check
	 * @return true when {@code bundles.info} already has a line for it
	 */
	private boolean isFileDeployed(String symbolicName) {
		File installRoot = bundleInstallRoot();
		if (installRoot == null)
			return false;
		File info = new File(installRoot,
				"configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");
		if (!info.isFile())
			return false;
		try {
			for (String line : java.nio.file.Files.readAllLines(info.toPath(), StandardCharsets.UTF_8)) {
				if (line.isBlank() || line.startsWith("#"))
					continue;
				int comma = line.indexOf(',');
				String existing = comma > 0 ? line.substring(0, comma).trim() : line.trim();
				if (existing.equals(symbolicName))
					return true;
			}
		} catch (Exception e) {
			log.log(Level.FINE, "Cannot read bundles.info while checking " + symbolicName, e);
		}
		return false;
	}

	/**
	 * Install a bundle that is not running yet, directly into the framework.
	 * <p>
	 * Per the OSGi specifications this needs no refresh. Core, Bundle Wiring API: the installation
	 * of a new bundle may allow new wires but must not affect existing wiring until a refresh is
	 * called. Compendium, Web Applications: a WAB is installed with {@code installBundle} like any
	 * other bundle, and the Web Extender deploys its context once the bundle is {@code ACTIVE}.
	 * <p>
	 * So {@code installBundle} followed by {@code start} is the whole operation. It also means a
	 * first install behaves differently from an update, which is why there is no closure check here
	 * - see {@link #dynamicUpdate} for the case that genuinely needs one.
	 * <p>
	 * A dependent that was resolved before this install keeps its previous wiring, so it cannot see
	 * these exports until a refresh. That is reported through
	 * {@link BundleInstallResult#isDependentsAwaitRestart()} rather than being treated as a failure.
	 * @param extensionId extension the bundle belongs to
	 * @param symbolicName bundle to install
	 * @param jarFile downloaded bundle
	 * @return the install result, or null when the bundle cannot be installed in place
	 * @throws Exception when the framework rejects the bundle
	 */
	private BundleInstallResult dynamicInstall(String extensionId, String symbolicName, File jarFile) throws Exception {
		if (ExtensionManagerActivator.context == null)
			return null;
		if (isFileDeployed(symbolicName)) {
			// Already owned by bundles.info: file deployment is the single source of truth.
			return null;
		}
		// Something with the same name is already installed under another location; installing a
		// second revision is an update, which has its own path.
		for (Bundle existing : bundlesNamed(symbolicName)) {
			if (existing.getState() != Bundle.UNINSTALLED)
				return null;
		}

		Bundle installed = null;
		try (java.io.FileInputStream fis = new java.io.FileInputStream(jarFile)) {
			installed = ExtensionManagerActivator.context.installBundle(jarFile.toURI().toString(), fis);
			// No refresh and no closure check here, deliberately. Installing a new bundle only
			// adds wiring; it cannot disturb existing wiring, so nothing needs re-resolving. For a
			// WAB the Web Extender deploys the context on its own once the bundle reaches ACTIVE.
			// Checking the refresh closure at this point would reject an install for an operation
			// that is never performed - that is what wrongly forced a restart on a first install.
			installed.start();
			log.warning("INSTALLED " + symbolicName + " " + installed.getVersion()
					+ " in place; it is running now");

			// A dependent that was already resolved keeps its old wiring until a refresh, so it
			// will not see this bundle's exported packages yet. That is worth reporting, but it is
			// not a reason to refuse the install.
			List<String> unwired = unwiredDependents(installed);
			if (!unwired.isEmpty()) {
				log.warning("INSTALLED " + symbolicName + " in place, but " + unwired.size()
						+ " dependent(s) keep their previous wiring until the next restart: " + unwired);
			}
			return new BundleInstallResult(installed, true, false, !unwired.isEmpty());
		} catch (org.osgi.framework.BundleException e) {
			// Roll back so a failed attempt leaves no half-installed revision behind.
			if (installed != null)
				uninstallQuietly(installed, symbolicName);
			log.log(Level.WARNING, "In-place install of " + symbolicName
					+ " failed; falling back to a file deployment", e);
			return null;
		}
	}

	/**
	 * Bundles that import a package this bundle exports but are still wired elsewhere.
	 * <p>
	 * Used only to report that a restart is needed for full effect. A bundle that was resolved
	 * before the install keeps its existing wiring, so a dependent stays connected to whatever
	 * provider it had - or to nothing, when the package is new. Installation itself succeeds either
	 * way; only a refresh would rewire them, and that is the operation this form avoids.
	 * <p>
	 * The check reads {@code Import-Package} to find candidates, then confirms against the actual
	 * package wires. {@code getDependencyClosure} is deliberately not used: it answers "what would a
	 * refresh affect", not "who is currently connected to me".
	 * @param installed bundle that was just started
	 * @return symbolic names of dependents not yet wired to it; empty when all are connected
	 */
	private List<String> unwiredDependents(Bundle installed) {
		List<String> unwired = new ArrayList<>();
		if (installed == null || ExtensionManagerActivator.context == null)
			return unwired;
		Set<String> exported = new HashSet<>(exportedPackagesOf(installed));
		if (exported.isEmpty())
			return unwired;
		for (Bundle candidate : ExtensionManagerActivator.context.getBundles()) {
			if (candidate.getBundleId() == installed.getBundleId()
					|| candidate.getState() == Bundle.UNINSTALLED)
				continue;
			if (!importsAnyPackageOf(candidate, exported))
				continue;
			if (!isWiredTo(candidate, installed))
				unwired.add(candidate.getSymbolicName());
		}
		return unwired;
	}

	/**
	 * Whether a bundle's manifest imports any of the given packages.
	 * @param candidate bundle to inspect
	 * @param packages package names to look for
	 * @return true when at least one import matches
	 */
	private boolean importsAnyPackageOf(Bundle candidate, Set<String> packages) {
		String header;
		try {
			header = candidate.getHeaders().get("Import-Package");
		} catch (Exception e) {
			return false;
		}
		if (header == null || header.isBlank())
			return false;
		for (String clause : splitHeaderClauses(header)) {
			String imported = clause.split(";")[0].trim();
			if (imported.isEmpty())
				continue;
			if (packages.contains(imported))
				return true;
		}
		return false;
	}

	/**
	 * Whether a bundle is currently wired to another bundle for any package.
	 * @param dependent bundle whose wires to inspect
	 * @param provider bundle that should be the provider
	 * @return true when a package wire connects the dependent to the provider
	 */
	private boolean isWiredTo(Bundle dependent, Bundle provider) {
		try {
			org.osgi.framework.wiring.BundleWiring wiring =
					dependent.adapt(org.osgi.framework.wiring.BundleWiring.class);
			if (wiring == null)
				return false;
			long providerId = provider.getBundleId();
			for (org.osgi.framework.wiring.BundleWire wire : wiring.getRequiredWires(null)) {
				org.osgi.framework.wiring.BundleRevision revision = wire.getProvider();
				if (revision != null && revision.getBundle().getBundleId() == providerId)
					return true;
			}
		} catch (Exception e) {
			log.log(Level.FINE, "Cannot inspect wires of " + dependent.getSymbolicName(), e);
		}
		return false;
	}

	/**
	 * Refresh one bundle and block until the framework reports it finished.
	 * <p>
	 * {@code refreshBundles} is specified to return immediately and complete on another thread, so
	 * a caller that starts the bundle straight afterwards races the refresh and may have its start
	 * undone. Waiting for the completion event makes the sequence deterministic.
	 * @param wiring framework wiring used for the refresh
	 * @param bundle bundle to refresh
	 * @param symbolicName used in the timeout message
	 * @throws InterruptedException when interrupted while waiting
	 * @throws AdempiereException when the refresh does not complete in time
	 */
	private void refreshAndWait(FrameworkWiring wiring, Bundle bundle, String symbolicName)
			throws InterruptedException {
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		org.osgi.framework.FrameworkEvent[] outcome = new org.osgi.framework.FrameworkEvent[1];
		wiring.refreshBundles(Collections.singletonList(bundle), event -> {
			outcome[0] = event;
			latch.countDown();
		});
		if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
			throw new AdempiereException("Timed out refreshing " + symbolicName);
		}
		org.osgi.framework.FrameworkEvent event = outcome[0];
		if (event != null && event.getType() == org.osgi.framework.FrameworkEvent.ERROR) {
			Throwable cause = event.getThrowable();
			throw new AdempiereException("Refresh of " + symbolicName + " reported an error"
					+ (cause == null ? "" : ": " + cause.getMessage()), cause);
		}
	}

	/**
	 * Try to bring a bundle back after a failed in-place update.
	 * <p>
	 * The alternative is leaving it stopped, which silently disables the extension - worse than any
	 * restart cost. Failures here are logged but not propagated: the caller is already handling the
	 * original failure and will fall back to a file deployment.
	 * @param target bundle that was being updated
	 * @param wasActive whether it was running before the attempt
	 * @param symbolicName used in log messages
	 * @param cause the original failure
	 */
	private void restartAfterFailedUpdate(Bundle target, boolean wasActive, String symbolicName, Exception cause) {
		if (wasActive && target.getState() != Bundle.ACTIVE) {
			try {
				target.start();
			} catch (Exception restart) {
				log.log(Level.SEVERE, "Could not restart " + symbolicName + " after failed in-place update", restart);
			}
		}
		log.log(Level.WARNING, "In-place update of " + symbolicName
				+ " failed; falling back to a file deployment", cause);
	}

	/**
	 * Uninstall a bundle, ignoring and logging any failure.
	 * <p>
	 * Used on rollback paths where the original failure is what matters; an uninstall error must
	 * not mask it. Callers fall back to a file deployment afterwards, which replaces the bundle.
	 * @param bundle bundle to remove
	 * @param symbolicName used in the log
	 */
	private void uninstallQuietly(Bundle bundle, String symbolicName) {
		try {
			if (bundle.getState() != Bundle.UNINSTALLED)
				bundle.uninstall();
		} catch (Exception e) {
			log.log(Level.WARNING, "Could not uninstall " + symbolicName + " during rollback", e);
		}
	}

	/**
	 * Update an already running bundle in place, when the framework says it is safe.
	 * <p>
	 * An update creates a new revision but leaves the old wiring serving classes to dependents
	 * (OSGi Core, Bundle Wiring API). Making the new classes visible therefore requires a refresh,
	 * and that refresh restarts every bundle in the closure. For a web bundle the closure includes
	 * Jetty's {@code DeploymentManager} and {@code org.adempiere.server}, so the refresh would take
	 * the deployed web contexts down - measured at 119 bundles for the AI Copilot WAB.
	 * <p>
	 * The closure is computed before anything is touched, so an unsafe case is refused while the
	 * running revision keeps serving. Callers fall back to a file deployment for that case.
	 * <p>
	 * This check belongs here and only here. {@link #dynamicInstall} must not use it: installing a
	 * new bundle only adds wiring and never disturbs existing wiring, so no refresh is needed and a
	 * closure check there would refuse an install for an operation that is never performed.
	 * @param symbolicName bundle to update
	 * @param jarFile downloaded revision
	 * @return the install result, or null when the update is not safe to do in place
	 * @throws Exception when the framework rejects the revision
	 */
	private BundleInstallResult dynamicUpdate(String symbolicName, File jarFile) throws Exception {
		if (ExtensionManagerActivator.context == null)
			return null;
		Bundle target = liveBundleNamed(symbolicName);
		if (target == null)
			return null;
		if (isFileDeployed(symbolicName))
			return null;

		List<String> blockers = closureBlockers(target);
		if (!blockers.isEmpty()) {
			log.warning("Not updating " + symbolicName + " in place: its closure would restart "
					+ String.join(", ", blockers) + "; falling back to a file deployment");
			return null;
		}

		int stateBefore = target.getState();
		boolean wasActive = stateBefore == Bundle.ACTIVE || stateBefore == Bundle.STARTING;
		try {
			if (wasActive)
				target.stop();
			try (java.io.FileInputStream fis = new java.io.FileInputStream(jarFile)) {
				target.update(fis);
			}
			// Safe by the check above: the closure holds nothing but this bundle. The refresh is
			// asynchronous by specification, so it must be waited on before the bundle is started -
			// starting while a refresh runs can be undone by the refresh itself.
			FrameworkWiring wiring = ExtensionManagerActivator.context.getBundle(0).adapt(FrameworkWiring.class);
			if (wiring != null)
				refreshAndWait(wiring, target, symbolicName);
			if (wasActive && target.getState() != Bundle.ACTIVE)
				target.start();
			log.warning("UPDATED " + symbolicName + " to " + target.getVersion()
					+ " in place; it is running now");
			return new BundleInstallResult(target, false, false);
		} catch (InterruptedException e) {
			// Restore the interrupt flag: swallowing it would hide a shutdown request from the
			// caller. The bundle is still brought back and the caller falls back to a file deploy.
			Thread.currentThread().interrupt();
			restartAfterFailedUpdate(target, wasActive, symbolicName, e);
			return null;
		} catch (org.osgi.framework.BundleException e) {
			// A stopped bundle left behind would silently disable the extension, so try to bring
			// the previous revision back and let the caller fall back to a file deployment.
			restartAfterFailedUpdate(target, wasActive, symbolicName, e);
			return null;
		}
	}

	/**
	 * Extract a bundle jar into a directory.
	 * @param jarFile source jar
	 * @param targetDir destination, created if absent
	 * @throws Exception when an entry cannot be written
	 */
	private void extractJar(File jarFile, File targetDir) throws Exception {
		if (!targetDir.exists() && !targetDir.mkdirs())
			throw new AdempiereException("Cannot create " + targetDir);
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarFile)) {
			java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				java.util.zip.ZipEntry entry = entries.nextElement();
				File out = new File(targetDir, entry.getName());
				// Zip entries must not escape the target directory.
				if (!out.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator))
					throw new AdempiereException("Refusing entry outside bundle directory: " + entry.getName());
				if (entry.isDirectory()) {
					if (!out.exists() && !out.mkdirs())
						throw new AdempiereException("Cannot create " + out);
					continue;
				}
				File parent = out.getParentFile();
				if (parent != null && !parent.exists() && !parent.mkdirs())
					throw new AdempiereException("Cannot create " + parent);
				try (java.io.InputStream in = zip.getInputStream(entry)) {
					java.nio.file.Files.copy(in, out.toPath(),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/**
	 * Remove other deployed versions of the same bundle.
	 * <p>
	 * Two directories for one {@code singleton:=true} bundle cannot both resolve, and the start
	 * would then depend on directory ordering. Only directories whose name is
	 * {@code <symbolicName>_} followed by a version are touched, so similarly named bundles are
	 * left alone.
	 * @param pluginsDir plugins directory
	 * @param symbolicName bundle to keep only one version of
	 * @param keepDirName directory name of the version just deployed
	 */
	private void removeOtherVersions(File pluginsDir, String symbolicName, String keepDirName) {
		File[] candidates = pluginsDir.listFiles();
		if (candidates == null)
			return;
		String prefix = symbolicName + "_";
		for (File candidate : candidates) {
			String name = candidate.getName();
			if (name.equals(keepDirName) || !name.startsWith(prefix))
				continue;
			// Only strip versions, never a longer symbolic name that happens to start with prefix.
			String remainder = name.substring(prefix.length());
			if (!remainder.matches("\\d[\\w.\\-]*"))
				continue;
			if (name.endsWith(".bak") || name.endsWith(".tmp"))
				continue;
			log.warning("Removing superseded deployment " + candidate.getName());
			deleteRecursively(candidate);
		}
	}

	/**
	 * Recursively delete a file or directory.
	 * @param file target, ignored when absent
	 */
	private void deleteRecursively(File file) {
		if (file == null || !file.exists())
			return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children)
					deleteRecursively(child);
			}
		}
		if (!file.delete())
			log.warning("Could not delete " + file);
	}

	/**
	 * Replace the {@code bundles.info} entry for a bundle.
	 * <p>
	 * {@code simpleconfigurator} treats this file as the sole list of bundles, so a deployed
	 * directory is invisible until its line is here. The file is rewritten atomically because a
	 * truncated {@code bundles.info} would leave the server unable to start.
	 * @param installRoot server install directory
	 * @param symbolicName bundle to register
	 * @param version deployed version
	 * @param relativePath path relative to the install root, directory form with a trailing slash
	 * @throws Exception when the file cannot be rewritten
	 */
	private void updateBundlesInfo(File installRoot, String symbolicName, String version, String relativePath)
			throws Exception {
		File info = new File(installRoot,
				"configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");
		if (!info.isFile())
			throw new AdempiereException("bundles.info not found at " + info);

		List<String> lines = new ArrayList<>(
				java.nio.file.Files.readAllLines(info.toPath(), StandardCharsets.UTF_8));
		StringBuilder out = new StringBuilder();
		boolean hadBom = !lines.isEmpty() && lines.get(0).startsWith("\uFEFF");
		if (hadBom)
			lines.set(0, lines.get(0).substring(1));

		for (String line : lines) {
			if (line.trim().isEmpty() || line.startsWith("#")) {
				out.append(line).append('\n');
				continue;
			}
			// Format: symbolicName,version,path,startLevel,autoStart. Compare only the first field
			// so "org.foo" never matches "org.foo.bar".
			int comma = line.indexOf(',');
			String existing = comma > 0 ? line.substring(0, comma).trim() : line.trim();
			if (existing.equals(symbolicName)) {
				log.fine("Replacing bundles.info entry for " + symbolicName);
				continue;
			}
			out.append(line).append('\n');
		}
		out.append(symbolicName).append(',').append(version).append(',')
				.append(relativePath).append(",4,true").append('\n');

		java.nio.file.Path target = info.toPath();
		java.nio.file.Path temp = java.nio.file.Files.createTempFile(info.getParentFile().toPath(),
				"bundles.info", ".tmp");
		try {
			java.nio.file.Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
			try {
				java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				// Same filesystem in practice, but fall back rather than fail the deploy.
				java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			java.nio.file.Files.deleteIfExists(temp);
		}
		log.warning("bundles.info updated: " + symbolicName + " " + version + " -> " + relativePath);
	}

	/**
	 * Bundles that must survive a refresh for the server to stay usable. Refreshing any of them
	 * restarts Jetty/ZK/Hazelcast and takes the running application down, which is not something
	 * an extension install should ever do implicitly.
	 * <p>
	 * The Jetty OSGi boot and the OSGi HTTP service are listed because a WAB is wired to them
	 * through {@code DeploymentManager}: refreshing a web bundle pulls them in, and once they are
	 * in the closure {@code ui.zk} and {@code org.adempiere.server} follow. Leaving them out made
	 * the check under-report the blast radius of exactly the bundle this form installs.
	 */
	private static final java.util.Set<String> REFRESH_FORBIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
			"org.eclipse.osgi",
			"org.adempiere.server",
			"org.adempiere.base",
			"org.adempiere.ui.zk",
			"org.adempiere.pipo",
			"org.eclipse.jetty.server",
			"org.eclipse.jetty.ee8.server",
			"org.eclipse.jetty.ee8.osgi.boot",
			"org.adempiere.eclipse.equinox.http.servlet",
			"org.eclipse.equinox.http.servlet",
			"com.hazelcast"));

	/**
	 * Bundles a refresh of {@code seed} would also restart, ignoring the seed itself.
	 * <p>
	 * {@code refreshBundles} always expands its argument to every bundle that depends on it, so
	 * this is the authoritative answer to "is a refresh safe here". It reads the framework's own
	 * dependency closure rather than a static manifest review: a WAB is wired to Jetty's
	 * {@code DeploymentManager} in a way that no manifest inspection reveals, and that is exactly
	 * the case where a refresh tears down deployed web contexts.
	 * <p>
	 * An empty list means the bundle can be installed or refreshed in place. A non-empty list means
	 * the caller must fall back to a file deployment plus restart.
	 * @param seed bundle about to be refreshed
	 * @return symbolic names in the closure that must not be restarted; empty when safe
	 */
	private List<String> closureBlockers(Bundle seed) {
		List<String> blockers = new ArrayList<>();
		if (seed == null || ExtensionManagerActivator.context == null)
			return blockers;
		FrameworkWiring wiring = ExtensionManagerActivator.context.getBundle(0).adapt(FrameworkWiring.class);
		if (wiring == null)
			return blockers;
		String selfName = seed.getSymbolicName();
		Collection<Bundle> closure;
		try {
			closure = wiring.getDependencyClosure(Collections.singletonList(seed));
		} catch (Exception e) {
			// An unreadable closure is treated as unsafe: the caller then falls back to a restart,
			// which always works.
			log.log(Level.WARNING, "Cannot compute dependency closure of " + selfName, e);
			blockers.add("closure-unavailable");
			return blockers;
		}
		if (closure == null)
			return blockers;
		for (Bundle dependent : closure) {
			String name = dependent.getSymbolicName();
			if (name == null || name.equals(selfName))
				continue;
			if (isRefreshForbidden(name) && !blockers.contains(name))
				blockers.add(name);
		}
		return blockers;
	}

	/**
	 * Log the refresh cost of a bundle, purely as evidence.
	 * <p>
	 * Nothing in the deployment model refreshes, so this gates no decision. It is invoked before
	 * every deploy because the closure and dynamic-import counts are the only data that would
	 * justify reopening an in-place update path, and they can only be observed while the bundle is
	 * live.
	 * @param target bundle being deployed
	 * @param symbolicName extension being deployed
	 */
	private void diagnoseBeforeDeploy(Bundle target, String symbolicName) {
		if (target == null)
			return;
		logRefreshClosure(target, symbolicName);
	}

	/**
	 * Log why a refresh of {@code target} reaches {@link #REFRESH_FORBIDDEN} bundles.
	 * <p>
	 * {@code getDependencyClosure} follows dependents, so a core bundle in the closure means some
	 * bundle depends on the extension - or on a bundle the extension pulls in. The closure itself
	 * only names bundles, which makes the reason invisible. This walks the real wires and prints
	 * every edge, so the cause is read from the framework instead of guessed from manifests.
	 * <p>
	 * Logged at WARNING on purpose: this is the diagnostic that decides whether a hot update is
	 * possible, and INFO is below the default threshold in this deployment.
	 * @param target bundle whose refresh is being considered
	 * @param symbolicName extension being updated
	 */
	public void logRefreshClosure(Bundle target, String symbolicName) {
		if (target == null || ExtensionManagerActivator.context == null)
			return;
		try {
			FrameworkWiring wiring = ExtensionManagerActivator.context.getBundle(0).adapt(FrameworkWiring.class);
			if (wiring == null) {
				log.warning(symbolicName + " refresh closure: FrameworkWiring unavailable");
				return;
			}
			Collection<Bundle> closure = wiring.getDependencyClosure(Collections.singletonList(target));
			int size = closure == null ? 0 : closure.size();
			int forbiddenCount = 0;
			int wildcardCount = 0;
			List<String> wildcardNames = new ArrayList<>();
			if (closure != null) {
				for (Bundle dependent : closure) {
					String name = dependent.getSymbolicName();
					if (name == null || name.equals(symbolicName))
						continue;
					if (isRefreshForbidden(name))
						forbiddenCount++;
					if (describeDynamicImport(dependent, symbolicName) != null) {
						wildcardCount++;
						wildcardNames.add(name);
					}
				}
			}
			log.warning("REFRESH-CLOSURE " + symbolicName + " seed=" + target.getBundleId()
					+ " size=" + size + " forbidden=" + forbiddenCount
					+ " dynamicImportOfExtension=" + wildcardCount
					+ " removalPending=" + describeRemovalPending(wiring));
			if (wildcardCount > 0) {
				// The single most useful line for explaining a large closure: these bundles are in it
				// only because a DynamicImport-Package pattern happens to cover what this extension
				// exports, not because they use it.
				log.warning("REFRESH-CLOSURE " + symbolicName + " dynamic importers of its packages: "
						+ wildcardNames);
			}
			if (closure == null)
				return;
			for (Bundle dependent : closure) {
				String name = dependent.getSymbolicName();
				if (name == null || name.equals(symbolicName))
					continue;
				String edge = describeEdgesTo(dependent, symbolicName);
				boolean forbidden = isRefreshForbidden(name);
				log.warning("REFRESH-CLOSURE   " + (forbidden ? "FORBIDDEN " : "          ")
						+ name + " [" + dependent.getBundleId() + "] state=" + stateName(dependent.getState())
						+ " via=" + edge);
			}
		} catch (Exception e) {
			log.log(Level.WARNING, "REFRESH-CLOSURE " + symbolicName + " failed", e);
		}
	}

	private boolean isRefreshForbidden(String name) {
		if (name == null)
			return false;
		if (REFRESH_FORBIDDEN.contains(name))
			return true;
		for (String prefix : REFRESH_FORBIDDEN) {
			if (name.startsWith(prefix + "."))
				return true;
		}
		return false;
	}

	private static String stateName(int state) {
		switch (state) {
			case Bundle.UNINSTALLED: return "UNINSTALLED";
			case Bundle.INSTALLED: return "INSTALLED";
			case Bundle.RESOLVED: return "RESOLVED";
			case Bundle.STARTING: return "STARTING";
			case Bundle.STOPPING: return "STOPPING";
			case Bundle.ACTIVE: return "ACTIVE";
			default: return Integer.toString(state);
		}
	}

	private String describeRemovalPending(FrameworkWiring wiring) {
		try {
			Collection<Bundle> pending = wiring.getRemovalPendingBundles();
			if (pending == null || pending.isEmpty())
				return "none";
			List<String> names = new ArrayList<>();
			for (Bundle b : pending)
				names.add(b.getSymbolicName() + "[" + b.getBundleId() + "]");
			return names.toString();
		} catch (Exception e) {
			return "unknown";
		}
	}

	/**
	 * Every wire of {@code dependent} whose provider is the target or another closure member.
	 * <p>
	 * The namespace matters: {@code osgi.wiring.package} is an import, {@code osgi.wiring.host} is
	 * a fragment attachment, {@code osgi.wiring.bundle} is a Require-Bundle. They have different
	 * fixes, so the raw requirement is printed alongside.
	 * <p>
	 * A bundle with a wildcard {@code DynamicImport-Package} depends on the extension without any
	 * wire existing: the framework only resolves such an import at class-load time, so it never
	 * appears in {@code getRequiredWires} yet still puts the bundle in the refresh closure. That
	 * case used to surface as the unhelpful "indirect", which hid the real cause of a 119-bundle
	 * closure, so the header is inspected explicitly.
	 * @return semicolon separated "namespace requirement" entries
	 */
	private String describeEdgesTo(Bundle dependent, String symbolicName) {
		try {
			org.osgi.framework.wiring.BundleWiring dependentWiring =
					dependent.adapt(org.osgi.framework.wiring.BundleWiring.class);
			if (dependentWiring == null)
				return "not resolved";
			List<String> edges = new ArrayList<>();
			// A fragment also inherits its host's requirement, so report the attachment first.
			if ((dependent.adapt(org.osgi.framework.wiring.BundleRevision.class) != null)) {
				for (org.osgi.framework.wiring.BundleWire wire
						: dependentWiring.getRequiredWires("osgi.wiring.host")) {
					edges.add("HOST->" + nameOf(wire.getProvider()));
				}
			}
			for (org.osgi.framework.wiring.BundleWire wire : dependentWiring.getRequiredWires(null)) {
				String namespace = wire.getRequirement().getNamespace();
				if ("osgi.wiring.host".equals(namespace))
					continue;
				String provider = nameOf(wire.getProvider());
				if (provider == null || !provider.equals(symbolicName))
					continue;
				edges.add(namespace.replace("osgi.wiring.", "") + "=" + wire.getRequirement());
			}
			if (!edges.isEmpty())
				return String.join(" && ", edges);

			String dynamic = describeDynamicImport(dependent, symbolicName);
			if (dynamic != null)
				return dynamic;
			return "indirect (depends on another closure member)";
		} catch (Exception e) {
			return "wire lookup failed: " + e.getMessage();
		}
	}

	/**
	 * Describe the {@code DynamicImport-Package} declaration that makes a bundle a dependent of
	 * the extension.
	 * <p>
	 * These imports have no wire, so they are invisible to the wiring API. Reading the header is
	 * the only way to explain why an unrelated bundle shows up in a refresh closure.
	 * <p>
	 * A pattern is only reported when it can actually match one of the extension's exported
	 * packages. Without that filter every bundle declaring {@code some.other.*} looked guilty, and
	 * the summary count became noise instead of evidence.
	 * @param bundle bundle to inspect
	 * @param symbolicName extension being updated, used to fetch its exported packages
	 * @return explanation, or null when the dynamic import cannot reach this extension
	 */
	private String describeDynamicImport(Bundle bundle, String symbolicName) {
		String header;
		try {
			header = bundle.getHeaders().get("DynamicImport-Package");
		} catch (Exception e) {
			return null;
		}
		if (header == null || header.isBlank())
			return null;
		List<String> exported = exportedPackagesOf(symbolicName);
		if (exported.isEmpty())
			return null;
		for (String clause : splitHeaderClauses(header)) {
			String pattern = clause.split(";")[0].trim();
			if (pattern.isEmpty())
				continue;
			for (String pkg : exported) {
				if (patternMatchesPackage(pattern, pkg))
					return "dynamic-import(" + pattern + ") matches exported package " + pkg
							+ " - the framework treats this bundle as a dependent";
			}
		}
		return null;
	}

	/**
	 * Whether a {@code DynamicImport-Package} pattern matches a package name.
	 * @param pattern header pattern, possibly ending in {@code *}
	 * @param packageName candidate package
	 * @return true when the framework could resolve that package through this pattern
	 */
	private static boolean patternMatchesPackage(String pattern, String packageName) {
		if (pattern.equals("*"))
			return true;
		if (pattern.endsWith(".*"))
			return packageName.startsWith(pattern.substring(0, pattern.length() - 1));
		return pattern.equals(packageName);
	}

	/**
	 * Packages exported by the extension's bundles.
	 * @param symbolicName extension bundle
	 * @return exported package names, empty when the bundle is absent or exports nothing
	 */
	private List<String> exportedPackagesOf(String symbolicName) {
		List<String> packages = new ArrayList<>();
		try {
			for (Bundle bundle : bundlesNamed(symbolicName))
				collectExportedPackages(bundle, packages);
		} catch (Exception e) {
			log.fine("Cannot read exported packages of " + symbolicName + ": " + e.getMessage());
		}
		return packages;
	}

	/**
	 * Packages exported by one specific bundle instance.
	 * @param bundle bundle to inspect
	 * @return exported package names, empty when it exports nothing
	 */
	private List<String> exportedPackagesOf(Bundle bundle) {
		List<String> packages = new ArrayList<>();
		collectExportedPackages(bundle, packages);
		return packages;
	}

	/**
	 * Append the packages a bundle exports.
	 * @param bundle bundle to inspect
	 * @param packages destination list, duplicates are not added
	 */
	private void collectExportedPackages(Bundle bundle, List<String> packages) {
		if (bundle == null)
			return;
		try {
			String header = bundle.getHeaders().get("Export-Package");
			if (header == null || header.isBlank())
				return;
			for (String clause : splitHeaderClauses(header)) {
				String pkg = clause.split(";")[0].trim();
				if (!pkg.isEmpty() && !packages.contains(pkg))
					packages.add(pkg);
			}
		} catch (Exception e) {
			log.fine("Cannot read exported packages of " + bundle.getSymbolicName() + ": " + e.getMessage());
		}
	}

	/**
	 * Split an OSGi header into clauses on commas that are not inside quotes.
	 * @param header raw header value
	 * @return clause list, never null
	 */
	private static List<String> splitHeaderClauses(String header) {
		List<String> clauses = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < header.length(); i++) {
			char c = header.charAt(i);
			if (c == '"')
				quoted = !quoted;
			if (c == ',' && !quoted) {
				clauses.add(current.toString());
				current.setLength(0);
				continue;
			}
			current.append(c);
		}
		if (current.length() > 0)
			clauses.add(current.toString());
		return clauses;
	}

	private static String nameOf(org.osgi.framework.wiring.BundleRevision revision) {
		return revision == null ? null : revision.getSymbolicName();
	}

	/**
	 * Deploy any staged jar left behind by an earlier build.
	 * <p>
	 * Current installs deploy straight to {@code plugins/} - see {@link #deployToPlugins} - so a jar
	 * in {@code staged-bundles/} can only come from a previous version of this manager that parked
	 * updates instead of deploying them. This method exists to migrate those, and to retry a deploy
	 * that failed on a previous start.
	 * <p>
	 * Nothing here touches the running framework: the files are extracted to {@code plugins/} and
	 * registered in {@code bundles.info}, and the next start loads them.
	 */
	public void applyStagedBundles() {
		if (ExtensionManagerActivator.context == null)
			return;
		File dir = stagedBundleDir();
		File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
		if (jars == null || jars.length == 0) {
			// Marker without a jar is the normal steady state after a deploy, so this is not a
			// warning; say nothing and let reconcileStagedInstalls clear it once the version runs.
			return;
		}
		for (File jar : jars) {
			String symbolicName = jar.getName().substring(0, jar.getName().length() - ".jar".length());
			File owner = new File(dir, symbolicName + ".extension");
			File abandoned = new File(dir, symbolicName + FAILED_SUFFIX);
			if (abandoned.exists()) {
				// Gave up on a previous start; retrying every boot only slows startup down.
				log.warning("Staged jar " + jar.getName() + " is abandoned (" + abandoned.getName()
						+ " exists); delete both files to retry");
				continue;
			}
			if (!owner.exists()) {
				// No owner means no extension row to settle afterwards. Leave it in place rather
				// than install a bundle nothing can account for.
				log.warning("Staged jar " + jar.getName() + " has no extension marker; left untouched");
				continue;
			}
			try {
				String extensionId = readMarkerExtensionId(owner);
				if (deployToPlugins(extensionId, symbolicName, jar) == null)
					continue;
				// The marker stays: it is what reconcileStagedInstalls uses to clear the pending
				// state once the deployed version is the one running.
				if (!jar.delete())
					log.warning("Staged jar " + jar.getName() + " could not be deleted after deploy");
				clearAttempts(dir, symbolicName);
				log.warning("Migrated staged jar " + symbolicName + " into plugins/");
			} catch (Exception e) {
				int attempts = recordAttemptFailure(dir, symbolicName);
				if (attempts >= MAX_STAGED_ATTEMPTS) {
					// Stop retrying: a jar that failed this often will not deploy on its own. It is
					// renamed rather than deleted so the file is still there to inspect.
					if (jar.renameTo(abandoned))
						log.log(Level.SEVERE, "Staged deploy of " + symbolicName + " failed " + attempts
								+ " times; abandoned as " + abandoned.getName()
								+ ". Investigate the cause, then delete the .failed and .extension files to retry.", e);
					else
						log.log(Level.SEVERE, "Staged deploy of " + symbolicName + " failed " + attempts
								+ " times and could not be renamed to " + abandoned.getName(), e);
				} else {
					log.log(Level.SEVERE, "Staged deploy of " + symbolicName + " failed (attempt "
							+ attempts + " of " + MAX_STAGED_ATTEMPTS + ")", e);
				}
			}
		}
	}

	/** Marks a staged revision that exceeded {@link #MAX_STAGED_ATTEMPTS}. */
	private static final String FAILED_SUFFIX = ".failed";

	/** Per-bundle counter of failed startup applies, kept next to the staged jar. */
	private static final String ATTEMPTS_SUFFIX = ".attempts";

	/**
	 * Give up after this many startup failures. Each failure means a boot spent time stopping,
	 * updating and refreshing a bundle cluster for nothing, so an unbounded retry is not harmless.
	 */
	private static final int MAX_STAGED_ATTEMPTS = 3;

	/**
	 * Increment the failure counter for a staged bundle.
	 * @param dir staged-bundles directory
	 * @param symbolicName bundle that failed
	 * @return the new attempt count, 1 when the counter was unreadable
	 */
	private int recordAttemptFailure(File dir, String symbolicName) {
		File counter = new File(dir, symbolicName + ATTEMPTS_SUFFIX);
		int attempts = 1;
		try {
			if (counter.exists()) {
				String text = new String(java.nio.file.Files.readAllBytes(counter.toPath()),
						StandardCharsets.UTF_8).trim();
				attempts = Integer.parseInt(text) + 1;
			}
			java.nio.file.Files.writeString(counter.toPath(), Integer.toString(attempts),
					StandardCharsets.UTF_8);
		} catch (Exception e) {
			// A counter that cannot be written must not hide the apply failure itself.
			log.log(Level.WARNING, "Cannot record staged apply attempt for " + symbolicName, e);
		}
		return attempts;
	}

	/**
	 * Drop the failure counter after a successful apply.
	 * @param dir staged-bundles directory
	 * @param symbolicName bundle that applied
	 */
	private void clearAttempts(File dir, String symbolicName) {
		File counter = new File(dir, symbolicName + ATTEMPTS_SUFFIX);
		if (counter.exists() && !counter.delete())
			log.fine("Could not delete attempt counter " + counter.getName());
	}

	/**
	 * Directory holding the pending-restart markers, plus any staged jar from an earlier build.
	 * <p>
	 * A sibling of {@code plugins/}, so it is never scanned as a bundle directory and never
	 * resolved by the running framework. Current installs put only a marker here, because the
	 * bundle itself is extracted into {@code plugins/} immediately.
	 */
	private File stagedBundleDir() {
		// Sibling of plugins/: the running framework never scans it, so nothing is resolved from
		// here until the startup apply installs what it finds.
		File base = bundleInstallRoot();
		if (base == null)
			base = new File(System.getProperty("user.dir"));
		return new File(base, "staged-bundles");
	}

	/**
	 * Directory that contains {@code plugins/} and {@code configuration/}.
	 * <p>
	 * Derived from this bundle's own location first: it is a concrete file path, while
	 * {@code osgi.install.area} may carry a {@code reference:file:} prefix that is not a URI.
	 * @return the install root, or null when it cannot be determined
	 */
	private File bundleInstallRoot() {
		try {
			if (ExtensionManagerActivator.context != null) {
				Bundle self = ExtensionManagerActivator.context.getBundle();
				if (self != null && self.getLocation() != null) {
					String location = self.getLocation();
					// Strip the OSGi "reference:file:" / "file:" decoration before making a URI.
					int scheme = location.indexOf("file:");
					String path = scheme >= 0 ? location.substring(scheme + "file:".length()) : location;
					File bundleFile = new File(path);
					if (!bundleFile.isAbsolute())
						bundleFile = new File(System.getProperty("user.dir"), path);
					// .../plugins/<symbolicName>_<version>[/] -> .../
					File parent = bundleFile.getParentFile();
					if (parent != null && "plugins".equals(parent.getName())) {
						File root = parent.getParentFile();
						if (root != null)
							return root;
					}
				}
			}
		} catch (Exception e) {
			log.log(Level.FINE, "Cannot derive install root from the bundle location", e);
		}
		String home = System.getProperty("osgi.install.area");
		if (home != null) {
			int scheme = home.indexOf("file:");
			String path = scheme >= 0 ? home.substring(scheme + "file:".length()) : home;
			try {
				File dir = new File(java.net.URLDecoder.decode(path, StandardCharsets.UTF_8));
				if (dir.isAbsolute())
					return dir;
			} catch (Exception e) {
				log.log(Level.WARNING, "Cannot resolve osgi.install.area " + home, e);
			}
		}
		return null;
	}

	/**
	 * Record that a deployed revision is on disk but not yet loaded by this process.
	 * <p>
	 * Only a marker is written: the bundle itself is already extracted into {@code plugins/} and
	 * registered in {@code bundles.info} by {@link #deployToPlugins}. The marker is what the UI and
	 * {@link #reconcileStagedInstalls} read to say "restart pending", and it is removed once the
	 * running bundle matches the recorded target version.
	 * @param extensionId extension the bundle belongs to
	 * @param symbolicName bundle that was deployed
	 * @param version version that is now on disk
	 * @param jarFile the deployed jar, read for its version header
	 */
	private void writePendingMarker(String extensionId, String symbolicName, String version, File jarFile) throws Exception {
		if (extensionId == null)
			return;
		File dir = stagedBundleDir();
		if (!dir.exists() && !dir.mkdirs())
			throw new AdempiereException("Cannot create " + dir);
		File owner = new File(dir, symbolicName + ".extension");
		java.nio.file.Files.writeString(owner.toPath(),
				buildMarker(extensionId, symbolicName, version), StandardCharsets.UTF_8);
		if (log.isLoggable(Level.INFO))
			log.info("Pending-restart marker written for " + symbolicName + " " + version);
	}

	/**
	 * Marker content describing a staged revision.
	 * <p>
	 * JSON rather than a bare extension id: when an update does not apply, the first questions are
	 * which version was replaced, which one was meant to take over, and when it was staged. The
	 * plain id answered none of them.
	 * @param extensionId extension the bundle belongs to
	 * @param symbolicName bundle that was deployed
	 * @param version version now on disk
	 * @return single line JSON
	 */
	private String buildMarker(String extensionId, String symbolicName, String version) {
		String from = currentBundleVersion(symbolicName);
		return "{\"extensionId\":\"" + extensionId + "\""
				+ ",\"bundle\":\"" + symbolicName + "\""
				+ ",\"from\":\"" + (from == null ? "" : from) + "\""
				+ ",\"to\":\"" + (version == null ? "" : version) + "\""
				+ ",\"stagedAt\":\"" + java.time.LocalDateTime.now().withNano(0) + "\"}";
	}

	/**
	 * Version of the bundle currently installed for a symbolic name.
	 * @param symbolicName bundle to look up
	 * @return version string, or null when no live bundle exists
	 */
	private String currentBundleVersion(String symbolicName) {
		try {
			for (Bundle bundle : bundlesNamed(symbolicName)) {
				if (bundle.getState() != Bundle.UNINSTALLED)
					return bundle.getVersion().toString();
			}
		} catch (Exception e) {
			log.fine("Cannot read current version of " + symbolicName + ": " + e.getMessage());
		}
		return null;
	}

	/**
	 * Read {@code Bundle-Version} from a jar without installing it.
	 * @param jarFile candidate bundle jar
	 * @return version string, or null when it cannot be read
	 */
	private String bundleVersionFromJar(File jarFile) {
		try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
			java.util.jar.Manifest manifest = jar.getManifest();
			if (manifest == null)
				return null;
			return manifest.getMainAttributes().getValue("Bundle-Version");
		} catch (Exception e) {
			log.fine("Cannot read Bundle-Version from " + jarFile.getName() + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * Extension id recorded in a marker file.
	 * <p>
	 * Accepts both the current JSON form and the original plain-text form, so markers written by an
	 * earlier build keep working instead of becoming unattributable.
	 * @param marker marker file
	 * @return extension id, or null when the file cannot be read
	 */
	private String readMarkerExtensionId(File marker) {
		try {
			String text = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
					StandardCharsets.UTF_8).trim();
			if (text.isEmpty())
				return null;
			if (!text.startsWith("{")) {
				// Legacy marker: the whole file was the extension id.
				return text;
			}
			com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
			if (json.has("extensionId") && !json.get("extensionId").isJsonNull())
				return json.get("extensionId").getAsString();
		} catch (Exception e) {
			log.fine("Cannot read staged marker " + marker.getName() + ": " + e.getMessage());
		}
		return null;
	}

	/**
	 * Delete a staged revision, for an operator who prefers not to apply it.
	 * @param symbolicName bundle that was staged
	 * @return true when something was removed
	 */
	public boolean discardStagedBundle(String symbolicName) {
		File dir = stagedBundleDir();
		boolean removed = false;
		// Everything that belongs to this staged revision goes, including the bookkeeping files, so
		// a later stage of the same bundle starts from a clean slate instead of inheriting an
		// attempt counter or an abandoned flag.
		for (String suffix : new String[] {".extension", ".jar", ATTEMPTS_SUFFIX, FAILED_SUFFIX}) {
			File file = new File(dir, symbolicName + suffix);
			if (file.exists() && file.delete())
				removed = true;
		}
		return removed;
	}

	/**
	 * Bundles with a revision waiting for the next start.
	 * <p>
	 * Driven by the {@code .extension} marker, not the jar: the startup apply deletes the jar as
	 * soon as the framework accepts it, and the marker has to survive until the row is settled.
	 * @return symbolic names of staged bundles
	 */
	public List<String> getStagedBundles() {
		List<String> names = new ArrayList<>();
		File dir = stagedBundleDir();
		File[] files = dir.listFiles((d, name) -> name.endsWith(".extension"));
		if (files == null)
			return names;
		for (File file : files) {
			String name = file.getName();
			names.add(name.substring(0, name.length() - ".extension".length()));
		}
		Collections.sort(names);
		return names;
	}

	/**
	 * Whether a revision of this extension is parked for the next start.
	 * @param extension extension row to check
	 * @return true when one of its bundles is staged
	 */
	public boolean hasStagedBundle(MExtension extension) {
		if (extension == null || extension.getExtensionId() == null)
			return false;
		return !stagedBundlesOf(extension).isEmpty();
	}

	/**
	 * Settle deployed revisions whose marker is still on disk.
	 * <p>
	 * A deploy writes the new revision to {@code plugins/} and records the row as {@code Installed}
	 * immediately, because the deployment itself is complete - only loading it needs a restart. The
	 * marker is what says "the running process has not loaded this yet". Once the running bundle
	 * matches the marker's target version, the marker has served its purpose and is removed.
	 * <p>
	 * Scans {@code Installed} rather than {@code Installing}: the row is already settled at deploy
	 * time, so keying off {@code Installing} would leave every marker on disk forever.
	 */
	public void reconcileStagedInstalls() {
		if (ExtensionManagerActivator.context == null)
			return;
		List<MExtension> candidates = new Query(Env.getCtx(), MExtension.Table_Name,
				"ExtensionState IN (?,?)", null)
				.setParameters(MExtension.EXTENSIONSTATE_Installed, MExtension.EXTENSIONSTATE_Installing)
				.setOnlyActiveRecords(true)
				.list();
		if (candidates.isEmpty())
			return;
		for (MExtension extension : candidates) {
			try {
				List<String> staged = stagedBundlesOf(extension);
				if (staged.isEmpty())
					continue;
				if (!isDeclaredVersionRunning(extension))
					continue;
				for (String symbolicName : staged)
					discardStagedBundle(symbolicName);
				log.info("Deployed revision of " + extension.getExtensionId()
						+ " is now running; staged marker cleared");
			} catch (Exception e) {
				log.log(Level.WARNING, "Failed to reconcile deployed revision of " + extension.getExtensionId(), e);
			}
		}
		CacheMgt.get().reset(MExtension.Table_Name);
	}

	/**
	 * Whether every bundle declared by the row's metadata is the running revision.
	 * <p>
	 * This is the test that separates "the update finished" from "the update is still in flight":
	 * during an install the running bundle is still the old one, so it never matches early.
	 * @param extension extension row in state {@code Installing}
	 * @return true when the metadata version is the one now active
	 */
	private boolean isDeclaredVersionRunning(MExtension extension) {
		if (ExtensionManagerActivator.context == null)
			return false;
		String metadata = extension.getExtensionMetadata();
		if (metadata == null || metadata.trim().isEmpty())
			return false;
		ExtensionMetadata parsed;
		try {
			parsed = new ExtensionMetadata(JsonParser.parseString(metadata).getAsJsonObject());
		} catch (Exception e) {
			return false;
		}
		if (!parsed.hasBundles())
			return false;
		JsonArray bundles = parsed.getBundles();
		if (bundles.size() == 0)
			return false;
		for (JsonElement bel : bundles) {
			JsonObject bundleObj = bel.getAsJsonObject();
			if (!bundleObj.has("symbolicName"))
				continue;
			String symbolicName = bundleObj.get("symbolicName").getAsString();
			Version declared = declaredBundleVersion(parsed, bundleObj);
			if (declared == null)
				continue;
			boolean found = false;
			for (Bundle bundle : bundlesNamed(symbolicName)) {
				if (bundle.getState() == Bundle.UNINSTALLED)
					continue;
				// The catalog version is major.minor.patch; the installed one adds a qualifier.
				if (bundle.getVersion().getMajor() == declared.getMajor()
						&& bundle.getVersion().getMinor() == declared.getMinor()
						&& bundle.getVersion().getMicro() == declared.getMicro()) {
					found = true;
					break;
				}
			}
			if (!found)
				return false;
		}
		return true;
	}

	/**
	 * Symbolic names staged for one extension.
	 * @param extension extension row
	 * @return staged bundle names owned by this extension
	 */
	private List<String> stagedBundlesOf(MExtension extension) {
		List<String> owned = new ArrayList<>();
		String extensionId = extension.getExtensionId();
		if (extensionId == null)
			return owned;
		File dir = stagedBundleDir();
		for (String symbolicName : getStagedBundles()) {
			File owner = new File(dir, symbolicName + ".extension");
			if (!owner.exists())
				continue;
			// Handles both the JSON marker and the plain-text one written by earlier builds.
			if (extensionId.equals(readMarkerExtensionId(owner)))
				owned.add(symbolicName);
		}
		return owned;
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
			if (hasStagedBundle(mExtension)) {
				// Not a stuck row: a newer revision is parked and waiting for a restart. Say so
				// instead of reporting "already being installed", which suggests someone else is
				// working on it.
				throw new IllegalStateException("An update of " + id
						+ " is already staged. Restart the server to apply it before installing again.");
			}
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
	/**
	 * Record an install as complete.
	 * <p>
	 * Tolerates a framework that is already shutting down. A refresh that cascaded into a server
	 * bundle tears the application down while this method runs, and the model layer then fails
	 * with a NullPointerException from {@code BaseActivator.getBundleContext()}. The install did
	 * succeed, so log it and leave the row for the next start rather than aborting the process
	 * with an exception that hides the real cause.
	 * @param extension metadata of the installed extension
	 */
	public void handleInstallationSuccess(ExtensionMetadata extension) {
		String extensionId = extension.getId();
		if (extensionId == null)
			return;
		MExtension mExtension;
		try {
			mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
					.setParameters(extensionId)
					.first();
		} catch (RuntimeException e) {
			log.log(Level.WARNING, "Cannot record install of " + extensionId
					+ " because the framework is shutting down; it will settle on the next start", e);
			return;
		}
		if (mExtension != null) {
			if (extension.hasVersion())
				mExtension.setExtensionVersion(extension.getVersion());
			mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Installed);
			mExtension.saveEx();
		}
	}

	/**
	 * Roll back a failed install.
	 * <p>
	 * Only bundles this attempt created are removed. A bundle that was already installed - the
	 * usual case for an update - belongs to the operator, and uninstalling it is exactly what
	 * took AI Copilot down: the update was refused, then the working 1.0.4 revision was destroyed
	 * as "cleanup". A refused update must leave the running system alone.
	 * @param extension metadata of the attempted install
	 * @param createdBundles bundles this attempt installed; may be empty
	 */
	public void handleInstallationFailure(ExtensionMetadata extension, List<Bundle> createdBundles) {
		MExtension mExtension = null;
		try {
			String extensionId = extension.getId();
			if (extensionId != null) {
				mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
						.setParameters(extensionId)
						.first();
			}
		} catch (Exception e) {
			log.log(Level.SEVERE, "Failed to read extension state for rollback", e);
		}

		// Remove this attempt's bundles first, then decide the row state from what actually
		// survived. Deciding first would count a bundle that is about to be uninstalled as
		// "still running" and leave the row Installed with nothing installed.
		if (createdBundles != null) {
			for (Bundle bundle : createdBundles) {
				try {
					if (bundle.getState() != Bundle.UNINSTALLED) {
						if (bundle.getState() == Bundle.ACTIVE)
							bundle.stop();
						bundle.uninstall();
					}
				} catch (Exception e) {
					// A shutting-down framework refuses these calls; the bundles are about to be
					// torn down anyway, so there is nothing to recover here.
					log.log(Level.WARNING, "Failed to cleanup bundle " + bundle.getSymbolicName()
							+ " after installation failure (framework may be stopping)", e);
				}
			}
		}

		if (mExtension == null)
			return;
		try {
			String previousVersion = mExtension.getExtensionVersion();
			boolean survived = isBundleStillRunning(extension, createdBundles);
			String state = survived ? MExtension.EXTENSIONSTATE_Installed : MExtension.EXTENSIONSTATE_Error;
			mExtension.setExtensionState(state);
			mExtension.saveEx();
			if (survived) {
				// The update was refused, not applied: the version that is serving did not change.
				log.warning("Install of " + extension.getId()
						+ " failed but its bundle is still running; kept Installed at " + previousVersion);
			} else {
				log.warning("Install of " + extension.getId() + " failed; marked Error");
			}
		} catch (Exception e) {
			log.log(Level.SEVERE, "Failed to update extension state after installation failure", e);
		}
	}

	/**
	 * Whether any bundle of the extension survived the rollback.
	 * @param extension metadata to inspect
	 * @param removed bundles this attempt created, already uninstalled
	 * @return true when at least one bundle of the extension is present and not one we removed
	 */
	private boolean isBundleStillRunning(ExtensionMetadata extension, List<Bundle> removed) {
		if (extension == null || !extension.hasBundles() || ExtensionManagerActivator.context == null)
			return false;
		for (JsonElement bel : extension.getBundles()) {
			JsonObject b = bel.getAsJsonObject();
			if (!b.has("symbolicName"))
				continue;
			String symbolicName = b.get("symbolicName").getAsString();
			for (Bundle bundle : bundlesNamed(symbolicName)) {
				if (bundle.getState() == Bundle.UNINSTALLED)
					continue;
				if (removed != null) {
					boolean ours = false;
					for (Bundle gone : removed) {
						if (gone.getBundleId() == bundle.getBundleId()) {
							ours = true;
							break;
						}
					}
					if (ours)
						continue;
				}
				return true;
			}
		}
		return false;
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

	/**
	 * Install or stage every bundle of an extension.
	 * @param metadata extension metadata
	 * @param statusCallback progress sink, may be null
	 * @param createdOut receives every bundle this call installed, so a caller can roll back only
	 *        its own work when a later step throws
	 * @return which bundles are waiting for a restart
	 * @throws Exception when the download or the install fails
	 */
	public InstallResult installExtension(ExtensionMetadata metadata, Callback<String> statusCallback,
			List<Bundle> createdOut) throws Exception {
		List<String> deployedBundles = new ArrayList<>();
		// Bundles whose install succeeded but whose dependents still hold their old wiring.
		List<String> partiallyWired = new ArrayList<>();

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

				// Routing order matters:
				//   1. dynamicUpdate - the bundle is running; safe only when its closure holds
				//      nothing but itself, otherwise the refresh would restart the web stack.
				//   2. dynamicInstall - the bundle is absent and unknown to bundles.info. Installing
				//      adds wiring and never disturbs existing wiring, so no refresh and no closure
				//      check apply: a first install takes effect immediately.
				//   3. deployToPlugins - the fallback for an unsafe update, requiring a restart.
				BundleInstallResult result = dynamicUpdate(symbolicName, tempZip);
				if (result == null)
					result = dynamicInstall(id, symbolicName, tempZip);
				if (result == null)
					result = deployToPlugins(id, symbolicName, tempZip);

				if (result.isDeployed()) {
					deployedBundles.add(symbolicName);
					if (statusCallback != null)
						// Plain text rather than a Msg lookup: this key has no AD_Message row, and a
						// missing key makes Msg return the key itself, which is what showed up as
						// "ExtensionBundleStaged" in the process log.
						statusCallback.onCallback("Deployed " + symbolicName
								+ " to plugins/; it will load at the next server start.");
				} else if (result.getBundle() != null) {
					// Installed or updated in place and already serving.
					if (result.isCreated() && createdOut != null)
						createdOut.add(result.getBundle());
					if (result.isDependentsAwaitRestart())
						partiallyWired.add(symbolicName);
					if (statusCallback != null) {
						if (result.isDependentsAwaitRestart())
							statusCallback.onCallback("Installed " + symbolicName + " "
									+ result.getBundle().getVersion()
									+ "; running now, but dependents need a restart to see it.");
						else
							statusCallback.onCallback("Installed " + symbolicName + " "
									+ result.getBundle().getVersion() + "; it is running now.");
					}
				}
			} finally {
				tempZip.delete();
			}
		}

		// Record the outcome. An in-place install is live already, so the row can be settled with
		// the new version. A file deployment is on disk but not loaded, which the marker records.
		if (deployedBundles.isEmpty()) {
			handleInstallationSuccess(metadata);
			if (!partiallyWired.isEmpty())
				log.warning("Extension " + metadata.getId() + " is running, but these bundles have "
						+ "dependents that keep their previous wiring until a restart: " + partiallyWired);
		} else {
			handleInstallationDeployed(metadata, deployedBundles);
		}
		return new InstallResult(deployedBundles, partiallyWired);
	}

	/**
	 * Record that a newer revision is deployed on disk and awaits a restart.
	 * <p>
	 * The row is set to {@code Installed} with the new version because the deployment is complete:
	 * the files and the {@code bundles.info} line are in place, so nothing else can fail. A marker
	 * records that the running process has not loaded it yet, which is what the UI and
	 * {@code reconcileStagedInstalls} use to say "restart pending".
	 * @param extension metadata of the deployed extension
	 * @param deployedBundles symbolic names that were written to disk
	 */
	public void handleInstallationDeployed(ExtensionMetadata extension, List<String> deployedBundles) {
		String extensionId = extension.getId();
		if (extensionId == null)
			return;
		MExtension mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
				.setParameters(extensionId)
				.first();
		if (mExtension == null)
			return;
		mExtension.setExtensionMetadata(extension.toString());
		if (extension.hasVersion())
			mExtension.setExtensionVersion(extension.getVersion());
		mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Installed);
		mExtension.saveEx();
		log.warning("Extension " + extensionId + " deployed to plugins/, restart required: "
				+ String.join(", ", deployedBundles));
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
