# MyAppx 主题定制

窗口 **Theme Customization**（`AD_Window_ID = 200151`，菜单挂在 Application Dictionary 下）按租户、组织、角色、用户，给当前 ZK 主题做小范围覆盖。来源是 IDEMPIERE-6293。不用复制整套主题。

它做两件事：

- 表头 **Stylesheet**：登录后多挂一份 CSS。
- 明细 **Current Value / New Value**：`ThemeManager.getThemeResource(name)` 取主题资源时，把原来的相对路径换成新路径。

同一时刻只命中一条表头。明细挂在这条表头上，按 `CurrentValue` 精确匹配，互不叠加。

## 和另外两种改法的差别

| 做法 | 作用范围 | 要不要改代码 / 重启 |
| --- | --- | --- |
| 整套主题（OSGi fragment，`ZK_THEME`） | 用这个主题的所有人 | 要部署 fragment，刷新 `org.adempiere.ui.zk` |
| `css/fragment/custom.css.dsp` | 用这个主题的所有人 | 要部署 fragment。`theme.css.dsp` 末尾发现这个文件才 include |
| **Theme Customization** | 当前租户里匹配到的组织 / 角色 / 用户 | 窗口里维护。资源可以用附件，不必发版 |

本仓库默认主题是 `iceblue_c`（`ITheme.ZK_THEME_DEFAULT`）。`theme.css.dsp` 末尾会 include `fragment/custom.css.dsp`，但只有 classpath 里真有这个文件才生效。Theme Customization 的 Stylesheet 是另一条路，在登录后的主界面上额外加一个 `<style>`。

## 表

| 表 | 模型 | 用途 |
| --- | --- | --- |
| `AD_UserDef_Theme` | `MUserDefTheme` | 表头：范围、主题名、附加样式表 |
| `AD_UserDef_Theme_Detail` | `MUserDefThemeDetail` | 明细：资源路径替换 |

访问级别是 System + Client。运行时只取**当前租户**的记录。System 租户（`AD_Client_ID = 0`）上建的记录不会套到别的租户。

唯一索引 `AD_UserDef_Theme_Unique`：同一 `Theme + AD_Client_ID + AD_Org_ID + User + Role` 只能有一条。User、Role 为空时按 0 参与唯一约束。

`Theme` 必须等于当前会话主题名。取值顺序：JVM 参数 `-DZK_THEME`，否则系统配置 `ZK_THEME`，否则 `iceblue_c`。写错名字等于没有这条自定义。

## 谁生效

`MUserDefTheme.getBestMatch` 在当前租户、当前主题的有效记录里打分，取得分最高的一条。某一维填了具体值但和当前会话不一致，这条直接淘汰。

| 用户 | 角色 | 组织 | 权重 |
| --- | --- | --- | --- |
| 当前用户 | 当前角色 | 当前组织 | 7 |
| 当前用户 | 当前角色 | 空 | 6 |
| 当前用户 | 空 | 当前组织 | 5 |
| 当前用户 | 空 | 空 | 4 |
| 空 | 当前角色 | 当前组织 | 3 |
| 空 | 当前角色 | 空 | 2 |
| 空 | 空 | 当前组织 | 1 |
| 空 | 空 | 空 | 0 |

组织、用户、角色留空表示不限制这一维。组织 ID 为 0 也算不限制。权重对应的维度组合在唯一索引下各只有一条，所以不会出现同分。

结果按 `主题_租户_组织_角色_用户` 放进缓存。改表头会清掉该租户的列表缓存。

## Stylesheet

字段在表头，最长 512。空则不加额外样式。`ThemeManager.getUserDefineStyleSheet` 的解析：

| 填写内容 | 实际地址 |
| --- | --- |
| `https://...` | 原样使用 |
| `attachment:表名/文件名,记录ID或UU` | `/astyles?path=attachment/表名/文件名&recordid=...` |
| 以 `/` 开头的主题内路径，例如 `/css/my.css` | `~./theme/{当前主题}/css/my.css` |

相对路径必须带前导 `/`。写成 `css/my.css` 会拼成 `~./theme/iceblue_ccss/my.css`。只认 `https://`。`http://` 会当成相对路径再拼一次主题前缀。

登录完成后 `AdempiereWebUI` 把这个地址做成一个 `Style`，追加到页面上。它不替换 `theme.css.dsp`，而是后加载，用来覆盖前面的规则。登录页不走这里。

改 Stylesheet 要重新登录才看得到。页面只在会话初始化时挂一次。

## 明细：替换主题资源

`ThemeManager.getThemeResource(name)` 的 `name` 是主题根下的相对路径，例如 `images/Save24.png`、`zul/desktop/desktop.zul`。工具栏、报表按钮、桌面 zul 都从这里取。

明细里：

- **Current Value**：和这次调用传入的 `name` 完全一致，区分大小写，不要写成 `~./theme/...`。
- **New Value**：替换后的路径。

| New Value | 结果 |
| --- | --- |
| `https://...` | 直接用这个 URL |
| `attachment:表名/文件名,记录ID或UU` | `/aimages?path=attachment/...&recordid=...` |
| 其他，例如 `images/Delete24.png` | 仍拼到当前主题下：`~./theme/{主题}/images/Delete24.png` |

查找只在已命中的那条表头下进行，条件是 `CurrentValue = ?` 且记录有效。同一 `CurrentValue` 有多条时取查询返回的第一条。没有匹配就用原来的 `name`。

图标对调的例子：Current Value = `images/Save24.png`，New Value = `images/Delete24.png`。界面上 Save 位置显示的是 Delete 的图，点击行为不变。

系统配置 `ZK_THEME_USE_FONT_ICON_FOR_IMAGE = Y` 时，`ButtonFactory` 用字体图标（`Icon.getIconSclass`），不再调用 `getThemeResource`。这时明细换图片看不到效果，要用 Stylesheet 改 `btn-save` 这类 class。`ZK_BUTTON_STYLE` 不含 `I`（不要图标）时同样不会走图片替换。

## 附件

把 css 或图片挂到 Theme Customization 记录上，字段里写：

```text
attachment:AD_UserDef_Theme/custom.css,<这条记录的 UU>
```

`AD_UserDef_Theme` 是表名，`custom.css` 是附件文件名，逗号后面是记录 ID 或 UU（UU 长度 36）。Stylesheet 走 `/astyles`，图片类 New Value 走 `/aimages`。这样改资源不用发 fragment。

## 代码

- `org.compiere.model.MUserDefTheme`：按租户加载、按权重选一条。
- `org.compiere.model.MUserDefThemeDetail.get`：在选中的表头下按 `CurrentValue` 查一条。
- `org.adempiere.webui.theme.ThemeManager.getUserDefineStyleSheet`：解析 Stylesheet。
- `org.adempiere.webui.theme.ThemeManager.getThemeResource`：解析资源替换。
- `org.adempiere.webui.AdempiereWebUI`：登录后挂上用户样式。
- `org.compiere.model.MAttachment`：`attachment:` 转成 `/astyles` 或 `/aimages`。

`I_*` / `X_*` 由 Generate Model 生成，业务判断在 `MUserDefTheme` / `MUserDefThemeDetail`。
