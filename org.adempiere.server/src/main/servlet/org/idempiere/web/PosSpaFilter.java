/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 **********************************************************************/

package org.idempiere.web;

import java.io.IOException;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

/**
 * SPA fallback for the React POS app deployed under /pos/.
 * Forwards client-side routes (e.g. /pos/dashboard) to /pos/index.html.
 */
public class PosSpaFilter implements Filter
{
	private static final String POS_INDEX = "/pos/index.html";
	private static final Set<String> STATIC_EXTENSIONS = Set.of(
		"js", "css", "map", "ico", "png", "jpg", "jpeg", "gif", "svg",
		"json", "txt", "woff", "woff2", "ttf", "eot", "webp", "html");

	@Override
	public void init(FilterConfig config) throws ServletException
	{
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
		throws IOException, ServletException
	{
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		if (!"GET".equalsIgnoreCase(httpRequest.getMethod()))
		{
			chain.doFilter(request, response);
			return;
		}

		String uri = httpRequest.getRequestURI();
		String contextPath = httpRequest.getContextPath();
		if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
			uri = uri.substring(contextPath.length());

		if (isStaticAsset(uri))
		{
			chain.doFilter(request, response);
			return;
		}

		RequestDispatcher dispatcher = request.getRequestDispatcher(POS_INDEX);
		dispatcher.forward(request, response);
	}

	private static boolean isStaticAsset(String uri)
	{
		if (uri == null || uri.isEmpty())
			return false;

		if (POS_INDEX.equals(uri))
			return true;

		if (uri.contains("/static/"))
			return true;

		int slash = uri.lastIndexOf('/');
		int dot = uri.lastIndexOf('.');
		if (dot > slash && dot < uri.length() - 1)
		{
			String ext = uri.substring(dot + 1).toLowerCase();
			return STATIC_EXTENSIONS.contains(ext);
		}

		return false;
	}

	@Override
	public void destroy()
	{
	}
}
