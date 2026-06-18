/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 **********************************************************************/

package org.adempiere.webui.preauth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.compiere.model.MSysConfig;
import org.compiere.util.CLogger;
import org.compiere.util.Util;

/**
 * Configuration for {@link MyAppxDesktopPreAuthFilter}.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>Java system properties ({@code myappx.desktop.preauth.*})</li>
 *   <li>Environment variables ({@code MYAPPX_DESKTOP_PREAUTH_*})</li>
 *   <li>{@link MSysConfig} (enabled defaults to {@code false})</li>
 *   <li>{@link #DEFAULT_SECRET} when enabled and no secret is configured</li>
 * </ol>
 */
public final class MyAppxDesktopPreAuthConfig
{
	private static final CLogger log = CLogger.getCLogger(MyAppxDesktopPreAuthConfig.class);

	private static final String PROP_ENABLED = "myappx.desktop.preauth.enabled";
	private static final String PROP_SECRET = "myappx.desktop.preauth.secret";
	private static final String PROP_BYPASS_PATHS = "myappx.desktop.preauth.bypass.paths";

	private static final String ENV_ENABLED = "MYAPPX_DESKTOP_PREAUTH_ENABLED";
	private static final String ENV_SECRET = "MYAPPX_DESKTOP_PREAUTH_SECRET";
	private static final String ENV_BYPASS_PATHS = "MYAPPX_DESKTOP_PREAUTH_BYPASS_PATHS";

	private static final String DEFAULT_BYPASS_PATHS =
		"/oauth2/callback,/idempiereMonitor,/ADInterface,/osgi,/desktop";

	/**
	 * Built-in pre-auth secret used when pre-auth is enabled but no secret is configured.
	 * Replace via SysConfig, environment variable, or system property.
	 */
	public static final String DEFAULT_SECRET = "MyAppxDesktop-DefaultPreAuth-v1";

	private static volatile boolean defaultSecretLogged = false;

	private MyAppxDesktopPreAuthConfig()
	{
	}

	public static boolean isEnabled()
	{
		String value = firstNonEmpty(
			System.getProperty(PROP_ENABLED),
			System.getenv(ENV_ENABLED));

		if (!Util.isEmpty(value, true))
			return "Y".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);

		try
		{
			return MSysConfig.getBooleanValue(MSysConfig.MYAPPX_DESKTOP_PREAUTH_ENABLED, false);
		}
		catch (Exception e)
		{
			if (log.isLoggable(java.util.logging.Level.FINE))
				log.fine("Unable to read MYAPPX_DESKTOP_PREAUTH_ENABLED from SysConfig: " + e.getMessage());
			return false;
		}
	}

	public static String getSecret()
	{
		String configured = getConfiguredSecret();
		if (!Util.isEmpty(configured, true))
			return configured.trim();

		if (isEnabled())
		{
			logDefaultSecretOnce();
			return DEFAULT_SECRET;
		}

		return null;
	}

	private static String getConfiguredSecret()
	{
		String value = firstNonEmpty(
			System.getProperty(PROP_SECRET),
			System.getenv(ENV_SECRET));

		if (!Util.isEmpty(value, true))
			return value.trim();

		try
		{
			value = MSysConfig.getValue(MSysConfig.MYAPPX_DESKTOP_PREAUTH_SECRET, (String) null);
			if (!Util.isEmpty(value, true))
				return value.trim();
		}
		catch (Exception e)
		{
			if (log.isLoggable(java.util.logging.Level.FINE))
				log.fine("Unable to read MYAPPX_DESKTOP_PREAUTH_SECRET from SysConfig: " + e.getMessage());
		}

		return null;
	}

	private static void logDefaultSecretOnce()
	{
		if (defaultSecretLogged || !log.isLoggable(java.util.logging.Level.INFO))
			return;

		defaultSecretLogged = true;
		log.info("MyAppx Desktop pre-auth using built-in default secret; configure MYAPPX_DESKTOP_PREAUTH_SECRET to replace it");
	}

	public static List<String> getBypassPathPrefixes()
	{
		String value = firstNonEmpty(
			System.getProperty(PROP_BYPASS_PATHS),
			System.getenv(ENV_BYPASS_PATHS));

		if (Util.isEmpty(value, true))
		{
			try
			{
				value = MSysConfig.getValue(MSysConfig.MYAPPX_DESKTOP_PREAUTH_BYPASS_PATHS, DEFAULT_BYPASS_PATHS);
			}
			catch (Exception e)
			{
				value = DEFAULT_BYPASS_PATHS;
			}
		}

		if (Util.isEmpty(value, true))
			return Collections.emptyList();

		return Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.map(MyAppxDesktopPreAuthConfig::normalizePath)
			.collect(Collectors.toList());
	}

	public static boolean isValidSecret(String providedSecret, String expectedSecret)
	{
		if (Util.isEmpty(expectedSecret, true))
			return false;
		if (providedSecret == null)
			return false;

		byte[] expected = expectedSecret.trim().getBytes(StandardCharsets.UTF_8);
		byte[] provided = providedSecret.trim().getBytes(StandardCharsets.UTF_8);
		return java.security.MessageDigest.isEqual(expected, provided);
	}

	private static String firstNonEmpty(String first, String second)
	{
		if (!Util.isEmpty(first, true))
			return first.trim();
		if (!Util.isEmpty(second, true))
			return second.trim();
		return null;
	}

	static String normalizePath(String path)
	{
		if (Util.isEmpty(path, true))
			return "/";

		String normalized = path.trim();
		if (!normalized.startsWith("/"))
			normalized = "/" + normalized;

		while (normalized.length() > 1 && normalized.endsWith("/"))
			normalized = normalized.substring(0, normalized.length() - 1);

		return normalized.toLowerCase(Locale.ROOT);
	}

	static String normalizeRequestPath(String uri, String contextPath)
	{
		String path = uri;
		if (!Util.isEmpty(contextPath, true) && path.startsWith(contextPath))
			path = path.substring(contextPath.length());

		if (Util.isEmpty(path, true))
			path = "/";

		return normalizePath(path);
	}

	static boolean matchesBypassPrefix(String requestPath, List<String> bypassPrefixes)
	{
		for (String prefix : bypassPrefixes)
		{
			if (requestPath.equals(prefix) || requestPath.startsWith(prefix + "/"))
				return true;
		}
		return false;
	}
}
