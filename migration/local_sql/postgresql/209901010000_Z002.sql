-- ============================================================================
-- Tenant Level Element
-- Migration Script: 209901010000_Z002.sql
-- 
-- Description:
--   Allow tenant level customization of AD_Element translations
--   (Name, Description, Help, PrintName, etc.)
--   Similar to IDEMPIERE-5136 (MESSAGES_AT_TENANT_LEVEL)
--
-- Components:
--   1. Database Schema Changes - Primary key update for AD_Element_Trl
--   2. System Configuration    - ELEMENTS_AT_TENANT_LEVEL setting
--   3. Dictionary Metadata     - TableIndex and IndexColumn records
--   4. Window & UI             - "Tenant level elements" maintenance window
--   5. Menu Integration        - Menu entry under System Admin
--
-- AD_Sequence_ID Reference:
--   AD_SysConfig = 50009, AD_Window = 27, AD_Tab = 19, AD_Field = 4, 
--   AD_Menu = 7, AD_TableIndex = 51011, AD_IndexColumn = 51012
-- ============================================================================

SET STATEMENT_TIMEOUT = 0;
SET client_encoding = 'UTF8';

-- ############################################################################
-- PART 1: SYSTEM CONFIGURATION
-- ############################################################################

-- ----------------------------------------------------------------------------
-- 1.1 Add ELEMENTS_AT_TENANT_LEVEL System Configuration
-- ----------------------------------------------------------------------------
-- Default: 'Y' (enabled)
-- Set to 'N' to disable tenant-level element translations
-- Configuration Level: 'C' (Client level - each tenant can override)
-- ----------------------------------------------------------------------------
INSERT INTO ad_sysconfig (
    ad_sysconfig_id, ad_client_id, ad_org_id,
    created, updated, createdby, updatedby,
    isactive, name, value, description,
    entitytype, configurationlevel, ad_sysconfig_uu
) 
SELECT 
    nextidfunc(50009, 'N'),
    0, 0,
    statement_timestamp(), statement_timestamp(), 0, 0,
    'Y',
    'ELEMENTS_AT_TENANT_LEVEL',
    'Y',
    'Turn it to Y to allow loading of tenant level elements',
    'MYAPPX.ZZZ.001',
    'C',
    generate_uuid()
WHERE NOT EXISTS (
    SELECT 1 FROM ad_sysconfig 
     WHERE name = 'ELEMENTS_AT_TENANT_LEVEL' AND ad_client_id = 0
);

-- ############################################################################
-- PART 2: DATABASE SCHEMA CHANGES
-- ############################################################################

-- ----------------------------------------------------------------------------
-- 2.1 Update AD_Table Access Level and Deleteability
-- ----------------------------------------------------------------------------
-- Change AccessLevel to '6' (System+Client) to allow tenant-level records
-- Enable IsDeleteable='Y' so tenant-level element records can be deleted
-- Similar to AD_Message_Trl table configuration
-- ----------------------------------------------------------------------------
UPDATE ad_table 
   SET accesslevel = '6',
       isdeleteable = 'Y',
       updated     = statement_timestamp(),
       updatedby   = 0 
 WHERE ad_table_id = 277 
   AND (accesslevel != '6' OR isdeleteable != 'Y');

-- ----------------------------------------------------------------------------
-- 2.2 Create TableIndex Record for Primary Key
-- ----------------------------------------------------------------------------
-- Dictionary record for the primary key constraint
-- ----------------------------------------------------------------------------
INSERT INTO ad_tableindex (
    ad_client_id, ad_org_id, ad_tableindex_id, ad_tableindex_uu,
    created, createdby, entitytype, isactive,
    name, updated, updatedby, ad_table_id,
    iscreateconstraint, isunique, processing, tableindexdrop, iskey
)
SELECT 
    0, 0, nextidfunc(200095, 'N'), generate_uuid(),
    statement_timestamp(), 0, 'MYAPPX.ZZZ.001', 'Y',
    'ad_element_trl_pkey',
    statement_timestamp(), 0, 277,
    'Y', 'Y', 'N', 'N', 'Y'
