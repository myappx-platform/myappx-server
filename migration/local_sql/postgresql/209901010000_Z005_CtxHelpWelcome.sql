-- ============================================================================
-- 209901010000_Z005_CtxHelpWelcome: MyAppx Getting Started Welcome Text
-- ============================================================================
--
-- Purpose:
--   Rebrand the Home Getting Started context help body (IDEMPIERE-6433 seed)
--   from "Welcome to iDempiere!" to MyAppx product wording.
--
-- Updates:
--   AD_CtxHelpMsg (UU 7d7911bb-...) MsgText English body
--   AD_CtxHelpMsg_Trl zh_CN MsgText (insert or update)
-- ============================================================================

SET STATEMENT_TIMEOUT = 0;
SET client_encoding = 'UTF8';

UPDATE AD_CtxHelpMsg
SET MsgText = '<p>Welcome to MyAppx (Powered by iDempiere)</p>

<p>Start exploring your workspace, managing data, and customizing your experience.</p>

<p>Need help? Check out our <a href="https://docs.idempiere.org/docs/getting-started" target="_blank">Getting Started Guide</a>.</p>
',
    Updated = statement_timestamp(),
    UpdatedBy = 100
WHERE AD_CtxHelpMsg_UU = '7d7911bb-1598-4354-8b7b-b6bc7e3d28a8';

UPDATE AD_CtxHelpMsg_Trl
SET MsgText = '<p>欢迎使用 MyAppx（基于 iDempiere）</p>

<p>开始探索工作区、管理数据并自定义您的体验。</p>

<p>需要帮助？请查看我们的 <a href="https://docs.idempiere.org/docs/getting-started" target="_blank">入门指南</a>。</p>
',
    IsTranslated = 'Y',
    Updated = statement_timestamp(),
    UpdatedBy = 100
WHERE AD_CtxHelpMsg_ID = 200000
  AND AD_Language = 'zh_CN';

INSERT INTO AD_CtxHelpMsg_Trl (
    AD_CtxHelpMsg_ID, AD_Language, AD_Client_ID, AD_Org_ID,
    IsActive, Created, CreatedBy, Updated, UpdatedBy,
    MsgText, IsTranslated, AD_CtxHelpMsg_Trl_UU
)
SELECT
    200000, 'zh_CN', 0, 0,
    'Y', statement_timestamp(), 100, statement_timestamp(), 100,
    '<p>欢迎使用 MyAppx（基于 iDempiere）</p>

<p>开始探索工作区、管理数据并自定义您的体验。</p>

<p>需要帮助？请查看我们的 <a href="https://docs.idempiere.org/docs/getting-started" target="_blank">入门指南</a>。</p>
',
    'Y', generate_uuid()
WHERE NOT EXISTS (
    SELECT 1 FROM AD_CtxHelpMsg_Trl
    WHERE AD_CtxHelpMsg_ID = 200000 AND AD_Language = 'zh_CN'
);

SELECT register_migration_script('209901010000_Z005_CtxHelpWelcome.sql');
