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
			if (!waitForStartLevel(context)) {
				// A framework that never settles is not a state to make changes in. The staged
				// files stay on disk and the next start retries.
				log.warning("Framework start level did not settle; staged bundles were NOT deployed, "
						+ "they will be retried on the next start");
				return;
			}
			new ExtensionBrowserService().applyStagedBundles();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.log(Level.WARNING, "Staged bundle deployment interrupted", e);
		} catch (Exception e) {
			// Never fail a start over a parked revision; the next start tries again.
			log.log(Level.SEVERE, "Staged bundle deployment failed", e);
		}
	}

	/**
	 * Wait until the framework reached its configured start level.
	 * <p>
	 * Started bundles have finished activating by then, so the {@code plugins/} directory and
	 * {@code bundles.info} can be written without racing anything. The deployed revision is not
	 * loaded until the next start regardless, so this is about a clean filesystem state, not about
	 * beating Jetty.
	 * @param context bundle context used to reach the framework
	 * @return true when the start level settled, false on timeout
	 * @throws InterruptedException when interrupted while waiting
	 */
	private static boolean waitForStartLevel(BundleContext context) throws InterruptedException {
		Bundle systemBundle = context.getBundle(0);
		FrameworkWiring wiring = systemBundle == null ? null : systemBundle.adapt(FrameworkWiring.class);
		if (wiring == null) {
			log.fine("FrameworkWiring unavailable; deploying staged bundles without a start level wait");
			return true;
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
				return true;
			}
			return false;
		} finally {
			context.removeFrameworkListener(listener);
		}
	}
}