WHERE NOT EXISTS (
    SELECT 1 FROM ad_tableindex 
     WHERE ad_table_id = 277 AND name = 'ad_element_trl_pkey'
);

-- ----------------------------------------------------------------------------
-- 2.3 Create IndexColumn Records
-- ----------------------------------------------------------------------------
-- Three columns in the primary key: AD_Element_ID, AD_Language, AD_Client_ID
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_tableindex_id INTEGER;
    v_col_element_id INTEGER;
    v_col_language_id INTEGER;
    v_col_client_id INTEGER;
BEGIN
    -- Get the TableIndex ID
    SELECT ad_tableindex_id INTO v_tableindex_id
      FROM ad_tableindex
     WHERE ad_table_id = 277 AND name = 'ad_element_trl_pkey';
    
    IF v_tableindex_id IS NULL THEN
        RAISE EXCEPTION 'TableIndex ad_element_trl_pkey not found';
    END IF;
    
    -- Get column IDs in one query
    SELECT MAX(CASE WHEN columnname = 'AD_Element_ID' THEN ad_column_id END),
           MAX(CASE WHEN columnname = 'AD_Language'    THEN ad_column_id END),
           MAX(CASE WHEN columnname = 'AD_Client_ID'   THEN ad_column_id END)
      INTO v_col_element_id, v_col_language_id, v_col_client_id
      FROM ad_column
     WHERE ad_table_id = 277
       AND columnname IN ('AD_Element_ID', 'AD_Language', 'AD_Client_ID');
    
    -- Validate all columns exist
    IF v_col_element_id IS NULL THEN
        RAISE EXCEPTION 'Column AD_Element_ID not found in AD_Element_Trl table';
    END IF;
    IF v_col_language_id IS NULL THEN
        RAISE EXCEPTION 'Column AD_Language not found in AD_Element_Trl table';
    END IF;
    IF v_col_client_id IS NULL THEN
        RAISE EXCEPTION 'Column AD_Client_ID not found in AD_Element_Trl table';
    END IF;
    
    -- IndexColumn 1: AD_Element_ID (SeqNo 10)
    INSERT INTO ad_indexcolumn (
        ad_client_id, ad_org_id, ad_indexcolumn_id, ad_indexcolumn_uu,
        created, createdby, entitytype, isactive,
        updated, updatedby, ad_column_id, ad_tableindex_id, seqno
    )
    SELECT 
        0, 0, nextidfunc(200084, 'N'), generate_uuid(),
        statement_timestamp(), 0, 'MYAPPX.ZZZ.001', 'Y',
        statement_timestamp(), 0, v_col_element_id, v_tableindex_id, 10
    WHERE NOT EXISTS (
        SELECT 1 FROM ad_indexcolumn 
         WHERE ad_tableindex_id = v_tableindex_id AND ad_column_id = v_col_element_id
    );
    
    -- IndexColumn 2: AD_Language (SeqNo 20)
    INSERT INTO ad_indexcolumn (
        ad_client_id, ad_org_id, ad_indexcolumn_id, ad_indexcolumn_uu,
        created, createdby, entitytype, isactive,
        updated, updatedby, ad_column_id, ad_tableindex_id, seqno
    )
    SELECT 
        0, 0, nextidfunc(200084, 'N'), generate_uuid(),
        statement_timestamp(), 0, 'MYAPPX.ZZZ.001', 'Y',
        statement_timestamp(), 0, v_col_language_id, v_tableindex_id, 20
    WHERE NOT EXISTS (
        SELECT 1 FROM ad_indexcolumn 
         WHERE ad_tableindex_id = v_tableindex_id AND ad_column_id = v_col_language_id
    );
    
    -- IndexColumn 3: AD_Client_ID (SeqNo 30)
    INSERT INTO ad_indexcolumn (
        ad_client_id, ad_org_id, ad_indexcolumn_id, ad_indexcolumn_uu,
        created, createdby, entitytype, isactive,
        updated, updatedby, ad_column_id, ad_tableindex_id, seqno
    )
    SELECT 
        0, 0, nextidfunc(200084, 'N'), generate_uuid(),
        statement_timestamp(), 0, 'MYAPPX.ZZZ.001', 'Y',
        statement_timestamp(), 0, v_col_client_id, v_tableindex_id, 30
    WHERE NOT EXISTS (
        SELECT 1 FROM ad_indexcolumn 
         WHERE ad_tableindex_id = v_tableindex_id AND ad_column_id = v_col_client_id
    );
    
    RAISE NOTICE 'IndexColumn records created for ad_element_trl_pkey (Element: %, Language: %, Client: %)', 
                 v_col_element_id, v_col_language_id, v_col_client_id;
