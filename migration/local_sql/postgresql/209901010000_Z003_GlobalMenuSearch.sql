-- ============================================================================
-- 209901010000_Z003: Global Menu Search
-- ============================================================================
--
-- Purpose:
--   Match AD_Menu names across configured languages in the global search box (Alt+G).
--
-- Components:
--   1. ENABLE_MULTILANG_MENU_SEARCH      - Y/N switch (default Y)
--   2. MULTILANG_MENU_SEARCH_LANGUAGES   - comma-separated AD_Language list
--
-- AD_Sequence_ID Reference:
--   AD_SysConfig = 50009
--
-- Configuration Level: 'S' (System). Inserts are skipped when the system row
-- already exists, so re-runs do not overwrite a changed value.
-- ============================================================================

SET STATEMENT_TIMEOUT = 0;
SET client_encoding = 'UTF8';

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'ENABLE_MULTILANG_MENU_SEARCH', 'Y', 'Enable cross-language menu search in global search (Y/N)', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'ENABLE_MULTILANG_MENU_SEARCH' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'MULTILANG_MENU_SEARCH_LANGUAGES', 'en_US,zh_CN', 'Comma-separated AD_Language codes for alternate menu search labels', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'MULTILANG_MENU_SEARCH_LANGUAGES' AND ad_client_id = 0);

SELECT register_migration_script('209901010000_Z003_GlobalMenuSearch.sql');
