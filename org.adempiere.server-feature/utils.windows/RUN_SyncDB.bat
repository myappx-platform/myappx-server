@echo off

rem Ensure environment is loaded (IDEMPIERE_HOME may be empty)
@if "%IDEMPIERE_HOME%"=="" (
    CALL myEnvironment.bat Server
) else (
    CALL "%IDEMPIERE_HOME%\utils\myEnvironment.bat" Server
)

@Title Synchronize iDempiere Database - %IDEMPIERE_HOME% (%ADEMPIERE_DB_NAME%)

rem Run SyncDB with quoted paths to avoid issues with spaces/special chars
@call "%ADEMPIERE_DB_PATH%\SyncDB" %ADEMPIERE_DB_USER% %ADEMPIERE_DB_PASSWORD% "%ADEMPIERE_DB_PATH%"
