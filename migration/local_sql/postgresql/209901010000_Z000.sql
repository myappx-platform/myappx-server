-- ============================================================================
-- 209901010000_Z000: MyAppx Platform Base Setup (Local / Fresh Install)
-- ============================================================================
--
-- Purpose:
--   Apply MyAppx branding and default settings on a new or reset database.
--   Safe to re-run: inserts use WHERE NOT EXISTS; updates are idempotent.
--
-- Sections:
--   1. System info (AD_System)
--   2. MyAppx sysconfig inserts (branding, UI, desktop pre-auth)
--   3. Sysconfig updates (login, ZK UI, session, tenant messages, 2Pack DDL)
--   4. User reset (system / superuser / GardenWorld demo)
--   5. UI customization (toolbar, dashboard)
--   6. Locale (country, currency, language)
--   7. Placeholder base language xx_XX (manual steps in comments below)
--   8. 2Pack SQL field length extension
--
-- Run: SyncDB / register_migration_script at end marks script as applied.
-- ============================================================================

-- Update ad_system
-- Platform identity; version 0.0.0 until build pipeline sets real value.
UPDATE ad_system 
SET lastbuildinfo = '0.0.0', name = 'MyAppx Platform', supportemail = 'support@local.corp', isautoerrorreport = 'N' 
WHERE ad_system_id = 0;

-- Setup Application Info - Insert ad_sysconfig records
-- Only inserts when missing (ad_client_id = 0 = system level).
-- APPLICATION_MAIN_VERSION / APPLICATION_IMPLEMENTATION_VENDOR: About box & version display
INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'APPLICATION_MAIN_VERSION', '0.0.0', 'Application Main Version', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'APPLICATION_MAIN_VERSION' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'APPLICATION_IMPLEMENTATION_VENDOR', 'MyAppx Platform', 'MyAppx Platform', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'APPLICATION_IMPLEMENTATION_VENDOR' AND ad_client_id = 0);

-- PDF_FONT_DIR: server path for fonts embedded in Jasper/PDF reports
INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'PDF_FONT_DIR', '/opt/appserver/data/fonts', 'Fonts folder for embedded in PDF', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'PDF_FONT_DIR' AND ad_client_id = 0);

-- STANDARD_REPORT_FOOTER_TRADEMARK_TEXT: report PDF footer (replaces default iDempiere®)
INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'STANDARD_REPORT_FOOTER_TRADEMARK_TEXT', 'MyEDI', 'Trademark text on standard report footer', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'STANDARD_REPORT_FOOTER_TRADEMARK_TEXT' AND ad_client_id = 0);

-- ZK_* branding: iceblue_c theme logos, favicon, browser tab title
INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'ZK_LOGO_LARGE', '~./theme/iceblue_c/images/myappx-large-logo.png', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'ZK_LOGO_LARGE' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'ZK_LOGO_SMALL', '~./theme/iceblue_c/images/myappx-small-logo.png', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'ZK_LOGO_SMALL' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'ZK_BROWSER_ICON', '~./theme/iceblue_c/images/myappx-icon.png', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'ZK_BROWSER_ICON' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'ZK_BROWSER_TITLE', 'MyEDI ...', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'ZK_BROWSER_TITLE' AND ad_client_id = 0);

-- APPLICATION_*_SHOWN = N: hide version/vendor/DB/JVM/OS/host on login/about (cleaner UX)
INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'APPLICATION_MAIN_VERSION_SHOWN', 'N', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'APPLICATION_MAIN_VERSION_SHOWN' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'APPLICATION_IMPLEMENTATION_VENDOR_SHOWN', 'N', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'APPLICATION_IMPLEMENTATION_VENDOR_SHOWN' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'APPLICATION_DATABASE_VERSION_SHOWN', 'N', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'APPLICATION_DATABASE_VERSION_SHOWN' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'APPLICATION_JVM_VERSION_SHOWN', 'N', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'APPLICATION_JVM_VERSION_SHOWN' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'APPLICATION_OS_INFO_SHOWN', 'N', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'APPLICATION_OS_INFO_SHOWN' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'APPLICATION_HOST_SHOWN', 'N', '', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'APPLICATION_HOST_SHOWN' AND ad_client_id = 0);