END $$;

-- ----------------------------------------------------------------------------
-- 2.4 Update AD_Element_Trl Primary Key
-- ----------------------------------------------------------------------------
-- Add AD_Client_ID to primary key to support tenant-specific translations
-- Only modify if AD_Client_ID is not already in the primary key
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_constraint_name TEXT;
    v_constraint_def  TEXT;
BEGIN
    -- Find current primary key constraint
    SELECT conname INTO v_constraint_name
      FROM pg_constraint
     WHERE conrelid = 'ad_element_trl'::regclass
       AND contype = 'p';
    
    IF v_constraint_name IS NOT NULL THEN
        -- Get constraint definition to check if AD_Client_ID is included
        SELECT pg_get_constraintdef(oid) INTO v_constraint_def
          FROM pg_constraint
         WHERE conname = v_constraint_name;
        
        -- Only modify if AD_Client_ID is not already in the primary key
        IF LOWER(v_constraint_def) NOT LIKE '%ad_client_id%' THEN
            EXECUTE 'ALTER TABLE ad_element_trl DROP CONSTRAINT ' || v_constraint_name || ' CASCADE';
            ALTER TABLE ad_element_trl 
                ADD CONSTRAINT ad_element_trl_pkey 
                PRIMARY KEY (ad_element_id, ad_language, ad_client_id);
            
            RAISE NOTICE 'Primary key updated to include AD_Client_ID';
        ELSE
            RAISE NOTICE 'Primary key already includes AD_Client_ID, skipping';
        END IF;
    ELSE
        -- No primary key exists, create new one
        ALTER TABLE ad_element_trl 
            ADD CONSTRAINT ad_element_trl_pkey 
            PRIMARY KEY (ad_element_id, ad_language, ad_client_id);
        
        RAISE NOTICE 'Primary key created with AD_Client_ID';
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 2.5 Set AD_Client_ID as Parent Column
-- ----------------------------------------------------------------------------
-- Mark AD_Client_ID as parent column (part of primary key, not updateable)
-- ----------------------------------------------------------------------------
UPDATE ad_column 
   SET isparent     = 'Y', 
       isupdateable = 'N',
       updated      = statement_timestamp(),
       updatedby    = 0 
 WHERE ad_table_id = 277 
   AND columnname = 'AD_Client_ID';

-- ############################################################################
-- PART 3: WINDOW DEFINITION
-- ############################################################################

-- ----------------------------------------------------------------------------
-- 3.1 Create Window, 3.2 Create Tab, Part 4 Fields, Part 5 AD_Language update
-- ----------------------------------------------------------------------------
-- Single DO block: one lookup for window/tab, one query for all 11 column IDs
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_window_id   INTEGER;
    v_tab_id      INTEGER;
    v_c_client    INTEGER;
    v_c_org       INTEGER;
    v_c_language  INTEGER;
    v_c_element_id INTEGER;
    v_c_name      INTEGER;
    v_c_printname INTEGER;
    v_c_po_name   INTEGER;
    v_c_po_printname INTEGER;
    v_c_description INTEGER;
    v_c_help      INTEGER;
    v_c_isactive  INTEGER;
