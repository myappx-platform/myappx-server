# 全局菜单跨语言搜索

iDempiere 的全局搜索框（顶栏 **Alt+G**）默认只匹配当前登录语言的菜单名。本改动让同一角色可见的菜单，也能用配置里列出的其他语言名称命中。单据搜索页（输入以 `/` 开头）没有改。

做法参考 [idempiere-multilang-search](https://github.com/nczz/idempiere-multilang-search)（台湾社区插件）：登录后把 `AD_Menu_Trl` 的其他语言名称装进内存，过滤时先比当前语言，再比这些名称。匹配规则与核心一致：去掉重音后，少于 3 个字符用前缀，否则用包含。

参考项目用 OSGi fragment 在运行时替换 `GlobalSearch`，并复制整份 `MenuSearchController`，且读取全部已翻译语言。本实现把同样的比对写进 `MenuSearchController` 本身，用两条系统配置开关和限定语言，不部署该插件。

## 查找顺序

`ENABLE_MULTILANG_MENU_SEARCH = Y` 时，`refreshModel()` 在菜单树建好之后调用 `loadAlternativeLabels()`。当前语言的名称已经在树上，不重复加载。

1. 读 `AD_Menu_Trl`：`IsTranslated = 'Y'`、`IsActive = 'Y'`、`AD_Language` 不是当前会话语言，并且落在 `MULTILANG_MENU_SEARCH_LANGUAGES` 里。
2. 当前会话不是 `AD_Menu` 的基础语言时，再读 `AD_Menu.Name`。基础语言的名称存在基表上，翻译表里通常没有对应行。

语言列表为空（配置值被清空）时，第 1 步不加 `IN` 条件，等于加载除当前语言以外的全部翻译。查询失败时清空后的 map 保持已写入的部分，搜索退回当前语言。

配置为 `N` 时不读翻译表。已有的翻译行留在库里，过滤、回车和结果行都只看当前语言名称。

结果仍限于当前角色菜单树上已有的节点。翻译名不会把角色看不到的菜单搜出来。

## 匹配与显示

`MenuListComparator` 通过 `matchesMenuItem` 决定一项是否进入结果：当前标签命中即收录；否则在 `altLabelsMap`（键为 `AD_Menu_ID`）里找翻译名。

结果行：

- 当前语言名称命中时，在该名称上高亮匹配片段。
- 只有翻译名命中时，显示 `当前名称 · 翻译名`，翻译名整段高亮，tooltip 附上该翻译名。

回车（`onOk`）在当前名称精确匹配之外，也接受翻译名的忽略大小写精确匹配；长度不少于 3 时，翻译名的前缀或包含也可以打开对应菜单。打开后搜索框写回当前语言名称。

标签在搜索框创建时加载一次（`create()` → `refreshModel()`），不在每次按键时重查。

## Java

两处改动。

`org.compiere.model.MSysConfig` 增加常量 `ENABLE_MULTILANG_MENU_SEARCH`、`MULTILANG_MENU_SEARCH_LANGUAGES`。读取时带当前 `AD_Client_ID`，因此客户级同名配置会盖过系统行。Java 缺省是启用，语言列表 `en_US,zh_CN`。库里没有这两条配置时，运行时仍按这个缺省做跨语言搜索。

`org.adempiere.webui.apps.MenuSearchController`：

- `isMultilangMenuSearchEnabled()`、`getMultilangSearchLanguages()` 读上面两条配置。
- `loadAlternativeLabels()`、`getMenuId()`、`matchesLabel()`、`matchesMenuItem()`、`findFirstMatchingAltLabel()`、`appendHighlightedLabel()` 负责装载、比对和高亮。
- `MenuListComparator`、`onOk`、`MenuItemRenderer.render` 改为走上述方法。`refreshModel()` 末尾调用装载。

`GlobalSearch`、`HeaderPanel`、`header.zul` 仍是核心：搜索框、Menu / Search 两个页签、Alt+G。

## 迁移脚本

`migration/local_sql/postgresql/209901010000_Z003_GlobalMenuSearch.sql`

这两条插在系统客户（`AD_Client_ID = 0`），配置级别为 System（`S`）：

| Name | 默认值 | 说明 |
| --- | --- | --- |
| `ENABLE_MULTILANG_MENU_SEARCH` | `Y` | 是否在全局菜单搜索中匹配其他语言名称 |
| `MULTILANG_MENU_SEARCH_LANGUAGES` | `en_US,zh_CN` | 逗号分隔的 `AD_Language`。当前登录语言会被排除 |

已有同名系统配置则跳过，不覆盖已改过的值。

## 与参考项目的差别

参考项目：[nczz/idempiere-multilang-search](https://github.com/nczz/idempiere-multilang-search)。

| | 参考插件 | 本实现 |
| --- | --- | --- |
| 接入 | OSGi fragment，`UiLifeCycle` 里用 reflection 换掉 `GlobalSearch` | 直接改 `MenuSearchController` |
| 语言范围 | `AD_Menu_Trl` 里除当前语言外的全部已翻译语言 | 仅 `MULTILANG_MENU_SEARCH_LANGUAGES` |
| 开关 | 无，装上即启用 | `ENABLE_MULTILANG_MENU_SEARCH` |
| 结果行 | 只高亮当前语言名称 | 翻译名命中时附加 `· 翻译名` |
| 回车 | 只认当前语言名称 | 翻译名精确匹配、以及长度不少于 3 的包含/前缀，也可以打开 |
| 失败日志 | 查询失败写 warning | 查询失败被吃掉，退回当前语言 |


## 使用

系统配置里确认两条值为 `Y` 和 `en_US,zh_CN`（或要参与的语言）。菜单翻译需 `IsTranslated = Y`。改配置或翻译后重置缓存，重新登录，让搜索框重建并重新装载标签。

用中文会话搜英文菜单名，或用英文会话搜中文菜单名。命中翻译名时，结果里当前名称后面会带出那个翻译名。

## 排错

- 只能搜到当前语言：确认 `ENABLE_MULTILANG_MENU_SEARCH` 为 `Y`，目标语言在 `MULTILANG_MENU_SEARCH_LANGUAGES` 中，对应 `AD_Menu_Trl` 行 `IsTranslated = Y` 且名称非空。然后重置缓存并重新登录。
- 英文基表名称搜不到、翻译行却没有 `en_US`：用非基础语言登录。基础语言会话不会再把 `AD_Menu.Name` 装进备用标签，那些名称已经在当前菜单树上。
- 改了配置仍是旧行为：标签在桌面创建时加载。重置缓存后需要新的登录会话。
- 角色看不到的菜单搜不到：过滤只发生在已加载的菜单树上。