-- Force hide all login/about version info (idempotent on re-run)
UPDATE ad_sysconfig
SET value = 'N',
    updated = statement_timestamp(),
    updatedby = 100
WHERE ad_client_id = 0
  AND name IN (
    'APPLICATION_MAIN_VERSION_SHOWN',
    'APPLICATION_IMPLEMENTATION_VENDOR_SHOWN',
    'APPLICATION_DATABASE_VERSION_SHOWN',
    'APPLICATION_JVM_VERSION_SHOWN',
    'APPLICATION_OS_INFO_SHOWN',
    'APPLICATION_HOST_SHOWN'
  );

-- MYAPPX_DESKTOP_PREAUTH_ENABLED: Electron desktop app SSO filter (default off)
INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 0, 0, 'Y', 'MYAPPX_DESKTOP_PREAUTH_ENABLED', 'Y', 'Enable MyAppx Desktop pre-authentication filter (Y/N)', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'MYAPPX_DESKTOP_PREAUTH_ENABLED' AND ad_client_id = 0);

-- Setup AD_SYSCONFIG - Update existing configuration values
-- Overrides standard iDempiere defaults for MyAppx UX (email login, paging, upload, session 2h, etc.)
-- 2PACK_COMMIT_DDL=Y: commit DDL between 2Pack column steps on PostgreSQL (required for extension/plugin table creation)
UPDATE ad_sysconfig 
SET value = CASE name
    WHEN 'USE_EMAIL_FOR_LOGIN' THEN 'N'
    WHEN 'ZK_PAGING_SIZE' THEN '100'
    WHEN 'ZK_PAGING_DETAIL_SIZE' THEN '100'
    WHEN 'ZK_THEME_USE_FONT_ICON_FOR_IMAGE' THEN 'Y'
    WHEN 'ZK_MAX_UPLOAD_SIZE' THEN '20480'
    WHEN 'ZK_GRID_AFTER_FIND' THEN 'Y'
    WHEN 'ZK_SESSION_TIMEOUT_IN_SECONDS' THEN '7200'
    WHEN 'LOGIN_SHOW_RESETPASSWORD' THEN 'N'
    WHEN 'START_VALUE_BPLOCATION_NAME' THEN '3'
    WHEN 'MESSAGES_AT_TENANT_LEVEL' THEN 'Y'
    WHEN '2PACK_COMMIT_DDL' THEN 'Y'
    ELSE value
END,
    updated = statement_timestamp(),
    updatedby = 100
WHERE name IN (
    'USE_EMAIL_FOR_LOGIN', 
    'ZK_PAGING_SIZE', 
    'ZK_PAGING_DETAIL_SIZE',
    'ZK_THEME_USE_FONT_ICON_FOR_IMAGE',
    'ZK_MAX_UPLOAD_SIZE',
    'ZK_GRID_AFTER_FIND',
    'ZK_SESSION_TIMEOUT_IN_SECONDS',
    'LOGIN_SHOW_RESETPASSWORD',
    'START_VALUE_BPLOCATION_NAME',
    'MESSAGES_AT_TENANT_LEVEL',
    '2PACK_COMMIT_DDL'
);

-- Setup User
-- Randomize passwords on fresh install; set local superuser email (change before production).
---- Reset for System Level User
UPDATE ad_user 
SET password = generate_uuid(), updated = statement_timestamp(), updatedby = 100
WHERE ad_user_id = 10;
---- Reset for SuperUser
UPDATE ad_user 
SET email = 'superuser@local.corp', notificationtype = 'N', updated = statement_timestamp(), updatedby = 100
WHERE ad_user_id = 100;
---- Reset for GardenWorld User
UPDATE ad_user 
SET password = generate_uuid(), updated = statement_timestamp(), updatedby = 100
WHERE ad_client_id = 11;

