-- ============================================================================
-- 209901010000_Z000_BaseSetup: MyAppx Platform Base Setup (Local / Fresh Install)
-- ============================================================================
--
-- Purpose:
--   Apply MyAppx branding and default settings on a new or reset database.
--   Runs once. register_migration_script marks it applied; SyncDB skips it after that.
--
-- Sections:
--   1. System info (AD_System)
--   2. MyAppx sysconfig inserts (branding, UI)
--   3. Sysconfig updates (login, ZK UI, session, tenant messages, 2Pack DDL)
--   4. User reset (system / superuser / GardenWorld demo)
--   5. UI customization (toolbar, dashboard)
--   6. Locale (country, currency, language)
--   7. Placeholder base language xx_XX (manual steps in comments below)
--   8. 2Pack SQL field length extension
-- ============================================================================

-- Update ad_system
-- Platform identity; version 0.0.0 until Z999 stamps the build.
UPDATE ad_system
SET lastbuildinfo = '0.0.0', name = 'MyAppx Platform', supportemail = 'support@local.corp', isautoerrorreport = 'N'
WHERE ad_system_id = 0;

-- Setup Application Info - Insert ad_sysconfig records
-- Skip names already present in seed (ad_client_id = 0 = system level).
INSERT INTO ad_sysconfig(ad_sysconfig_id, ad_client_id, ad_org_id, created, updated, createdby, updatedby, isactive, name, value, description, entitytype, configurationlevel, ad_sysconfig_uu)
SELECT nextidfunc(50009,'N'), 0, 0, statement_timestamp(), statement_timestamp(), 100, 100, 'Y',
       v.name, v.value, v.description, 'A', 'S', generate_uuid()
FROM (VALUES
    ('APPLICATION_MAIN_VERSION', '0.0.0', 'Application Main Version'),
    ('APPLICATION_IMPLEMENTATION_VENDOR', 'MyAppx Platform', 'MyAppx Platform'),
    ('PDF_FONT_DIR', 'data/fonts', 'Fonts folder for embedded in PDF'),
    ('STANDARD_REPORT_FOOTER_TRADEMARK_TEXT', 'MyAppx', 'Trademark text on standard report footer'),
    ('ZK_LOGO_LARGE', '~./theme/iceblue_c/images/myappx-large-logo.png', ''),
    ('ZK_LOGO_SMALL', '~./theme/iceblue_c/images/myappx-small-logo.png', ''),
    ('ZK_BROWSER_ICON', '~./theme/iceblue_c/images/myappx-icon.png', ''),
    ('ZK_BROWSER_TITLE', 'MyAppx ...', ''),
    ('APPLICATION_MAIN_VERSION_SHOWN', 'N', ''),
    ('APPLICATION_IMPLEMENTATION_VENDOR_SHOWN', 'N', ''),
    ('APPLICATION_DATABASE_VERSION_SHOWN', 'N', ''),
    ('APPLICATION_JVM_VERSION_SHOWN', 'N', ''),
    ('APPLICATION_OS_INFO_SHOWN', 'N', ''),
    ('APPLICATION_HOST_SHOWN', 'N', '')
) AS v(name, value, description)
WHERE NOT EXISTS (
    SELECT 1 FROM ad_sysconfig s
    WHERE s.name = v.name AND s.ad_client_id = 0
);

-- Setup AD_SYSCONFIG - Update existing configuration values
-- System level only. Tenant overrides of the same name are left alone.
-- 2PACK_COMMIT_DDL=Y: commit DDL between 2Pack column steps on PostgreSQL (required for extension/plugin table creation)
UPDATE ad_sysconfig AS s
SET value = v.value,
    updated = statement_timestamp(),
    updatedby = 100
FROM (VALUES
    ('USE_EMAIL_FOR_LOGIN', 'N'),
    ('ZK_PAGING_SIZE', '100'),
    ('ZK_PAGING_DETAIL_SIZE', '100'),
    ('ZK_THEME_USE_FONT_ICON_FOR_IMAGE', 'Y'),
    ('ZK_MAX_UPLOAD_SIZE', '20480'),
    ('ZK_GRID_AFTER_FIND', 'Y'),
    ('ZK_SESSION_TIMEOUT_IN_SECONDS', '7200'),
    ('LOGIN_SHOW_RESETPASSWORD', 'N'),
    ('START_VALUE_BPLOCATION_NAME', '3'),
    ('MESSAGES_AT_TENANT_LEVEL', 'Y'),
    ('2PACK_COMMIT_DDL', 'Y')
) AS v(name, value)
WHERE s.ad_client_id = 0
  AND s.name = v.name;

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
-- Narrow master data to the CN/US locale set used by MyAppx deployments.
---- Countries: keep CN and US, disable the rest
UPDATE c_country
SET isactive = CASE WHEN countrycode IN ('CN', 'US') THEN 'Y' ELSE 'N' END,
    updated = statement_timestamp(),
    updatedby = 100;
---- Currencies: keep CNY, USD and EUR, disable the rest
UPDATE c_currency
SET isactive = CASE WHEN iso_code IN ('CNY', 'USD', 'EUR') THEN 'Y' ELSE 'N' END,
    updated = statement_timestamp(),
    updatedby = 100;
---- Disable Languages except zh_CN and en_US
UPDATE ad_language
SET isactive = 'N', issystemlanguage = 'N', updated = statement_timestamp(), updatedby = 100
WHERE ad_language NOT IN ('zh_CN', 'en_US');
---- Enable Languages zh_CN
-- zh_CN as default login locale; en_US remains available
UPDATE ad_language
SET issystemlanguage = 'Y', isloginlocale = 'Y', updated = statement_timestamp(), updatedby = 100
WHERE ad_language = 'zh_CN';

-- Add new base-language xx_XX
-- Placeholder for a future custom base language (replace xx_XX with real code).
-- Inactive until the TODOs below are done, so it stays out of language lists.
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
    'xx_XX', 0, 0, 'N', statement_timestamp(), 100, statement_timestamp(), 100,
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
SELECT register_migration_script('209901010000_Z000_BaseSetup.sql');
