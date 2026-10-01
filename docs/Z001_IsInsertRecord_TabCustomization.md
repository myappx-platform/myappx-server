# Tab Customization 的 Insert Record

Tab Customization（`AD_UserDef_Tab`）可以单独控制这个页签能不能新建记录。空值继承基页签的 `IsInsertRecord`。基页签以后改了，空值会跟着变。`Y` / `N` 是明确覆盖，和 `IsReadOnly`、`IsSingleRow` 同一套做法。

基页签不允许插入，或基页签是只读时，自定义不能改成允许插入。

## 规则

改字段时 `CalloutUserDefTab` 立刻清空并提示。保存时 `MUserDefTab.beforeSave` 再查一次。导入和接口不走 callout，所以保存检查留在模型里。两处规则相同。打开窗口时 `GridTabVO.loadUserDefTab` 再套一遍，库里不合规则的值也不会生效。

| 条件 | 结果 |
| --- | --- |
| `IsInsertRecord` 为空 | 继承基页签 |
| 基页签 `IsInsertRecord = N`，自定义设为 Y | 清空，提示不能覆盖 |
| 基页签 `IsReadOnly = Y`，自定义 Insert 设为 Y | 清空，提示不能在只读基页签上打开插入 |
| 基页签 `IsReadOnly = Y`，自定义 `IsReadOnly` 设为 N | 清空 `IsReadOnly` 和 `IsInsertRecord` |
| 自定义 `IsReadOnly = Y`，`IsInsertRecord` 为 Y 或 N | 插入清空，回到继承。不另发提示 |
| 基页签允许插入且不是只读，自定义为 Y | 允许新建 |
| 基页签允许插入且不是只读，自定义为 N | 不允许新建 |
| 基页签允许插入且不是只读，自定义为空 | 继承基页签（允许新建） |

提示原文：

- `Base tab does not allow Insert Record. User Define Tab Customization cannot override this setting.`
- `Base tab is Read Only. User Define Tab Customization cannot enable Insert Record when base tab is Read Only.`
- `Base tab is Read Only. User Define Tab Customization cannot override this setting.`

`beforeSave` 用 `log.saveWarning` 记这三条，键是 `BaseTabInsertRecordNotAllowed`、`BaseTabReadOnlyInsertRecord`、`BaseTabReadOnlyCannotOverride`。字典里没有这些键时，界面上看到的是后面的英文说明。自定义只读时清空插入不另发提示。

没有基页签（`AD_Tab_ID` 无效，或基页签读不到）时，`beforeSave` 直接允许保存。保存按当前值判断：自定义 `IsReadOnly` 为 Y 时，`IsInsertRecord` 不是空就写成空。Callout 在只读设为 Y 时同样清空。

## 打开窗口

`GridTabVO.loadUserDefTab` 先记下基页签的 `IsInsertRecord` 和 `IsReadOnly`，再套自定义：

1. 基页签不可插入，或基页签只读：本页签不可插入。
2. 否则自定义给了 Y 或 N：按该值，并记 `userDefIsInsertRecordExplicit`。
3. 自定义为空：保持基页签的值。

工具栏 New 走 `GridTab.isInsertAllowed`。窗口工具栏（`AbstractADWindowContent`）和明细页工具栏（`DetailPane`）都调用它。`GridTab.dataNew(false)` 也走它。

- 静态 `IsReadOnly`、`IsInsertRecord` 始终生效。
- 没有 User Define Tab 时，动态 `ReadOnlyLogic` 不阻止新建。已处理的单据可以只读，仍可以新建另一条。
- 有 User Define Tab、`IsInsertRecord` 为空（继承）、并且自定义了 `ReadOnlyLogic` 的明细页签：`ReadOnlyLogic` 也阻止新建。
- 表头（TabLevel 0）：`ReadOnlyLogic` 不阻止新建。
- 自定义明确给了 Y 或 N：只看静态值。为 Y 时 `ReadOnlyLogic` 不阻止新建。

复制还要看动态只读。`GridTab.dataNew(true)` 和主窗口 Copy 在 `isReadOnly()` 为真时拒绝。当前记录只读时不能复制，包括动态只读。

`GridTable.dataNew` 检查 `m_readOnly`。这个标志在 `GridTab` 构造时设成静态 `IsReadOnly` 或 `IsView`。静态只读和视图页签在这里被拒绝。动态 `ReadOnlyLogic` 不写这个标志，是否允许新建只看 `isInsertAllowed`。

## 界面刷新

Callout 里改别的字段会马上反映。把 `IsReadOnly` 设为 Y 时，`IsInsertRecord` 被清空，界面立刻变空，并且因为 `ReadOnlyLogic = @IsReadOnly@=Y` 变成不可改。

