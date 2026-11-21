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
	 * Wait until a package refresh finishes. A singleton stays selected until this returns.
	 * @param bundles bundles to refresh; empty does nothing
	 * @param symbolicName used in timeout and error text
	 * @throws Exception refresh timed out or the framework reported an error
	 */
	private void refreshBundlesAndWait(Collection<Bundle> bundles, String symbolicName) throws Exception {
		refreshBundlesAndWait(bundles, symbolicName, false);
	}

	/**
	 * Refresh bundles and wait for completion.
	 * @param bundles bundles to refresh; empty does nothing
	 * @param symbolicName used in timeout and error text
	 * @param atStartup true when called during framework start, where stopping a server bundle is
	 *        harmless because nothing is serving yet. False for an in-session update, where the
	 *        same refresh would take the application down.
	 * @throws Exception refresh timed out, was refused, or the framework reported an error
	 */
	private void refreshBundlesAndWait(Collection<Bundle> bundles, String symbolicName, boolean atStartup)
			throws Exception {
		if (bundles == null || bundles.isEmpty())
			return;
		if (ExtensionManagerActivator.context == null) {
			throw new AdempiereException(Msg.getMsg(Env.getCtx(), "BundleContextNotFound"));
		}
		FrameworkWiring wiring = ExtensionManagerActivator.context.getBundle(0).adapt(FrameworkWiring.class);
		if (wiring == null) {
			throw new AdempiereException("FrameworkWiring is not available");
		}
		// Last line of defence for an in-session update. refreshBundles always expands the seed to
		// its dependents, so a caller's own check can be defeated by a manifest it did not account
		// for - which is how a WAB refresh once took the whole application down. At startup the
		// same expansion is exactly what is wanted, so the check is skipped there.
		if (atStartup) {
			if (log.isLoggable(Level.INFO)) {
				Collection<Bundle> closure = wiring.getDependencyClosure(bundles);
				log.info("STARTUP-REFRESH " + symbolicName + " closure=" + (closure == null ? 0 : closure.size())
						+ " (allowed: nothing is serving yet)");
			}
		} else {
			assertClosureIsRefreshable(wiring, bundles, symbolicName);
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
	 * Result of installing one bundle.
	 * <p>
	 * {@code created} is true only when this attempt is the one that put the bundle there, so a
	 * rollback may uninstall it. A bundle that was already installed belongs to the operator and
	 * must survive a failed install untouched - uninstalling it takes a working extension down.
	 * <p>
	 * {@code staged} means the new revision was downloaded but not applied because applying it
	 * needs a framework refresh that would restart a server bundle. The jar is parked on disk and
	 * applied on the next start.
	 */
	public static final class BundleInstallResult {
		private final Bundle bundle;
		private final boolean created;
		private final boolean staged;

		BundleInstallResult(Bundle bundle, boolean created, boolean staged) {
			this.bundle = bundle;
			this.created = created;
			this.staged = staged;
		}

		public Bundle getBundle() {
			return bundle;
		}

		public boolean isCreated() {
			return created;
		}

		public boolean isStaged() {
			return staged;
		}
	}

	/**
	 * Outcome of installing a whole extension.
	 */
	public static final class InstallResult {
		private final List<String> stagedBundles;

		InstallResult(List<String> stagedBundles) {
			this.stagedBundles = stagedBundles;
		}

		/**
		 * @return symbolic names whose new revision only takes effect after a restart; empty when
		 *         everything was applied immediately
		 */
		public List<String> getStagedBundles() {
			return stagedBundles;
		}

		public boolean isStaged() {
			return !stagedBundles.isEmpty();
		}
	}

	/**
	 * Update the current bundle in place, or install one new bundle after pending removals are gone.
	 * A second install while the old singleton revision is still selected cannot resolve.
	 */
	private BundleInstallResult replaceOrInstallBundle(String metadataExtensionId, String symbolicName, String downloadUrl, File jarFile) throws Exception {
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
				return new BundleInstallResult(ExtensionManagerActivator.context.installBundle(downloadUrl, fis), true, false);
			}
		}

		Bundle target = preferLiveBundle(live);
		List<Bundle> extras = new ArrayList<>();
		for (Bundle bundle : live) {
			if (bundle.getBundleId() != target.getBundleId())
				extras.add(bundle);
		}
		if (!extras.isEmpty()) {
			// Duplicate revisions of a singleton cannot both resolve, and dropping the extras needs
			// a framework refresh that would restart the server. Park the jar instead of failing:
			// the next start resolves to the one revision that is left.
			stageBundle(metadataExtensionId, symbolicName, jarFile);
			log.warning("Staged " + symbolicName + " for the next start; " + extras.size()
					+ " additional revision(s) must be retired first");
			return new BundleInstallResult(target, false, true);
		}

		// Bundle.update(InputStream) replaces this bundle's current revision, and the previous
		// revision stays selected until a refresh retires it - so a refresh is unavoidable. But a
		// refresh also restarts every bundle that depends on this one. Check the blast radius
		// BEFORE touching the bundle.
		List<String> blockers = refreshBlockers(target, symbolicName);
		if (!blockers.isEmpty()) {
			// Touching the bundle at all would stop it: Bundle.update() stops an active bundle, and
			// the refresh that makes the new revision current would also restart the server. So the
			// running revision is left completely alone and the jar is parked. It is applied during
			// the next start, when a restart is expected anyway.
			stageBundle(metadataExtensionId, symbolicName, jarFile);
			log.warning("Staged " + symbolicName + " for the next start; refreshing it would restart "
					+ String.join(", ", blockers));
			return new BundleInstallResult(target, false, true);
		}

		// Bundle.update() stops an active bundle and the following refresh retires the old
		// revision. Both are unavoidable, so the bundle is brought back up explicitly afterwards:
		// a refresh restores ACTIVE state only for bundles it could re-resolve, and relying on
		// that leaves the extension silently dead when it cannot.
		int stateBefore = target.getState();
		boolean wasActive = stateBefore == Bundle.ACTIVE || stateBefore == Bundle.STARTING;
		if (wasActive)
			target.stop();
		try (java.io.FileInputStream fis = new java.io.FileInputStream(jarFile)) {
			target.update(fis);
		}
		refreshBundlesAndWait(Collections.singletonList(target), symbolicName);
		for (Bundle bundle : bundlesNamed(symbolicName)) {
			if (bundle.getBundleId() != target.getBundleId() && bundle.getState() != Bundle.UNINSTALLED)
				throw new AdempiereException("Bundle " + symbolicName + " is still selected after refresh");
		}
		if (wasActive && target.getState() != Bundle.ACTIVE) {
			// Restart the new revision, which is what makes the update take effect in this session.
			target.start();
			log.warning(symbolicName + " refreshed and restarted at " + target.getVersion()
					+ " (state=" + stateName(target.getState()) + ")");
		}
		return new BundleInstallResult(target, false, false);
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
	 * Extensions whose refresh was measured to touch nothing but themselves.
	 * <p>
	 * Deliberately empty, and this must not be filled in on the strength of a test where the
	 * bundle was placed in {@code plugins/}. Such a bundle resolves its {@code Require-Bundle}
	 * headers without the web container, and its measured closure came out as {@code size=1}.
	 * <p>
	 * A bundle installed through this form is a different animal: it is a WAB, and Jetty's
	 * {@code DeploymentManager} plus the OSGi HTTP service wire it to {@code ui.zk}, {@code zk},
	 * {@code org.adempiere.server} and the rest. Measured on a real form-installed extension the
	 * closure was {@code size=119} with 7 forbidden bundles, so an in-place refresh stops the
	 * whole application. That is not a hot update, and allowing it here caused exactly that outage.
	 */
	private static final java.util.Set<String> REFRESH_SAFE_EXTENSIONS = java.util.Collections.emptySet();

	/**
	 * Verify the framework's own refresh closure before any bundle is stopped.
	 * <p>
	 * {@code refreshBundles} always expands the seed with everything that depends on it, so a
	 * caller's earlier check can be defeated by wiring it did not model - a WAB is wired through
	 * {@code DeploymentManager} in a way that is invisible to a manifest-only review. Asking the
	 * framework here is the only check that cannot be bypassed.
	 * @param wiring framework wiring used for the refresh
	 * @param bundles seed bundles about to be refreshed
	 * @param symbolicName extension being updated, used in the error
	 * @throws AdempiereException when the closure contains a bundle that cannot restart
	 */
	private void assertClosureIsRefreshable(FrameworkWiring wiring, Collection<Bundle> bundles, String symbolicName)
			throws AdempiereException {
		Collection<Bundle> closure = wiring.getDependencyClosure(bundles);
		if (closure == null || closure.isEmpty())
			return;
		List<String> blockers = new ArrayList<>();
		for (Bundle dependent : closure) {
			String name = dependent.getSymbolicName();
			if (name != null && isRefreshForbidden(name) && !name.equals(symbolicName))
				blockers.add(name);
		}
		if (!blockers.isEmpty()) {
			// Refused before anything was stopped, so the running revision keeps serving.
			log.warning("REFUSED refresh of " + symbolicName + ": closure of " + closure.size()
					+ " would restart " + String.join(", ", blockers));
			throw new AdempiereException("Refusing to refresh " + symbolicName + ": it would also restart "
					+ String.join(", ", blockers)
					+ ". Update it at the next restart instead.");
		}
	}

	/**
	 * Bundles whose refresh would take the server down.
	 * @param target bundle about to be refreshed
	 * @param symbolicName extension being updated, skipped in the result
	 * @return symbolic names of bundles that cannot restart; empty when a refresh is harmless
	 */
	private List<String> refreshBlockers(Bundle target, String symbolicName) throws Exception {
		List<String> blockers = new ArrayList<>();
		// The diagnostic runs first, for every update attempt including the allowed ones: it is the
		// evidence that decides whether an extension can be added to REFRESH_SAFE_EXTENSIONS.
		logRefreshClosure(target, symbolicName);
		if (ExtensionManagerActivator.context == null)
			throw new AdempiereException(Msg.getMsg(Env.getCtx(), "BundleContextNotFound"));
		FrameworkWiring wiring = ExtensionManagerActivator.context.getBundle(0).adapt(FrameworkWiring.class);
		if (wiring == null)
			throw new AdempiereException("FrameworkWiring is not available");

		Collection<Bundle> closure = wiring.getDependencyClosure(Collections.singletonList(target));
		if (closure == null)
			return blockers;
		for (Bundle dependent : closure) {
			String name = dependent.getSymbolicName();
			if (name == null || name.equals(symbolicName))
				continue;
			if (isRefreshForbidden(name))
				blockers.add(name);
		}
		return blockers;
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
			for (Bundle bundle : bundlesNamed(symbolicName)) {
				String header = bundle.getHeaders().get("Export-Package");
				if (header == null || header.isBlank())
					continue;
				for (String clause : splitHeaderClauses(header)) {
					String pkg = clause.split(";")[0].trim();
					if (!pkg.isEmpty() && !packages.contains(pkg))
						packages.add(pkg);
				}
			}
		} catch (Exception e) {
			log.fine("Cannot read exported packages of " + symbolicName + ": " + e.getMessage());
		}
		return packages;
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
	 * Log the refresh cost of every dynamically installed plugin extension.
	 * <p>
	 * Runs once per JVM after the framework settles. An operator does not have to open the form or
	 * attempt an update to learn whether hot update is possible: the closure of each extension is
	 * written to the log at WARNING, with the wire that produces every forbidden entry.
	 */
	public void logRefreshClosuresForInstalledExtensions() {
		if (ExtensionManagerActivator.context == null)
			return;
		try {
			java.util.Set<String> analysed = new java.util.HashSet<>();
			List<MExtension> installed = new Query(Env.getCtx(), MExtension.Table_Name,
					"ExtensionState IN (?,?,?)", null)
					.setParameters(MExtension.EXTENSIONSTATE_Installed,
							MExtension.EXTENSIONSTATE_Disabled,
							MExtension.EXTENSIONSTATE_Installing)
					.setOnlyActiveRecords(true)
					.list();
			for (MExtension extension : installed)
				analyse(extension.getExtensionId(), extension.getExtensionState(), analysed);

			// Extension rows can be stale: a row in Error still has a bundle that an update would
			// have to refresh. Analyse every extension bundle no row claimed, so the picture does
			// not depend on the dictionary being tidy.
			for (Bundle bundle : ExtensionManagerActivator.context.getBundles()) {
				String name = bundle.getSymbolicName();
				if (name == null || analysed.contains(name) || bundle.getState() == Bundle.UNINSTALLED)
					continue;
				if (!name.startsWith("org.idempiere.myappx.") && !name.startsWith("com.trekglobal.")
						&& !name.startsWith("org.idempiere.mcp."))
					continue;
				analyse(name, "no-row", analysed);
			}
			if (analysed.isEmpty())
				log.warning("REFRESH-CLOSURE no extension bundles found to analyse");
		} catch (Exception e) {
			log.log(Level.WARNING, "REFRESH-CLOSURE analysis failed", e);
		}
	}

	/**
	 * Analyse one bundle and record it as done.
	 * @param extensionId symbolic name to analyse
	 * @param rowState extension row state, for the log only
	 * @param analysed names already handled in this pass
	 * @return true when the bundle was found and analysed
	 */
	private boolean analyse(String extensionId, String rowState, java.util.Set<String> analysed) {
		if (extensionId == null || !analysed.add(extensionId))
			return false;
		Bundle bundle = null;
		for (Bundle candidate : ExtensionManagerActivator.context.getBundles()) {
			if (extensionId.equals(candidate.getSymbolicName())
					&& candidate.getState() != Bundle.UNINSTALLED) {
				bundle = candidate;
				break;
			}
		}
		if (bundle == null) {
			log.warning("REFRESH-CLOSURE " + extensionId + " has no live bundle (row state=" + rowState + ")");
			return false;
		}
		logRefreshClosure(bundle, extensionId);
		return true;
	}

	/**
	 * Apply staged bundle revisions. Call once during framework start.
	 * <p>
	 * A staged revision is a jar this server accepted but could not apply while it was serving
	 * users. Applying it here is safe for the reason the in-session path is not: at this point the
	 * application has not begun serving requests, so stopping and refreshing the bundle has no user
	 * impact.
	 * <p>
	 * The staged jar is deleted only after the framework accepted the revision, and the marker is
	 * kept until the extension row is settled. That way a failed apply is retried on the next start
	 * instead of silently losing the update, and a successful one can still be attributed to its
	 * extension.
	 */
	public void applyStagedBundles() {
		if (ExtensionManagerActivator.context == null)
			return;
		File dir = stagedBundleDir();
		File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
		if (jars == null)
			return;
		if (jars.length == 0) {
			// Keep the marker-only state visible: a marker with no jar means an earlier start
			// applied the revision and the row still has to be settled.
			List<String> pending = getStagedBundles();
			if (!pending.isEmpty())
				log.warning("STAGED-BUNDLES marker present but jar already consumed: " + pending);
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
				if (applyStagedBundle(symbolicName, jar) == null)
					continue;
				// Delete the jar but keep the marker: the row is still Installing and
				// reconcileStagedInstalls needs to know which extension this revision belongs to.
				if (!jar.delete())
					log.warning("Staged jar " + jar.getName() + " could not be deleted after apply");
				clearAttempts(dir, symbolicName);
				log.warning("Staged update of " + symbolicName + " applied at startup");
			} catch (Exception e) {
				int attempts = recordAttemptFailure(dir, symbolicName);
				if (attempts >= MAX_STAGED_ATTEMPTS) {
					// Stop retrying: a revision that failed this often will not apply on its own.
					// The jar is renamed rather than deleted so the file is still there to inspect.
					if (jar.renameTo(abandoned))
						log.log(Level.SEVERE, "Staged update of " + symbolicName + " failed " + attempts
								+ " times; abandoned as " + abandoned.getName()
								+ ". Investigate the cause, then delete the .failed and .extension files to retry.", e);
					else
						log.log(Level.SEVERE, "Staged update of " + symbolicName + " failed " + attempts
								+ " times and could not be renamed to " + abandoned.getName(), e);
				} else {
					log.log(Level.SEVERE, "Staged update of " + symbolicName + " could not be applied (attempt "
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
	 * Install or update one staged bundle from its parked jar.
	 * @param symbolicName bundle to apply
	 * @param jar staged jar
	 * @return the install result, or null when the jar holds a different bundle
	 * @throws Exception when the framework rejects the revision
	 */
	private BundleInstallResult applyStagedBundle(String symbolicName, File jar) throws Exception {
		List<Bundle> live = new ArrayList<>();
		for (Bundle bundle : bundlesNamed(symbolicName)) {
			if (bundle.getState() != Bundle.UNINSTALLED)
				live.add(bundle);
		}
		if (live.isEmpty()) {
			try (java.io.FileInputStream fis = new java.io.FileInputStream(jar)) {
				// The jar's own file URI is a stable, resolvable location. The framework copies the
				// content into its own storage, so deleting the staged jar afterwards is safe.
				Bundle installed = ExtensionManagerActivator.context.installBundle(jar.toURI().toString(), fis);
				if (installed.getState() != Bundle.ACTIVE)
					installed.start();
				return new BundleInstallResult(installed, true, false);
			}
		}
		Bundle target = preferLiveBundle(live);
		if (target.getState() == Bundle.ACTIVE || target.getState() == Bundle.STARTING)
			target.stop();
		try (java.io.FileInputStream fis = new java.io.FileInputStream(jar)) {
			target.update(fis);
		}
		// Startup mode: the bundle that gets refreshed is the extension itself and the closures it
		// pulls in are being restarted anyway, so the in-session blast-radius check must not apply
		// here. Applying it was what made a staged update fail on every restart - the check saw the
		// same core bundles it had refused in-session and threw before anything was applied.
		refreshBundlesAndWait(Collections.singletonList(target), symbolicName, true);
		if (target.getState() != Bundle.ACTIVE)
			target.start();
		return new BundleInstallResult(target, false, false);
	}

	/**
	 * Directory holding jars whose install waits for the next server start.
	 * <p>
	 * A sibling of {@code plugins/}, so it is never scanned as a bundle directory and never
	 * resolved by the running framework.
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
	 * Park a bundle revision for the next start.
	 * <p>
	 * The running revision is left alone on purpose. {@code Bundle.update()} stops an active
	 * bundle and the following refresh restarts the server, so applying a blocked update in place
	 * is exactly what must not happen. Copying the jar to a directory the server owns keeps this
	 * reversible: nothing in the running framework changes, and the file is applied - or deleted -
	 * during the next start.
	 * @param extensionId extension the bundle belongs to, recorded next to the jar
	 * @param symbolicName bundle being staged, used in the file name
	 * @param jarFile downloaded revision
	 */
	private void stageBundle(String extensionId, String symbolicName, File jarFile) throws Exception {
		File dir = stagedBundleDir();
		if (!dir.exists() && !dir.mkdirs())
			throw new AdempiereException("Cannot create " + dir);
		File target = new File(dir, symbolicName + ".jar");
		File owner = new File(dir, symbolicName + ".extension");
		// The marker is written first. A jar without a marker cannot be attributed to an extension,
		// and the startup apply must not install something it cannot account for.
		if (extensionId != null)
			java.nio.file.Files.writeString(owner.toPath(),
					buildMarker(extensionId, symbolicName, jarFile), StandardCharsets.UTF_8);
		try {
			java.nio.file.Files.copy(jarFile.toPath(), target.toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			if (owner.exists())
				owner.delete();
			throw e;
		}
		if (log.isLoggable(Level.INFO))
			log.info("Staged " + symbolicName + " for the next start: " + target);
	}

	/**
	 * Marker content describing a staged revision.
	 * <p>
	 * JSON rather than a bare extension id: when an update does not apply, the first questions are
	 * which version was replaced, which one was meant to take over, and when it was staged. The
	 * plain id answered none of them.
	 * @param extensionId extension the bundle belongs to
	 * @param symbolicName bundle being staged
	 * @param jarFile downloaded revision
	 * @return single line JSON, escaped for the values that can contain quotes
	 */
	private String buildMarker(String extensionId, String symbolicName, File jarFile) {
		String from = currentBundleVersion(symbolicName);
		String to = bundleVersionFromJar(jarFile);
		return "{\"extensionId\":\"" + extensionId + "\""
				+ ",\"bundle\":\"" + symbolicName + "\""
				+ ",\"from\":\"" + (from == null ? "" : from) + "\""
				+ ",\"to\":\"" + (to == null ? "" : to) + "\""
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
	 * Finish staged updates on the first form open after a restart.
	 * <p>
	 * A staged update leaves the row as {@code Installing} while the old revision keeps serving.
	 * Once the new revision is the one running, the row is promoted to {@code Installed}. The
	 * decision is taken from the running bundles, not from the staged file, because the startup
	 * apply deletes that file as soon as it succeeds - keying off it here would leave the row
	 * {@code Installing} and let the stale-install recovery flag a working extension as Error.
	 */
	public void reconcileStagedInstalls() {
		if (ExtensionManagerActivator.context == null)
			return;
		List<MExtension> pending = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionState=?", null)
				.setParameters(MExtension.EXTENSIONSTATE_Installing)
				.setOnlyActiveRecords(true)
				.list();
		if (pending.isEmpty())
			return;
		for (MExtension extension : pending) {
			try {
				if (!isDeclaredVersionRunning(extension))
					continue;
				List<String> staged = stagedBundlesOf(extension);
				MExtension loaded = new MExtension(Env.getCtx(), extension.getAD_Extension_ID(), null);
				String metadata = loaded.getExtensionMetadata();
				if (metadata != null && !metadata.trim().isEmpty()) {
					ExtensionMetadata parsed = new ExtensionMetadata(JsonParser.parseString(metadata).getAsJsonObject());
					if (parsed.hasVersion())
						loaded.setExtensionVersion(parsed.getVersion());
				}
				loaded.setExtensionState(MExtension.EXTENSIONSTATE_Installed);
				loaded.saveEx();
				for (String symbolicName : staged)
					discardStagedBundle(symbolicName);
				log.info("Staged update of " + loaded.getExtensionId() + " completed: running revision matches the metadata");
			} catch (Exception e) {
				log.log(Level.WARNING, "Failed to reconcile staged update of " + extension.getExtensionId(), e);
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
		List<Bundle> installedBundles = new ArrayList<>();
		List<Bundle> createdBundles = createdOut != null ? createdOut : new ArrayList<>();
		List<String> stagedBundles = new ArrayList<>();
				
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
				BundleInstallResult result = replaceOrInstallBundle(metadata.getId(), symbolicName, downloadUrl, tempZip);
				if (result.isStaged()) {
					stagedBundles.add(symbolicName);
					if (statusCallback != null)
						// Plain text rather than a Msg lookup: this key has no AD_Message row, and a
						// missing key makes Msg return the key itself, which is what showed up as
						// "ExtensionBundleStaged" in the process log.
						statusCallback.onCallback("Staged " + symbolicName
								+ " for the next restart; the running version is untouched.");
				} else {
					installedBundles.add(result.getBundle());
					if (result.isCreated())
						createdBundles.add(result.getBundle());
				}
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
			// The framework can be shutting down here: a refresh that cascaded into a server bundle
			// tears the application down while this loop runs, and starting a bundle then fails
			// inside BaseActivator with a NullPointerException from a null bundle context. Report
			// that as a failed install instead of an unexplained NPE in the process log.
			try {
				bundle.start();
			} catch (org.osgi.framework.BundleException e) {
				throw new AdempiereException("Bundle " + bundle.getSymbolicName()
						+ " was installed but could not be started: " + e.getMessage()
						+ ". If the server is shutting down, restart it to finish the install.", e);
			} catch (IllegalStateException e) {
				throw new AdempiereException("Bundle " + bundle.getSymbolicName()
						+ " was installed but the framework is no longer available: " + e.getMessage()
						+ ". Restart the server to finish the install.", e);
			}
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

		if (stagedBundles.isEmpty()) {
			handleInstallationSuccess(metadata);
		} else {
			// The running revision is untouched and still serving; only the row changes, so the
			// operator can see that a restart is outstanding.
			handleInstallationStaged(metadata, stagedBundles);
		}
		return new InstallResult(stagedBundles);
	}

	/**
	 * Record that a newer revision is parked for the next start.
	 * <p>
	 * The installed version stays as it is on purpose: reporting the staged version here would
	 * make the file look installed while the running server still serves the old one.
	 * @param extension metadata of the staged revision
	 * @param stagedBundles symbolic names that are waiting for a restart
	 */
	public void handleInstallationStaged(ExtensionMetadata extension, List<String> stagedBundles) {
		String extensionId = extension.getId();
		if (extensionId == null)
			return;
		MExtension mExtension = new Query(Env.getCtx(), MExtension.Table_Name, "ExtensionId=?", null)
				.setParameters(extensionId)
				.first();
		if (mExtension == null)
			return;
		mExtension.setExtensionMetadata(extension.toString());
		mExtension.setExtensionState(MExtension.EXTENSIONSTATE_Installing);
		mExtension.saveEx();
		log.warning("Extension " + extensionId + " staged for the next start: " + String.join(", ", stagedBundles));
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
