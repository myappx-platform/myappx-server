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
 * Missing or invalid secrets receive HTTP 403 with {@code X-Reject-Reason: pre-auth}
 * and an HTML page linking to the MyAppx Desktop download page ({@code /desktop/}).
 */
public class MyAppxDesktopPreAuthFilter implements Filter
{
	public static final String HEADER_PREAUTH_SECRET = "X-MyAppx-Preauth-Secret";
	public static final String HEADER_REJECT_REASON = "X-Reject-Reason";
	public static final String REJECT_REASON_PREAUTH = "pre-auth";
	private static final String DESKTOP_PAGE_PATH = "/desktop/";

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

		String desktopPageUrl = buildDesktopPageUrl();

		response.resetBuffer();
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader(HEADER_REJECT_REASON, REJECT_REASON_PREAUTH);
		response.setContentType("text/html;charset=UTF-8");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(buildRejectHtml(desktopPageUrl));
	}

	static String buildDesktopPageUrl()
	{
		// Desktop download portal is served at server root (/desktop/), not under the webui context.
		return DESKTOP_PAGE_PATH;
	}

	private static String buildRejectHtml(String desktopPageUrl)
	{
		return String.format("""
			<!DOCTYPE html>
			<html lang="zh-CN">
			<head>
			    <meta charset="UTF-8">
			    <meta name="viewport" content="width=device-width, initial-scale=1">
			    <title>需要 My Desktop 客户端</title>
			    <style>
			        :root {
			            --bg: #f4f6f8;
			            --surface: #ffffff;
			            --text: #1a1d21;
			            --muted: #5e6672;
			            --accent: #1c58d9;
			            --accent-hover: #1548b8;
			            --accent-soft: #e8efff;
			            --border: #dfe3e8;
			            --warning: #c87a00;
			            --radius: 10px;
			            --shadow: 0 1px 3px rgba(16, 24, 40, 0.08);
			        }
			        * { box-sizing: border-box; }
			        body {
			            margin: 0;
			            font-family: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
			            background: var(--bg);
			            color: var(--text);
			            line-height: 1.6;
			            min-height: 100vh;
			            display: flex;
			            align-items: center;
			            justify-content: center;
			            padding: 24px 16px;
			        }
			        .page {
			            width: 100%%;
			            max-width: 640px;
			        }
			        header {
			            background: linear-gradient(135deg, #1c58d9 0%%, #0f3d99 100%%);
			            color: #fff;
			            border-radius: var(--radius);
			            padding: 28px 32px;
			            box-shadow: var(--shadow);
			            margin-bottom: 20px;
			            text-align: center;
			        }
			        header .icon {
			            font-size: 2.4rem;
			            line-height: 1;
			            margin-bottom: 12px;
			            opacity: 0.95;
			        }
			        header h1 {
			            margin: 0 0 8px;
			            font-size: 1.55rem;
			            font-weight: 600;
			        }
			        header .lead {
			            margin: 0;
			            opacity: 0.92;
			            font-size: 0.96rem;
			        }
			        .badge {
			            display: inline-block;
			            margin-top: 14px;
			            padding: 4px 12px;
			            border-radius: 999px;
			            background: rgba(255, 255, 255, 0.18);
			            font-size: 0.82rem;
			            font-weight: 600;
			            letter-spacing: 0.02em;
			        }
			        section {
			            background: var(--surface);
			            border: 1px solid var(--border);
			            border-radius: var(--radius);
			            padding: 24px 28px;
			            margin-bottom: 16px;
			            box-shadow: var(--shadow);
			        }
			        section p {
			            margin: 0;
			            color: var(--muted);
			            font-size: 0.94rem;
			        }
			        section p + p { margin-top: 10px; }
			        .download-panel {
			            background: var(--surface);
			            border: 1px solid var(--border);
			            border-radius: var(--radius);
			            padding: 24px 28px;
			            margin-bottom: 16px;
			            box-shadow: var(--shadow);
			            text-align: center;
			        }
			        .download-panel h2 {
			            margin: 0 0 6px;
			            font-size: 1.1rem;
			        }
			        .download-panel .desc {
			            margin: 0 0 18px;
			            color: var(--muted);
			            font-size: 0.92rem;
			        }
			        .btn {
			            display: inline-block;
			            padding: 12px 24px;
			            border-radius: 8px;
			            font-size: 0.98rem;
			            font-weight: 600;
			            text-decoration: none;
			            transition: background 0.15s;
			        }
			        .btn-primary {
			            background: var(--accent);
			            color: #fff;
			        }
			        .btn-primary:hover { background: var(--accent-hover); }
			        .note {
			            background: var(--accent-soft);
			            border-left: 4px solid var(--accent);
			            padding: 12px 14px;
			            border-radius: 0 8px 8px 0;
			            color: #243b6b;
			            font-size: 0.92rem;
			        }
			        .note.warn {
			            background: #fff8eb;
			            border-left-color: var(--warning);
			            color: #6b4a00;
			        }
			        footer {
			            text-align: center;
			            color: var(--muted);
			            font-size: 0.85rem;
			        }
			        footer a { color: var(--accent); text-decoration: none; }
			        footer a:hover { text-decoration: underline; }
			    </style>
			</head>
			<body>
			    <div class="page">
			        <header>
			            <div class="icon" aria-hidden="true">&#128274;</div>
			            <h1>需要 My Desktop 客户端</h1>
			            <p class="lead">本系统已启用 Desktop 预认证，请使用 My Desktop 桌面客户端访问。</p>
			            <span class="badge">HTTP 403 · pre-auth</span>
			        </header>

			        <section>
			            <p>当前请求未通过 Desktop 预认证校验。直接使用浏览器访问 Web UI 已被限制，以保护内网工作区安全。</p>
			            <p>请安装并启动 <strong>My Desktop</strong> 客户端，客户端会自动注入预认证头并连接 MyAppx Server。</p>
			        </section>

			        <div class="download-panel">
			            <h2>获取 My Desktop</h2>
			            <p class="desc">下载 Windows 安装包，安装后使用客户端连接本服务器。</p>
			            <a class="btn btn-primary" href="%s">下载 My Desktop</a>
			        </div>

			        <section>
			            <div class="note warn">
			                <strong>提示：</strong>若已安装客户端，请从桌面或开始菜单启动 My Desktop，不要在浏览器中直接打开 Web UI 地址。
			            </div>
			        </section>

			        <footer>
			            <a href="%s">查看下载页与文档</a>
			        </footer>
			    </div>
			</body>
			</html>
			""", desktopPageUrl, desktopPageUrl);
	}

	@Override
	public void destroy()
	{
	}
}