改当前正在编辑的字段时，界面可能还显示刚选的值。编辑器处于编辑中，会忽略对同一字段的 `setValue`。模型已经写成空，错误提示会出来。保存时 `beforeSave` 写成空。

## Java

`org.adempiere.base.callout.CalloutUserDefTab` 用 `@Callout` 挂在 `AD_UserDef_Tab.IsInsertRecord` 和 `IsReadOnly`。`AnnotationBasedColumnCalloutFactoryImpl` 扫描包 `org.adempiere.base.callout`，不写 `AD_Column.Callout`。

- `org.compiere.model.MUserDefTab.beforeSave`：保存时按上表清空。只读为 Y 时，插入的 Y 和 N 都写成空。
- `org.compiere.model.GridTabVO.loadUserDefTab`：把自定义应用到页签。
- `org.compiere.model.GridTab.isInsertAllowed`：工具栏和 `dataNew` 是否允许新建。
- `org.compiere.model.GridTable.dataNew`：拒绝静态只读和视图页签。

`I_AD_UserDef_Tab` / `X_AD_UserDef_Tab` 由 Generate Model 生成。列已经在模型里，不要手改。取值常量是 `X_AD_UserDef_Tab.ISINSERTRECORD_Yes` / `ISINSERTRECORD_No`，以及 `ISREADONLY_Yes` / `ISREADONLY_No`。类型是 `String`，不是 `AD_Tab.IsInsertRecord` 那种 `boolean`。

## 迁移脚本

`migration/local_sql/postgresql/209901010000_Z001_IsInsertRecord.sql`

脚本做这些事：

1. `ad_userdef_tab.isinsertrecord CHAR(1)`，默认 `NULL`。允许 `Y`、`N`、空。
2. `AD_Column`：`AD_Table_ID = 466`，Reference 17（List），Reference Value 319（Yes/No，与 `IsReadOnly` 相同）。元素沿用已有的 `IsInsertRecord`（与 `AD_Tab.IsInsertRecord` 同一个元素）。
3. `AD_Field`：Tab Customization 窗口 `AD_Tab_ID = 394`，SeqNo 121，Grid SeqNo 120，XPosition 4，ColumnSpan 2。`ReadOnlyLogic = @IsReadOnly@=Y`，页签标成只读时这个字段不可改。

列和字段已存在则跳过。`AD_Column`、`AD_Field` 的 ID 用 `nextidfunc`。脚本不写 `AD_Column.Callout`。

## 使用

System Admin → General Rules → Window, Tab & Field → Tab Customization，或从 Window Customization 进去。选中要改的页签，Insert Record 选：

- 空：继承基页签。
- Yes：允许新建。基页签不允许时会被清空。
- No：不允许新建。

跑完脚本后重置缓存。自定义 Read Only 选 Yes 时，Insert Record 回到空。

基页签 `IsReadOnly = N`、`IsInsertRecord = Y`、`ReadOnlyLogic = @Processed@=Y` 时：当前记录已处理，页签只读，New 仍可用，Copy 不可用。没有 User Define Tab，或表头页签，都是这样。明细页签上如果自定义了 `ReadOnlyLogic` 且 Insert Record 为空，New 也不可用。

## 测试

1. 基页签 `IsInsertRecord = N`，自定义选 Yes：清空，提示不能覆盖，该页签不能新建。
2. 基页签只读，自定义 Insert 选 Yes：清空，提示不能在只读基页签上打开插入。再把自定义 `IsReadOnly` 选成 No：两项都清空，提示不能覆盖，页签仍只读。
3. 基页签允许插入且不是只读：自定义 Y 可新建，N 不可新建，空则继承（可新建）。
4. 自定义 `IsReadOnly = Y`：`IsInsertRecord` 为 Y 或 N 都会变空，并且该字段只读。
5. 基页签不允许插入时把当前字段改成 Yes：提示会出现；当前字段的界面可能仍显示 Yes，保存后为空。同时被改的其他字段会马上更新。
6. 已处理记录触发 `ReadOnlyLogic`：New 可用，Copy 不可用。视图页签或静态 `IsReadOnly = Y`：New 不可用。

## 排错

- 窗口里没有 Insert Record：确认脚本已执行，然后重置缓存。
- 改字段没有提示：callout 在 `org.adempiere.base.callout.CalloutUserDefTab`，由 `AnnotationBasedColumnCalloutFactoryImpl` 扫描注册。
- 选了 Yes 界面没变空、保存后却是空：当前字段的界面刷新会被编辑器挡住。看提示，保存后以库里的空值为准。
- 界面没拦住、保存后值变回空：`beforeSave` 按同样规则清空。那是导入或不经过 callout 的保存。
