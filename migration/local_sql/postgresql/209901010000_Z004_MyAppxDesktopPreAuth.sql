-- ============================================================================
-- 209901010000_Z004: MyAppx Desktop Pre-Auth
-- ============================================================================
--
-- Purpose:
--   Seed the WebUI pre-authentication switch used by MyAppxDesktopPreAuthFilter.
--   Default is off. Secret and bypass paths stay on JVM properties, environment
--   variables, or admin-created SysConfig rows.
--
-- Components:
--   1. MYAPPX_DESKTOP_PREAUTH_ENABLED - Y/N switch (default N)
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
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'MYAPPX_DESKTOP_PREAUTH_ENABLED', 'N', 'Enable MyAppx Desktop pre-authentication filter (Y/N)', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'MYAPPX_DESKTOP_PREAUTH_ENABLED' AND ad_client_id = 0);

SELECT register_migration_script('209901010000_Z004_MyAppxDesktopPreAuth.sql');