BEGIN
    -- 3.1 Window: get or create
    SELECT ad_window_id INTO v_window_id
      FROM ad_window 
     WHERE name = 'Tenant level elements' AND ad_client_id = 0;
    
    IF v_window_id IS NULL THEN
        v_window_id := nextidfunc(27, 'N');
        INSERT INTO ad_window (
            ad_window_id, name,
            ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            windowtype, processing, entitytype,
            issotrx, isdefault, winheight, winwidth,
            isbetafunctionality, ad_window_uu
        ) VALUES (
            v_window_id, 'Tenant level elements',
            0, 0, 'Y',
            statement_timestamp(), 0, statement_timestamp(), 0,
            'M', 'N', 'MYAPPX.ZZZ.001',
            'Y', 'N', 0, 0,
            'N', generate_uuid()
        );
        RAISE NOTICE 'Created AD_Window with ID: %', v_window_id;
    END IF;
    
    -- 3.2 Tab: get or create
    SELECT ad_tab_id INTO v_tab_id
      FROM ad_tab 
     WHERE ad_window_id = v_window_id AND name = 'Tenant level elements';
    
    IF v_tab_id IS NULL THEN
        v_tab_id := nextidfunc(19, 'N');
        INSERT INTO ad_tab (
            ad_tab_id, name, ad_window_id, seqno,
            issinglerow, ad_table_id,
            ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            hastree, isinfotab, istranslationtab, isreadonly,
            whereclause,
            processing, importfields, tablevel, issorttab,
            entitytype, isinsertrecord, isadvancedtab,
            ad_tab_uu, treedisplayedon,
            islookuponlyselection, isallowadvancedlookup, maxqueryrecords
        ) VALUES (
            v_tab_id, 'Tenant level elements', v_window_id, 10,
            'N', 277, 0, 0, 'Y',
            statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'N', 'N', 'N',
            'AD_Element_Trl.AD_Client_ID = @#AD_Client_ID@',
            'N', 'N', 0, 'N',
            'MYAPPX.ZZZ.001', 'Y', 'N',
            generate_uuid(), 'B',
            'N', 'Y', 0
        );
        RAISE NOTICE 'Created AD_Tab with ID: %', v_tab_id;
    END IF;
    
    -- Part 4: Fetch all 11 column IDs in one query (AD_Table_ID = 277)
    SELECT
        MAX(CASE WHEN columnname = 'AD_Client_ID'   THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'AD_Org_ID'       THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'AD_Language'     THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'AD_Element_ID'   THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'Name'            THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'PrintName'       THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'PO_Name'         THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'PO_PrintName'    THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'Description'     THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'Help'            THEN ad_column_id END),
        MAX(CASE WHEN columnname = 'IsActive'        THEN ad_column_id END)
      INTO v_c_client, v_c_org, v_c_language, v_c_element_id, v_c_name,
           v_c_printname, v_c_po_name, v_c_po_printname, v_c_description,
           v_c_help, v_c_isactive
      FROM ad_column
     WHERE ad_table_id = 277
       AND columnname IN (
           'AD_Client_ID', 'AD_Org_ID', 'AD_Language', 'AD_Element_ID',
           'Name', 'PrintName', 'PO_Name', 'PO_PrintName', 'Description', 'Help', 'IsActive'
       );
    
    -- Field 1: AD_Client_ID
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_client) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan
        ) VALUES (
            nextidfunc(4, 'N'),
            'Client', 'Client/Tenant for this installation.',
            'A Client is a company or a legal entity. You cannot share data between Clients. Tenant is a synonym for Client.',
            v_tab_id, v_c_client,
            'Y', 22, 10, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'N', 0, 2
        );
    END IF;
    
    -- Field 2: AD_Org_ID
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_org) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isallowcopy, isdisplayedgrid, xposition, columnspan
        ) VALUES (
            nextidfunc(4, 'N'),
            'Organization', 'Organizational entity within client',
            'An organization is a unit of your client or legal entity - examples are store, department. You can share data between organizations.',
            v_tab_id, v_c_org,
            'Y', 22, 20, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'Y', 'N', 4, 2
        );
    END IF;
    
    -- Field 3: AD_Language (Mandatory)
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_language) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan, ismandatory
        ) VALUES (
            nextidfunc(4, 'N'),
            'Language', 'Language for this entity',
            'The Language identifies the language to use for display and formatting',
            v_tab_id, v_c_language,
            'Y', 6, 30, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'Y', 20, 2, 'Y'
        );
    END IF;
    
    -- Field 4: AD_Element_ID
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_element_id) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan
        ) VALUES (
            nextidfunc(4, 'N'),
            'Element', 'System Element enables the central maintenance of column description and help.',
            'The System Element allows you to centrally maintain the column description and help text for a database column.',
            v_tab_id, v_c_element_id,
            'Y', 22, 40, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'Y', 10, 2
        );
    END IF;
    
    -- Field 5: Name
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_name) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan, numlines
        ) VALUES (
            nextidfunc(4, 'N'),
            'Name', 'Alphanumeric identifier of the entity',
            'The name of an entity (record) is used as an default search option in addition to the search key. The name is up to 60 characters in length.',
            v_tab_id, v_c_name,
            'Y', 60, 50, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'Y', 30, 5, 3
        );
    END IF;
    
    -- Field 6: PrintName
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_printname) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan, numlines
        ) VALUES (
            nextidfunc(4, 'N'),
            'Print Name', 'Print name of the element',
            'The Print Name indicates the name that will be used when printing this element.',
            v_tab_id, v_c_printname,
            'Y', 60, 60, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'Y', 40, 5, 3
        );
    END IF;
    
    -- Field 7: PO_Name
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_po_name) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan, numlines
        ) VALUES (
            nextidfunc(4, 'N'),
            'PO Name', 'Name used in Purchase Order',
            'The PO Name indicates the name that will be used when this element is referenced in a purchase order.',
            v_tab_id, v_c_po_name,
            'Y', 60, 70, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'N', 0, 5, 3
        );
    END IF;
    
    -- Field 8: PO_PrintName
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_po_printname) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan, numlines
        ) VALUES (
            nextidfunc(4, 'N'),
            'PO Print Name', 'Print name used in Purchase Order',
            'The PO Print Name indicates the print name that will be used when this element is referenced in a purchase order.',
            v_tab_id, v_c_po_printname,
            'Y', 60, 80, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'N', 0, 5, 3
        );
    END IF;
    
    -- Field 9: Description
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_description) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan, numlines
        ) VALUES (
            nextidfunc(4, 'N'),
            'Description', 'Optional short description of the record',
            'A description is limited to 255 characters.',
            v_tab_id, v_c_description,
            'Y', 255, 90, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'N', 0, 5, 3
        );
    END IF;
    
    -- Field 10: Help
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_help) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, columnspan, numlines
        ) VALUES (
            nextidfunc(4, 'N'),
            'Comment/Help', 'Comment or Hint',
            'The Help field contains a hint, comment or help about the use of this item.',
            v_tab_id, v_c_help,
            'Y', 2000, 100, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'N', 0, 5, 3
        );
    END IF;
    
    -- Field 11: IsActive
    IF NOT EXISTS (SELECT 1 FROM ad_field WHERE ad_tab_id = v_tab_id AND ad_column_id = v_c_isactive) THEN
        INSERT INTO ad_field (
            ad_field_id, name, description, help, ad_tab_id, ad_column_id,
            isdisplayed, displaylength, seqno, issameline, isheading, isfieldonly, isencrypted,
            ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,
            isreadonly, iscentrallymaintained, entitytype, ad_field_uu,
            isdisplayedgrid, seqnogrid, xposition, columnspan
        ) VALUES (
            nextidfunc(4, 'N'),
            'Active', 'The record is active in the system',
            'There are two methods of making records unavailable in the system: One is to delete the record, the other is to de-activate the record. A de-activated record is not available for selection, but available for reports.',
            v_tab_id, v_c_isactive,
            'Y', 1, 110, 'N', 'N', 'N', 'N',
            0, 0, 'Y', statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'MYAPPX.ZZZ.001', generate_uuid(),
            'Y', 50, 2, 2
        );
    END IF;
    
    -- Part 5: Configure AD_Language field to use List reference (18) with AD_Language list (327)
    UPDATE ad_field 
       SET ad_reference_id       = 18,
           ad_reference_value_id = 327,
           updated               = statement_timestamp(),
           updatedby             = 0 
     WHERE ad_tab_id = v_tab_id 
       AND ad_column_id = v_c_language;
    
    RAISE NOTICE 'Window/Tab/Fields/AD_Language update completed for tab ID: %', v_tab_id;
