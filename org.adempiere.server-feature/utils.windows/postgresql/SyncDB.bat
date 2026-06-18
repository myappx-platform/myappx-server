@Echo	Synchronize iDempiere Database

@Echo Upgrading database %1@%ADEMPIERE_DB_NAME%

@setlocal EnableExtensions EnableDelayedExpansion

@if "%IDEMPIERE_HOME%" == "" goto environment
@if "%ADEMPIERE_DB_NAME%" == "" goto environment
@if "%ADEMPIERE_DB_SERVER%" == "" goto environment
@if "%ADEMPIERE_DB_PORT%" == "" goto environment
@Rem Must have parameter: userAccount
@if "%1" == "" goto usage

@set "PGPASSWORD=%2"

@set "TMPFOLDER=%TEMP%"
@if "%TMPFOLDER%" == "" @set "TMPFOLDER=%TMP%"
@if "%TMPFOLDER%" == "" @set "TMPFOLDER=C:\Windows\Temp"

@set "UID=%RANDOM%%RANDOM%"
@set "ADEMPIERE_DB_USER=%1"
@set "ADEMPIERE_DB_PATH=%3"
@set "CMD=psql -b -h %ADEMPIERE_DB_SERVER% -p %ADEMPIERE_DB_PORT% -d %ADEMPIERE_DB_NAME% -U %ADEMPIERE_DB_USER%"
@set "SILENTCMD=psql -q -t -A -h %ADEMPIERE_DB_SERVER% -p %ADEMPIERE_DB_PORT% -d %ADEMPIERE_DB_NAME% -U %ADEMPIERE_DB_USER%"
@set "DIR_POST=%IDEMPIERE_HOME%\migration"

@if "%4" == "" (
    @set "DIR_SCRIPTS=%IDEMPIERE_HOME%\migration"
) else (
    @set "ARG4=%~4"
    @set "IS_ABS=0"
    @if not "%~d4" == "" @set "IS_ABS=1"
    @if "!ARG4:~0,1!" == "\" @set "IS_ABS=1"
    @if "!ARG4:~0,2!" == "\\" @set "IS_ABS=1"
    @if "!IS_ABS!" == "1" (
        @set "DIR_SCRIPTS=!ARG4!"
    ) else (
        @set "DIR_SCRIPTS=%IDEMPIERE_HOME%\!ARG4!"
    )
)

@pushd "%DIR_SCRIPTS%"
@if errorlevel 1 (
    @Echo ERROR: Cannot change to folder "%DIR_SCRIPTS%"
    @goto enderror
)

@set "LISTDB=%TMPFOLDER%\lisDB_%UID%.txt"
@set "LISTDB_RAW=%TMPFOLDER%\lisDB_raw_%UID%.txt"
@set "LISTFS=%TMPFOLDER%\lisFS_%UID%.txt"
@set "LISTPENDING=%TMPFOLDER%\lisPENDING_%UID%.txt"
@set "LISTPENDINGFOL=%TMPFOLDER%\lisPENDINGFOL_%UID%.txt"
@set "OUTDIR=%TMPFOLDER%\SyncDB_out_%UID%"
@set "MSGFILE=%TMPFOLDER%\SyncDB_error_%UID%.txt"

@if exist "%MSGFILE%" @del "%MSGFILE%" >nul 2>&1

@call %SILENTCMD% -c "select name from ad_migrationscript" > "%LISTDB_RAW%"
@findstr /R /V "^$" "%LISTDB_RAW%" > "%LISTDB%"
@del "%LISTDB_RAW%" >nul 2>&1
@sort "%LISTDB%" /o "%LISTDB%"

@type nul > "%LISTFS%"
@for /f "delims=" %%F in ('dir /b /s "%DIR_SCRIPTS%\*.sql" 2^>nul ^| findstr /I "\\%ADEMPIERE_DB_PATH%\\" ^| findstr /V /I "\\processes_post_migration\\%ADEMPIERE_DB_PATH%\\"') do @echo %%~nxF>>"%LISTFS%"
@sort "%LISTFS%" /o "%LISTFS%"