-- Desktop/Window/Toolbar Customization
---- Window - Help
-- Move Help toolbar button to end (seqno 999) so it is less prominent
UPDATE ad_toolbarbutton 
SET seqno = 999, updated = statement_timestamp(), updatedby = 100
WHERE ad_toolbarbutton_id = 200030;
---- Disable Dashboard Content : Donate
UPDATE PA_DashboardContent 
SET IsActive = 'N', updated = statement_timestamp(), updatedby = 100
WHERE PA_DashboardContent_ID = 200005;

-- Setup Country/Language/Currency
-- Narrow master data to CN/US locale set used by MyAppx deployments.
---- Disable Countries except CN and US
UPDATE c_country 
SET isactive = 'N', updated = statement_timestamp(), updatedby = 100
WHERE countrycode NOT IN ('CN', 'US');
---- Disable Currencies except CNY, USD and EUR
UPDATE c_currency 
SET isactive = 'N', updated = statement_timestamp(), updatedby = 100;
---- Enable Currencies CNY, USD and EUR
UPDATE c_currency 
SET isactive = 'Y', updated = statement_timestamp(), updatedby = 100
WHERE iso_code IN ('CNY', 'USD', 'EUR');
---- Disable Languages except zh_CN and en_US
UPDATE ad_language 
SET isactive = 'N', issystemlanguage = 'N', updated = statement_timestamp(), updatedby = 100
WHERE ad_language NOT IN ('zh_CN', 'en_US');
---- Enable Languages zh_CN
-- zh_CN as default login locale; en_US remains available
UPDATE ad_language 
SET issystemlanguage = 'Y', isloginlocale = 'Y', updated = statement_timestamp(), updatedby = 100
WHERE ad_language = 'zh_CN';

-- Global menu search: match AD_Menu names across configured languages (Alt+G)
INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'ENABLE_MULTILANG_MENU_SEARCH', 'Y', 'Enable cross-language menu search in global search (Y/N)', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'ENABLE_MULTILANG_MENU_SEARCH' AND ad_client_id = 0);

INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y', 'MULTILANG_MENU_SEARCH_LANGUAGES', 'en_US,zh_CN', 'Comma-separated AD_Language codes for alternate menu search labels', 'A', 'S', generate_uuid()
WHERE NOT EXISTS (SELECT 1 FROM ad_sysconfig WHERE name = 'MULTILANG_MENU_SEARCH_LANGUAGES' AND ad_client_id = 0);

-- Add new base-language xx_XX
-- Placeholder for a future custom base language (replace xx_XX with real code).
-- 1. Add new language xx_XX
-- 2. TODO: "Change Base Language" from en_US to xx_XX
-- 3. TODO: Enable en_US as system language, and run process "Language Maintenance" to add missing translations
INSERT INTO ad_language(
    ad_language, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
    name, languageiso, countrycode, isbaselanguage, issystemlanguage, processing, 
    ad_language_id, isdecimalpoint, datepattern, timepattern, ad_language_uu, 
    isloginlocale, ad_printpaper_id, printname
)
VALUES (
    'xx_XX', 0, 0, 'Y', statement_timestamp(), 100, statement_timestamp(), 100,
    'xx_XX', 'xx', 'XX', 'N', 'N', NULL, 999, NULL, NULL, NULL, 
    generate_uuid(), 'N', NULL, 'xx_XX'
)
ON CONFLICT (ad_language) DO NOTHING;

-- Others
-- Sets the size of the SQL Statement field to 20.000 characters. 
-- It allows to apply big view statements with a 2Pack file.
ALTER TABLE ad_package_exp_detail 
ALTER COLUMN sqlstatement TYPE varchar(20000);
---- Update SQL Statement field length to 20.000 characters
UPDATE ad_column 
SET fieldlength = 20000, updated = statement_timestamp(), updatedby = 100
WHERE AD_Column_UU = '7491d9c1-7e9e-4f87-897c-b8792a3c48e8';


-- Register SQL
SELECT register_migration_script('209901010000_Z000.sql');
