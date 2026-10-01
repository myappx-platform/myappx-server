# MyAppx Desktop 预认证

`/webui/` 可以要求请求带共享密钥头 `X-MyAppx-Preauth-Secret`。MyAppx Desktop 在访问已配置服务器时注入这个头。没有头或密钥不对时，过滤器返回 HTTP 403，响应头 `X-Reject-Reason: pre-auth`，页面指向服务器根路径的下载页 `/desktop/`。

过滤器始终挂在 WebUI 上。开关默认关闭，关闭时请求原样继续。Monitor、Web Services、OSGi console 是别的 Web context，不经过这个过滤器。

## 查找顺序

每一项都按这个顺序取第一个非空值：

1. JVM 系统属性 `myappx.desktop.preauth.*`
2. 环境变量 `MYAPPX_DESKTOP_PREAUTH_*`
3. `AD_SysConfig`（只读系统客户 `AD_Client_ID = 0`、`AD_Org_ID = 0`）

`MyAppxDesktopPreAuthConfig` 调用的是 `MSysConfig.getBooleanValue(name, false)` 和 `getValue(name, …)` 的系统重载，不读当前登录客户。客户级同名配置不会生效。配置级别是 System（`S`）。

开关接受 `Y` 或 `true`（忽略大小写）。系统属性或环境变量有值时，不再读 SysConfig。

| 项 | JVM | 环境变量 | SysConfig | 缺省 |
| --- | --- | --- | --- | --- |
| 开关 | `myappx.desktop.preauth.enabled` | `MYAPPX_DESKTOP_PREAUTH_ENABLED` | `MYAPPX_DESKTOP_PREAUTH_ENABLED` | `N`。Java 缺省同样是关闭 |
| 密钥 | `myappx.desktop.preauth.secret` | `MYAPPX_DESKTOP_PREAUTH_SECRET` | `MYAPPX_DESKTOP_PREAUTH_SECRET` | 开关打开且三处都没配时，用内置 `MyAppxDesktop-DefaultPreAuth-v1` |
| 放行路径 | `myappx.desktop.preauth.bypass.paths` | `MYAPPX_DESKTOP_PREAUTH_BYPASS_PATHS` | `MYAPPX_DESKTOP_PREAUTH_BYPASS_PATHS` | `/oauth2/callback,/idempiereMonitor,/ADInterface,/osgi,/desktop` |

迁移脚本只插入开关。密钥和放行路径没有种子行，需要时用系统属性、环境变量，或在系统配置里手工新增。

SysConfig 走 `MSysConfig` 缓存。改库里的值之后要重置缓存。系统属性和环境变量每次请求直接读，不经过这层缓存；改它们需要重启进程才能让 JVM 看到新值。

## 请求处理

`MyAppxDesktopPreAuthFilter` 映射在 `org.adempiere.ui.zk` 的 `WEB-INF/web.xml`，`url-pattern` 为 `/*`，排在 Session Fingerprint 和 SSO 过滤器之前。该 bundle 的 `Web-ContextPath` 是 `webui`。

处理顺序：

1. 开关关闭，或请求不是 HTTP：交给后续过滤器。
2. 去掉 context path（`/webui`）后的路径，与放行前缀比较。相等，或以 `前缀/` 开头，则放行。比较前去掉首尾空白和结尾斜杠，并转成小写。
3. 用 `MessageDigest.isEqual` 比较请求头与配置密钥（两边先 `trim`）。密钥未配置且开关关闭时，比较失败。
4. 不匹配则 `403`，`X-Reject-Reason: pre-auth`，HTML 正文链到 `/desktop/`。链接是站点根路径，不带 `/webui`。

默认放行列表里的 `/idempiereMonitor`、`/ADInterface`、`/osgi` 对应的是其他 context，本来就不会进这个过滤器。列表里对 WebUI 真正起作用的是去掉 context 之后的路径，例如 `/webui/oauth2/callback` 变成 `/oauth2/callback`。

内置默认密钥只在开关已经打开、并且系统属性、环境变量、SysConfig 都没有密钥时使用。第一次使用时打一条 info 日志。

## Java

`org.compiere.model.MSysConfig` 增加三个常量：`MYAPPX_DESKTOP_PREAUTH_ENABLED`、`MYAPPX_DESKTOP_PREAUTH_SECRET`、`MYAPPX_DESKTOP_PREAUTH_BYPASS_PATHS`。

`org.adempiere.webui.preauth`：

- `MyAppxDesktopPreAuthConfig` 按上面的顺序读开关、密钥和放行路径，并做路径规范化与常量时间比较。
- `MyAppxDesktopPreAuthFilter` 执行放行或 403。

## 迁移脚本

`migration/local_sql/postgresql/209901010000_Z004_MyAppxDesktopPreAuth.sql`

这条插在系统客户（`AD_Client_ID = 0`），配置级别为 System（`S`）：

| Name | 默认值 | 说明 |
| --- | --- | --- |
| `MYAPPX_DESKTOP_PREAUTH_ENABLED` | `N` | 是否要求 WebUI 请求携带 Desktop 预认证头 |

已有同名系统配置则跳过，不覆盖已改过的值。Docker 与便携版仓库没有设置对应的环境变量或 JVM 属性。

## 使用

保持默认 `N` 时，浏览器可以直接打开 `/webui/`。

要限制为 Desktop 访问：把系统配置 `MYAPPX_DESKTOP_PREAUTH_ENABLED` 设为 `Y`，重置缓存。未另配密钥时，服务端和本机 Desktop 都使用内置默认密钥。对外或非本机地址应设置 `MYAPPX_DESKTOP_PREAUTH_SECRET`（或同名环境变量、`myappx.desktop.preauth.secret`），并在 Desktop 连接该服务器时输入同一密钥。

放行 WebUI 下的额外路径时，设置 `MYAPPX_DESKTOP_PREAUTH_BYPASS_PATHS`，逗号分隔，路径相对于 `/webui`。

## 排错

- 浏览器仍能打开 WebUI：确认生效的是系统行 `N` 之外的值。系统属性或环境变量优先于 SysConfig。改 SysConfig 后重置缓存。
- Desktop 本机可以进、浏览器 403：开关已打开，本机客户端带了内置密钥。这是预期行为。
- Desktop 远程服务器 403 后反复要密钥：服务端密钥与客户端保存的不一致。把 SysConfig 或环境变量改成与 Desktop 里输入的相同值，或在弹窗里重输。改的是 SysConfig 时重置缓存。
- 改了密钥仍用内置值：三处都空时才会用 `MyAppxDesktop-DefaultPreAuth-v1`。检查系统属性、环境变量和系统级 SysConfig 是否真有非空值。
- `/idempiereMonitor` 等被拦：它们不在 `webui` context 里，不应被这个过滤器处理。若被拦，先看是不是请求打进了 `/webui/` 下的同名路径。
- 403 页面的下载链接打不开：链接是站点根上的 `/desktop/`，不是 `/webui/desktop/`。
