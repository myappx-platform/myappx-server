-- ============================================================================
-- 209901010000_Z999: MyAppx Build Version Stamp (Re-run on Every Server Start)
-- ============================================================================
--
-- Purpose:
--   Sync displayed application version with the deployed build.
--   Complements 209901010000_Z000 (initial 0.0.0 placeholder).
--
-- Behavior:
--   - NOT registered via register_migration_script — runs every SyncDB / restart
--   - Edit v_build_version below once when releasing a new build
--
-- Updates:
--   AD_System.LastBuildInfo               → startup build check (DB.isBuildOK)
--   AD_SysConfig APPLICATION_MAIN_VERSION → About / login version (Adempiere.getVersion)
-- ============================================================================

DO $$
DECLARE
  v_build_version TEXT := '14.0.0.20260626';  -- *** edit here only ***
BEGIN
  ---- Update lastbuildinfo
  UPDATE ad_system
     SET lastbuildinfo = v_build_version
   WHERE ad_system_id = 0;

  ---- Update APPLICATION_MAIN_VERSION in ad_sysconfig
  UPDATE ad_sysconfig
     SET value = v_build_version,
         updated = statement_timestamp(),
         updatedby = 100
   WHERE name = 'APPLICATION_MAIN_VERSION'
     AND ad_client_id = 0;
END $$;

---- NO Register SQL, and UPDATE everytime for each restart of the server
