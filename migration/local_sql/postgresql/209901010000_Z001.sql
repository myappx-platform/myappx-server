-- ============================================================================
-- 209901010000_Z001: IsInsertRecord for Tab Customization (AD_UserDef_Tab)
-- ============================================================================
--
-- Purpose:
--   Add IsInsertRecord to AD_UserDef_Tab so users can control insert
--   capability per tab customization (same idea as IsReadOnly).
--
-- Steps:
--   1. Schema: add column isinsertrecord to ad_userdef_tab
--   2. Dictionary: AD_Column for IsInsertRecord (table 466)
--   3. UI: AD_Field in Tab Customization window (AD_Tab_ID=394)
--   4. Callouts: CalloutUserDefTabCustomization (@Callout in ZZZ plugin; no AD_Column.Callout)
--
-- ID generation: nextidfunc(3,'N') for AD_Column, nextidfunc(4,'N') for AD_Field
-- (uses system sequences for AD_Column / AD_Field).
--
-- ============================================================================

SET STATEMENT_TIMEOUT = 0;
SET client_encoding = 'UTF8';

-- ############################################################################
-- PART 1: SCHEMA
-- ############################################################################
-- Add IsInsertRecord to AD_UserDef_Tab. Default NULL = inherit from base tab.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
         WHERE table_name = 'ad_userdef_tab' 
           AND column_name = 'isinsertrecord'
    ) THEN
        ALTER TABLE ad_userdef_tab ADD COLUMN isinsertrecord CHAR(1) DEFAULT NULL;
        RAISE NOTICE 'Added IsInsertRecord column to AD_UserDef_Tab table';
    ELSE
        RAISE NOTICE 'IsInsertRecord column already exists in AD_UserDef_Tab table';
    END IF;
END $$;

-- ############################################################################
-- PART 2: AD_Column (AD_Table_ID=466). Ref: 17/319 (List Yes/No), same as IsReadOnly
-- ############################################################################
INSERT INTO ad_column (
    ad_column_id, ad_client_id, ad_org_id,
    isactive, created, createdby, updated, updatedby,
    name, description, help, version,
    entitytype, columnname, ad_table_id, ad_reference_id, ad_reference_value_id,
    fieldlength, iskey, isparent, ismandatory, istranslated, isidentifier,
    seqno, isencrypted, isupdateable, isselectioncolumn, issyncdatabase,
    isalwaysupdateable, isautocomplete, isallowlogging, isallowcopy,
    seqnoselection, istoolbarbutton, issecure, ad_element_id, ad_column_uu
)
SELECT 
    nextidfunc(3, 'N'), 0, 0,
    'Y', statement_timestamp(), 0, statement_timestamp(), 0,
    'Insert Record',
    'The user can insert a new Record',
    'Allow users to insert new records in this tab',
    0,
    'D', 'IsInsertRecord', 466, 17, 319,
    1, 'N', 'N', 'N', 'N', 'N',
    0, 'N', 'Y', 'N', 'N',
    'N', 'N', 'Y', 'Y',
    0, 'N', 'N',
    (SELECT ad_element_id FROM ad_element WHERE columnname = 'IsInsertRecord' LIMIT 1),
    generate_uuid()
WHERE NOT EXISTS (
    SELECT 1 FROM ad_column 
     WHERE columnname = 'IsInsertRecord' AND ad_table_id = 466
);

-- ############################################################################
-- PART 3: AD_Field in Tab Customization (AD_Tab_ID=394). SeqNo 121, Grid 120.
-- ReadOnlyLogic '@IsReadOnly@=Y' so field is disabled when tab is read-only.
-- ############################################################################
INSERT INTO ad_field (
    ad_field_id, ad_client_id, ad_org_id,
    isactive, created, createdby, updated, updatedby,
    name, description, help,
    ad_tab_id, ad_column_id,
    isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly,
    isencrypted, isreadonly, iscentrallymaintained, entitytype,
    isdisplayedgrid, seqnogrid, xposition, columnspan, readonlylogic,
    ad_field_uu
)
SELECT 
    nextidfunc(4, 'N'), 0, 0,
    'Y', statement_timestamp(), 0, statement_timestamp(), 0,
    'Insert Record',
    'The user can insert a new Record',
    'Allow users to insert new records in this tab',
    394,
    (SELECT ad_column_id FROM ad_column WHERE columnname = 'IsInsertRecord' AND ad_table_id = 466),
    'Y', 1, 121, 'N', 'N', 'N',
    'N', 'N', 'Y', 'D',
    'Y', 120, 4, 2, '@IsReadOnly@=Y',
    generate_uuid()
WHERE NOT EXISTS (
    SELECT 1 FROM ad_field 
     WHERE ad_tab_id = 394 
       AND ad_column_id = (
           SELECT ad_column_id FROM ad_column 
            WHERE columnname = 'IsInsertRecord' AND ad_table_id = 466
       )
);

-- ############################################################################
-- PART 4: Register
-- ############################################################################
SELECT register_migration_script('209901010000_Z001.sql');
