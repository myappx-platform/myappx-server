# MyAppx 平台基础设置

新库或重置库上的一次性品牌、界面和地区默认值。`register_migration_script` 登记后，SyncDB 不再执行。

`LastBuildInfo` 和 `APPLICATION_MAIN_VERSION` 在这里先写成 `0.0.0`。同一次启动里排在后面的 `Z999` 会改成脚本里的 `v_build_version`。说明见文末「构建号」。

## 迁移脚本

`migration/local_sql/postgresql/209901010000_Z000_BaseSetup.sql`

系统配置都只处理 `AD_Client_ID = 0`。新增时，种子里已有同名系统行则跳过。更新时覆盖系统行，不改租户级同名配置。

### 系统身份

`AD_System`（`AD_System_ID = 0`）：名称 `MyAppx Platform`，支持邮箱 `support@local.corp`，关闭自动错误报告，`LastBuildInfo = 0.0.0`。

### 新增系统配置

配置级别为 System（`S`）。

| Name | 值 | 说明 |
| --- | --- | --- |
| `APPLICATION_MAIN_VERSION` | `0.0.0` | 关于框版本，随后由 Z999 覆盖 |
| `APPLICATION_IMPLEMENTATION_VENDOR` | `MyAppx Platform` | 实施方名称 |
| `PDF_FONT_DIR` | `data/fonts` | PDF 嵌入字体目录 |
| `STANDARD_REPORT_FOOTER_TRADEMARK_TEXT` | `MyAppx` | 标准报表页脚 |
| `ZK_LOGO_LARGE` | `~./theme/iceblue_c/images/myappx-large-logo.png` | 大标志 |
| `ZK_LOGO_SMALL` | `~./theme/iceblue_c/images/myappx-small-logo.png` | 小标志 |
| `ZK_BROWSER_ICON` | `~./theme/iceblue_c/images/myappx-icon.png` | 浏览器图标 |
| `ZK_BROWSER_TITLE` | `MyAppx ...` | 浏览器标题 |
| `APPLICATION_MAIN_VERSION_SHOWN` | `N` | 登录/关于中隐藏版本 |
| `APPLICATION_IMPLEMENTATION_VENDOR_SHOWN` | `N` | 隐藏实施方 |
| `APPLICATION_DATABASE_VERSION_SHOWN` | `N` | 隐藏数据库版本 |
| `APPLICATION_JVM_VERSION_SHOWN` | `N` | 隐藏 JVM |
| `APPLICATION_OS_INFO_SHOWN` | `N` | 隐藏操作系统 |
| `APPLICATION_HOST_SHOWN` | `N` | 隐藏主机名 |

### 覆盖已有系统配置

| Name | 值 | 说明 |
| --- | --- | --- |
| `USE_EMAIL_FOR_LOGIN` | `N` | 用用户名登录 |
| `ZK_PAGING_SIZE` | `100` | 列表分页 |
| `ZK_PAGING_DETAIL_SIZE` | `100` | 明细分页 |
| `ZK_THEME_USE_FONT_ICON_FOR_IMAGE` | `Y` | 工具栏用字体图标 |
| `ZK_MAX_UPLOAD_SIZE` | `20480` | 上传上限（KB） |
| `ZK_GRID_AFTER_FIND` | `Y` | 查询后显示表格 |
| `ZK_SESSION_TIMEOUT_IN_SECONDS` | `7200` | 会话 2 小时 |
| `LOGIN_SHOW_RESETPASSWORD` | `N` | 登录页不显示重置密码 |
| `START_VALUE_BPLOCATION_NAME` | `3` | 业务伙伴地址名称起始序号 |
| `MESSAGES_AT_TENANT_LEVEL` | `Y` | 消息按租户维护 |
| `2PACK_COMMIT_DDL` | `Y` | 2Pack 建列之间提交 DDL，PostgreSQL 上插件建表需要 |

### 用户

| 对象 | 改动 |
| --- | --- |
| System（`AD_User_ID = 10`） | 密码换成新的 UUID |
| SuperUser（`AD_User_ID = 100`） | 邮箱 `superuser@local.corp`，通知类型 `N`（Notice）。密码不改 |
| GardenWorld（`AD_Client_ID = 11`） | 该客户下全部用户密码换成新的 UUID |

上线前要改掉 SuperUser 邮箱，并给仍需登录的账号重设密码。

### 界面

- 窗口工具栏 Help（`AD_ToolbarButton_ID = 200030`）序号改为 999，排到末尾。
- 仪表板 Donate（`PA_DashboardContent_ID = 200005`）停用。

### 国家、货币、语言

| 主数据 | 保留并启用 | 其余 |
| --- | --- | --- |
| 国家 | CN、US | 停用 |
| 货币 | CNY、USD、EUR | 停用 |
| 语言 | `zh_CN`、`en_US` 保持启用 | 停用，并取消系统语言 |

`zh_CN` 设为系统语言和登录语言。`en_US` 仍可用；种子里它通常仍是基础语言，本脚本不改这个标志。

### 占位语言 xx_XX

插入一条停用的 `xx_XX`：不是基础语言、不是系统语言、不是登录语言。已有同名行则跳过。

iDempiere 的基础语言不能同时是系统语言。默认基础语言是 `en_US`，英语原文在主表上，没有 `*_Trl`。若 `zh_CN` 和 `en_US` 都要作为登录语言并各自维护翻译，需要一个无人登录的语言充当基础语言。官方进程 [Change Base Language](https://wiki.idempiere.org/en/Change_Base_Language_(Process_ID-200040)) 的说明即：建一个不用于登录的 `xx_XX`，再把它设为基础语言。

本脚本只插入记录，不切换基础语言。之后仍需手工：

1. 启用 `xx_XX`，运行 **Change Base Language**，从 `en_US` 改为 `xx_XX`。目标不能是系统语言。
2. 将 `en_US` 标为系统语言，运行 **Language Maintenance**，补缺失翻译。
3. 关闭全部会话后重新登录。

### 2Pack

`AD_Package_Exp_Detail.SQLStatement` 改为 `varchar(20000)`，对应列（`AD_Column_UU = 7491d9c1-7e9e-4f87-897c-b8792a3c48e8`）的 `FieldLength` 改为 20000，以便 2Pack 携带较长的视图 SQL。

## 构建号

`migration/local_sql/postgresql/209901010000_Z999_BuildVersion.sql`

不调用 `register_migration_script`，每次 SyncDB / 启动都执行。文件名排在 `Z000` 之后，首次启动会盖掉 BaseSetup 写下的 `0.0.0`。

发版时只改 `v_build_version`。脚本把它写到两处，且都只动系统行：

| 位置 | 作用 |
| --- | --- |
| `AD_System.LastBuildInfo`（`AD_System_ID = 0`） | `DB.isBuildOK` 读到的库版本 |
| `AD_SysConfig` `APPLICATION_MAIN_VERSION`（`AD_Client_ID = 0`） | `Adempiere.getVersion()` 有这条配置时直接返回它，不再用 OSGi bundle 版本 |

`Z000` 把 `APPLICATION_MAIN_VERSION_SHOWN` 设为 `N`，登录页不显示这个版本。`getVersion()` 仍然使用它。租户级同名配置不改。系统行不存在时，这条 `UPDATE` 不会插入。

`isBuildOK` 比较的是 `getVersion()` 和 `LastBuildInfo`。两列都被写成同一个字符串，所以这项检查不会发现代码包版本和库不一致。每次启动都会更新系统配置的 `Updated`，即使版本字符串没有变化。
