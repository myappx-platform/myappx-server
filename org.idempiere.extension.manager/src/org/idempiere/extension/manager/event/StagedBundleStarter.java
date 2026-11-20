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
 * This program is distributed in the hope that it will be useful,      *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of       *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the         *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 **********************************************************************/
package org.idempiere.extension.manager.event;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.idempiere.extension.manager.form.ExtensionBrowserService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Applies extension revisions that were staged for a restart.
 * <p>
 * A staged revision exists because applying it in the running server would have restarted
 * bundles the application cannot do without. At startup that cost disappears: Jetty has not
 * begun serving yet, so refreshing the bundle only means it is resolved again before the
 * application starts.
 * <p>
 * This is why the apply waits for the framework start level and not for the application: doing
 * it after the server is up would re-introduce exactly the outage the staging was meant to avoid.
 * The database is not touched here - {@code reconcileStagedInstalls} settles the
 * {@code MExtension} row when the form is opened.
 */
public final class StagedBundleStarter {

	private static final Logger log = Logger.getLogger(StagedBundleStarter.class.getName());

	/** One attempt per JVM; a restart is what retries a failed apply. */
	private static volatile boolean started;

	private StagedBundleStarter() {
	}

	/**
	 * Schedule the staged apply once. Subsequent calls do nothing.
	 * @param context bundle context of the extension manager
	 */
	public static void startWhenReady(BundleContext context) {
		if (context == null)
			return;
		if (started)
			return;
		synchronized (StagedBundleStarter.class) {
			if (started)
				return;
			started = true;
		}
		Thread worker = new Thread(() -> run(context), "myappx-staged-extensions");
		worker.setDaemon(true);
		worker.start();
	}

	private static void run(BundleContext context) {
		try {
			waitForStartLevel(context);
			new ExtensionBrowserService().applyStagedBundles();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.log(Level.WARNING, "Staged extension apply interrupted", e);
		} catch (Exception e) {
			// Never fail a start over a parked revision; the next start tries again.
			log.log(Level.SEVERE, "Staged extension apply failed", e);
		}
		// No startup closure analysis on purpose. Measured at this point the closure under-reports:
		// for a web bundle the WAB has not been deployed yet, so Jetty's DeploymentManager has not
		// wired it to ui.zk / zk / org.adempiere.server and the closure came back as size=1 while
		// the real one at update time was size=119. Reporting that number invited exactly the wrong
		// conclusion - enabling a hot update that stopped the whole application. The closure is now
		// measured only where it matters: immediately before an update, in refreshBlockers.
	}

	/**
	 * Wait until the framework reached its configured start level.
	 * <p>
	 * Refreshing a bundle before that races with bundles that are still being started, and after
	 * the application is up would restart Jetty while it serves requests. The start level event is
	 * the window in between.
	 * @param context bundle context used to reach the framework
	 * @throws InterruptedException when interrupted while waiting
	 */
	private static void waitForStartLevel(BundleContext context) throws InterruptedException {
		Bundle systemBundle = context.getBundle(0);
		FrameworkWiring wiring = systemBundle == null ? null : systemBundle.adapt(FrameworkWiring.class);
		if (wiring == null) {
			log.fine("FrameworkWiring unavailable; applying staged extensions without a start level wait");
			return;
		}
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		FrameworkListener listener = event -> {
			if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED)
				latch.countDown();
		};
		context.addFrameworkListener(listener);
		try {
			if (latch.await(5, java.util.concurrent.TimeUnit.MINUTES)) {
				// Let the bundles the framework just activated finish their own start work.
				Thread.sleep(1000L);
			} else {
				log.warning("Framework start level did not settle in 5 minutes; applying staged extensions anyway");
			}
		} finally {
			context.removeFrameworkListener(listener);
		}
	}
}