@set "DBSIZE=0"
@for %%A in ("%LISTDB%") do @set "DBSIZE=%%~zA"
@if !DBSIZE! gtr 0 (
    @findstr /V /X /I /L /G:"%LISTDB%" "%LISTFS%" > "%LISTPENDING%"
) else (
    @copy /Y "%LISTFS%" "%LISTPENDING%" >nul
)

@set "APPLIED=N"
@set "PENDINGSIZE=0"
@for %%A in ("%LISTPENDING%") do @set "PENDINGSIZE=%%~zA"
@if !PENDINGSIZE! gtr 0 (
    @type nul > "%LISTPENDINGFOL%"
    @for /f "usebackq delims=" %%F in ("%LISTPENDING%") do (
        @for /f "delims=" %%S in ('dir /b /s "%DIR_SCRIPTS%\%%F" 2^>nul ^| findstr /I "\\%ADEMPIERE_DB_PATH%\\"') do @echo %%S>>"%LISTPENDINGFOL%"
    )
    @sort "%LISTPENDINGFOL%" /o "%LISTPENDINGFOL%"
    @mkdir "%OUTDIR%" >nul 2>&1
    @for /f "usebackq delims=" %%S in ("%LISTPENDINGFOL%") do (
        @set "SCRIPT=%%S"
        @set "OUTFILE=%OUTDIR%\%%~nS.out"
        @Echo Applying !SCRIPT!
        @!CMD! < "!SCRIPT!" > "!OUTFILE!" 2>&1
        @type "!OUTFILE!"
        @set "APPLIED=Y"
        @findstr /R /C:"^ERROR:" /C:"^FEHLER:" /C:"^FATAL:" /C:"^ERRO:" "!OUTFILE!" >nul
        @if not errorlevel 1 (
            @Echo **** ERROR ON FILE !OUTFILE! - Please verify ****>>"%MSGFILE%"
            @goto pendingdone
        )
    )
) else (
    @set "FSSIZE=0"
    @for %%A in ("%LISTFS%") do @set "FSSIZE=%%~zA"
    @if !FSSIZE! gtr 0 (
        @Echo Database is already in sync - no scripts pending to apply
    ) else (
        @Echo No scripts were found to apply
    )
)

:pendingdone
@popd

@if /I "%APPLIED%" == "Y" (
    @pushd "%DIR_POST%"
    @if errorlevel 1 (
        @Echo ERROR: Cannot change to folder "%DIR_POST%"
        @goto enderror
    )
    @for /f "delims=" %%F in ('dir /b /s "processes_post_migration\%ADEMPIERE_DB_PATH%\*.sql" 2^>nul') do (
        @set "OUTFILE=%OUTDIR%\%%~nF.out"
        @!CMD! < "%%F" > "!OUTFILE!" 2>&1
        @type "!OUTFILE!"
        @findstr /R /C:"^ERROR:" /C:"^FEHLER:" /C:"^FATAL:" /C:"^ERRO:" "!OUTFILE!" >nul
        @if not errorlevel 1 (
            @Echo **** ERROR ON FILE !OUTFILE! - Please verify ****>>"%MSGFILE%"
        )
    )
    @popd
)

@set "PGPASSWORD="
@if exist "%MSGFILE%" (
    @set "MSGSIZE=0"
    @for %%A in ("%MSGFILE%") do @set "MSGSIZE=%%~zA"
    @if !MSGSIZE! gtr 0 (
        @type "%MSGFILE%"
        @Echo.
        @Echo Errors were found during the process (see message above) - please review and fix the error running manually the script - and then restart this process again
        @exit /b 1
    )
)

@goto end

:enderror
@set "PGPASSWORD="
@exit /b 1

:environment
@Echo Please make sure that the enviroment variables are set correctly:
@Echo		IDEMPIERE_HOME	e.g. D:\ADEMPIERE2
@Echo		ADEMPIERE_DB_NAME 	e.g. adempiere or xe
@Echo		ADEMPIERE_DB_SERVER 	e.g. dbserver.adempiere.org
@Echo		ADEMPIERE_DB_PORT 	e.g. 5432 or 1521

:usage
@echo Usage:		%0 <userAccount>
@echo Example:	%0 adempiere adempiere

:end