END $$;

-- ############################################################################
-- PART 6: MENU INTEGRATION
-- ############################################################################

-- ----------------------------------------------------------------------------
-- 6.1 Create Menu Entry
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_window_id INTEGER;
    v_menu_id   INTEGER;
BEGIN
    -- Get window ID
    SELECT ad_window_id INTO v_window_id
      FROM ad_window 
     WHERE name = 'Tenant level elements' AND ad_client_id = 0;
    
    IF v_window_id IS NULL THEN
        RAISE EXCEPTION 'Window "Tenant level elements" not found';
    END IF;
    
    -- Check if menu already exists
    SELECT ad_menu_id INTO v_menu_id
      FROM ad_menu 
     WHERE name = 'Tenant level elements' AND ad_client_id = 0;
    
    IF v_menu_id IS NULL THEN
        v_menu_id := nextidfunc(7, 'N');
        
        INSERT INTO ad_menu (
            ad_menu_id, name, "action",
            ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            issummary, issotrx, isreadonly,
            entitytype, iscentrallymaintained,
            ad_menu_uu, ad_window_id
        ) VALUES (
            v_menu_id,
            'Tenant level elements',
            'W',
            0, 0, 'Y',
            statement_timestamp(), 0, statement_timestamp(), 0,
            'N', 'Y', 'N',
            'MYAPPX.ZZZ.001', 'Y',
            generate_uuid(),
            v_window_id
        );
        
        RAISE NOTICE 'Created AD_Menu with ID: %', v_menu_id;
        
        -- Add to menu tree under System Admin (parent_id 153), same level as 'Tenant level messages'
        -- Place it right after 'Tenant level messages' menu
        INSERT INTO ad_treenodemm (
            ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            ad_tree_id, node_id, parent_id, seqno,
            ad_treenodemm_uu
        ) 
        SELECT 
            t.ad_client_id, 0, 'Y',
            statement_timestamp(), 0, statement_timestamp(), 0,
            t.ad_tree_id,
            v_menu_id,
            153,  -- System Admin (same parent as 'Tenant level messages')
            COALESCE(
                (SELECT tnm.seqno 
                   FROM ad_treenodemm tnm
                   JOIN ad_menu m ON tnm.node_id = m.ad_menu_id
                  WHERE tnm.ad_tree_id = t.ad_tree_id 
                    AND tnm.parent_id = 153
                    AND m.name = 'Tenant level messages'
                    AND m.ad_client_id = 0
                ), 
                34  -- Fallback: next sequence after 'Tenant level messages' (seqno 34)
            ),
            generate_uuid()
          FROM ad_tree t 
         WHERE t.ad_client_id = 0 
           AND t.isactive = 'Y' 
           AND t.isallnodes = 'Y' 
           AND t.treetype = 'MM'
           AND NOT EXISTS (
               SELECT 1 FROM ad_treenodemm e 
                WHERE e.ad_tree_id = t.ad_tree_id AND e.node_id = v_menu_id
           );
        
        RAISE NOTICE 'Added menu to tree under System Admin (parent_id 153), after Tenant level messages';
    ELSE
        RAISE NOTICE 'AD_Menu already exists with ID: %', v_menu_id;
    END IF;
END $$;

-- ############################################################################
-- PART 7: MIGRATION REGISTRATION
-- ############################################################################

SELECT register_migration_script('209901010000_Z002.sql');

-- ============================================================================
-- END OF MIGRATION SCRIPT
-- ============================================================================
