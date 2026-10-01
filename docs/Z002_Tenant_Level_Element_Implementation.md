# 租户级元素

iDempiere 13+，PostgreSQL。做法与 IDEMPIERE-5136（租户级消息）相同：每个租户可以改自己的元素翻译，不改系统默认值，也不影响其他租户。

## 查找顺序

`ELEMENTS_AT_TENANT_LEVEL = Y` 时，`Msg.getElement` 先读当前客户的 `AD_Element_Trl`。没有记录，或名称、打印名为空，再回退系统：基础语言读 `AD_Element`，其他语言读 `AD_Client_ID = 0` 的翻译。采购界面（`isSOTrx = false`）在有值时使用 `PO_Name` / `PO_PrintName`。

配置为 `N` 时只读系统翻译。已经写入的租户记录留在库里，不再被读取。

可翻译字段：Name、PrintName、PO_Name、PO_PrintName、Description、Help。

系统翻译的缓存键是 `ColumnName|isSOTrx`。当前客户大于 0 且开关为 `Y` 时，租户文案单独用 `AD_Client_ID|ColumnName|isSOTrx`。该客户没有覆盖时，租户键记空串，之后不再查库，再回退系统键。改完翻译后重置缓存。

界面上的字段名、打印名走 `Msg.getElement`。Description、Help 存在租户翻译行上，由 Synchronize Terminology 写到字段、流程参数等，不由 `getElement` 直接返回。

## Java

三处改动，都在未提交的本地修改里。

`org.compiere.model.MSysConfig` 增加常量 `ELEMENTS_AT_TENANT_LEVEL`。`Msg.getElement` 用它读系统配置。Java 侧缺省值是 `false`；迁移脚本插入的系统配置才是 `Y`。没有这条配置时，运行时只走系统翻译。

`org.compiere.util.Msg.getElement(String ad_language, String ColumnName, boolean isSOTrx, boolean isPrintName)` 在当前客户大于 0 且开关为 `Y` 时，先读租户键。未缓存则查该客户的 `AD_Element_Trl`：

- `isPrintName` 为真时取 `PrintName`、`PO_PrintName`，否则取 `Name`、`PO_Name`。
- 按 `UPPER(ColumnName)`、`AD_Client_ID`、`AD_Language` 匹配。
- 采购界面在 PO 字段非空时改用 PO 值。
- 去掉空白后非空，写入租户键并返回，不写入系统键。没有记录或名称为空时，租户键写入空串，再走系统查找。查询失败不写入缓存，下次再查。
- 客户 0 不查租户行。

系统查找里，非基础语言的 `AD_Element_Trl` 增加 `AD_Client_ID = 0`，避免和租户行一起命中。基础语言仍读 `AD_Element`，不读翻译表。

`org.compiere.process.SynchronizeTerminology` 与 `02_SynchronizeTerminology.sql` 同一套条件：从元素或流程翻译回写时加上 `源翻译.AD_Client_ID = 目标翻译.AD_Client_ID`。改到的步骤是字段翻译、采购字段翻译、流程按钮字段翻译、流程参数翻译、信息列翻译、打印项翻译（名称和多语言打印名）、列翻译、表翻译，以及 `_Trl` 表翻译。窗口、表单、菜单、工作流节点和基语言表没有改。

## 迁移脚本

`migration/local_sql/postgresql/209901010000_Z002_TenantLevelElement.sql`

脚本做这些事：

1. 插入系统配置 `ELEMENTS_AT_TENANT_LEVEL`，默认 `Y`，配置级别为 Client。已有同名系统配置则跳过。
2. `AD_Element_Trl`（`AD_Table_ID = 277`）的 AccessLevel 改为 `6`（系统 + 客户），并允许删除。
3. 主键改为 `(AD_Element_ID, AD_Language, AD_Client_ID)`。`AD_Client_ID` 标为父列，不可更新。主键里已经有 `AD_Client_ID` 时跳过。
4. 字典索引 `ad_element_trl_pkey`，列顺序为元素、语言、客户。
5. 窗口、页签、菜单都叫 `Tenant level elements`。菜单挂在 System Admin（`parent_id = 153`）下，紧挨 `Tenant level messages`。页签条件是 `AD_Element_Trl.AD_Client_ID = @#AD_Client_ID@`。窗口、页签、字段、菜单的 ID 由 `nextidfunc` 分配。

## 同步术语

主键加上客户之后，同一元素、同一语言可以同时有系统行和租户行。Synchronize Terminology 用标量子查询回写翻译；只按元素和语言关联时，PostgreSQL 报错：

```text
ERROR: more than one row returned by a subquery used as an expression
```

`org.compiere.process.SynchronizeTerminology` 和 `migration/processes_post_migration/postgresql/02_SynchronizeTerminology.sql` 在从翻译表取值的 `UPDATE` 里增加了：

```sql
源翻译.AD_Client_ID = 目标翻译.AD_Client_ID
```

系统行只读系统翻译，租户行只读同一客户的翻译。没有同客户源翻译时，该行不更新。基语言表，以及从窗口、表单、流程、菜单自身翻译复制的语句，不经过这条一对多的键，没有改。

| 脚本行 | 目标 | 来源 | 回写 |
| --- | --- | --- | --- |
| 21 | `AD_FIELD_TRL` | `AD_ELEMENT_TRL` | Name、Description、Help、Placeholder、IsTranslated |
| 27 | `AD_FIELD_TRL`（采购窗口） | `AD_ELEMENT_TRL` 的 PO 字段 | PO_Name、PO_Description、PO_Help、IsTranslated |
| 33 | `AD_FIELD_TRL`（流程按钮） | `AD_PROCESS_TRL` | Name、Description、Help、IsTranslated |
| 45 | `AD_PROCESS_PARA_TRL` | `AD_ELEMENT_TRL` | Name、Description、Help、Placeholder、IsTranslated |
| 48 | `AD_INFOCOLUMN_TRL` | `AD_ELEMENT_TRL` | Name、Description、Help、Placeholder、IsTranslated |
| 75 | `AD_PRINTFORMATITEM_TRL` | `AD_ELEMENT_TRL` | Name |
| 78 | `AD_PRINTFORMATITEM_TRL` | `AD_ELEMENT_TRL` | PrintName（多语言单据） |
| 123 | `AD_COLUMN_TRL` | `AD_ELEMENT_TRL` | Name、PlaceHolder |
| 129 | `AD_TABLE_TRL` | `AD_ELEMENT_TRL` | Name |
| 135 | `AD_TABLE_TRL`（表名以 `_Trl` 结尾） | `AD_ELEMENT_TRL` | Name，值为元素名加 ` **` |

第 33 行匹配 `AD_PROCESS_TRL` 的客户，是为了流程翻译以后也能按客户分开，避免同样的一对多。第 81、84 行仍从打印格式项本身复制打印名，不读 `AD_ELEMENT_TRL`。

租户文案只写进该租户自己的翻译行，不会盖住系统行，也不会被系统翻译写回。

## 使用

系统管理员菜单打开 **Tenant level elements**，为当前租户新增一行：语言、元素，以及要改的名称、打印名、说明、帮助。保存后重置缓存。

## 排错

- 菜单里没有窗口：确认迁移已登记，角色能看到 System Admin 下的新菜单，然后重置缓存。
- 界面仍是系统名称：确认当前客户的 `ELEMENTS_AT_TENANT_LEVEL` 为 `Y`，翻译行的客户和语言与登录一致，然后重置缓存。
- Synchronize Terminology 报 subquery 返回多行：确认 `02_SynchronizeTerminology.sql` 已是带 `AD_Client_ID` 条件的版本。
