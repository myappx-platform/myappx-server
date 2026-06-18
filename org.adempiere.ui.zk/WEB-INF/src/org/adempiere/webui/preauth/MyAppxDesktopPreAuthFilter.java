/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 **********************************************************************/

package org.adempiere.webui.preauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.compiere.util.CLogger;

/**
 * Gate for MyAppx Desktop clients using a shared pre-authentication secret header.
 * <p>
 * Requests must include {@code X-MyAppx-Preauth-Secret} matching the configured secret.
 * Missing or invalid secrets receive HTTP 403 with {@code X-Reject-Reason: pre-auth}.
 */
public class MyAppxDesktopPreAuthFilter implements Filter
{
	public static final String HEADER_PREAUTH_SECRET = "X-MyAppx-Preauth-Secret";
	public static final String HEADER_REJECT_REASON = "X-Reject-Reason";
	public static final String REJECT_REASON_PREAUTH = "pre-auth";

	private static final CLogger log = CLogger.getCLogger(MyAppxDesktopPreAuthFilter.class);

	@Override
	public void init(FilterConfig config) throws ServletException
	{
		if (log.isLoggable(Level.INFO))
			log.info("MyAppxDesktopPreAuthFilter initialized");
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
		throws IOException, ServletException
	{
		if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse))
		{
			chain.doFilter(request, response);
			return;
		}

		if (!MyAppxDesktopPreAuthConfig.isEnabled())
		{
			chain.doFilter(request, response);
			return;
		}

		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;

		String requestPath = MyAppxDesktopPreAuthConfig.normalizeRequestPath(
			httpRequest.getRequestURI(),
			httpRequest.getContextPath());

		List<String> bypassPrefixes = MyAppxDesktopPreAuthConfig.getBypassPathPrefixes();
		if (MyAppxDesktopPreAuthConfig.matchesBypassPrefix(requestPath, bypassPrefixes))
		{
			chain.doFilter(request, response);
			return;
		}

		String expectedSecret = MyAppxDesktopPreAuthConfig.getSecret();
		String providedSecret = httpRequest.getHeader(HEADER_PREAUTH_SECRET);

		if (MyAppxDesktopPreAuthConfig.isValidSecret(providedSecret, expectedSecret))
		{
			chain.doFilter(request, response);
			return;
		}

		reject(httpResponse);
	}

	private static void reject(HttpServletResponse response) throws IOException
	{
		if (response.isCommitted())
			return;

		response.resetBuffer();
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader(HEADER_REJECT_REASON, REJECT_REASON_PREAUTH);
		response.setContentType("text/plain;charset=UTF-8");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("MyAppx Desktop authentication required.");
	}

	@Override
	public void destroy()
	{
	}
}
