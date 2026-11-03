@echo off
setlocal enabledelayedexpansion
title cross-clip - Banking Mode Manager

echo ===================================================
echo           cross-clip Banking Mode Manager
echo ===================================================
echo.
echo Some banking and UPI apps (e.g. Google Pay, PhonePe, YONO, BHIM)
echo scan for sensitive permissions like SYSTEM_ALERT_WINDOW (overlay)
echo or active Developer Options / USB Debugging.
echo.
echo This tool lets you instantly switch your phone into a Bank-Safe state
echo and restore cross-clip synchronization whenever you are done.
echo.

:: Locate adb executable
set "ADB_CMD=adb"
where adb >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    if exist "%~dp0tools\platform-tools\adb.exe" (
        set "ADB_CMD=%~dp0tools\platform-tools\adb.exe"
    ) else if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB_CMD=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else (
        echo [ERROR] ADB not found.
        pause
        exit /b 1
    )
)

echo Select an option:
echo  [1] Turn ON Banking Mode  (Bank-Safe: stop sync & revoke sensitive permissions)
echo  [2] Turn OFF Banking Mode (Resume Sync: restore permissions & restart cross-clip)
echo  [3] Exit
echo.
set /p choice="Enter choice (1, 2, or 3): "

if "%choice%"=="1" goto ENABLE_BANKING
if "%choice%"=="2" goto DISABLE_BANKING
if "%choice%"=="3" goto END

echo Invalid choice.
pause
goto END

:ENABLE_BANKING
echo.
echo [1/3] Stopping cross-clip service on phone...
"%ADB_CMD%" shell am force-stop com.crossclip.sync

echo [2/3] Revoking SYSTEM_ALERT_WINDOW permission...
"%ADB_CMD%" shell appops set com.crossclip.sync SYSTEM_ALERT_WINDOW deny

echo [3/3] Revoking READ_LOGS permission...
"%ADB_CMD%" shell pm revoke com.crossclip.sync android.permission.READ_LOGS

echo.
echo ===================================================
echo [SUCCESS] Banking Mode is now ACTIVE!
echo ===================================================
echo - cross-clip is completely stopped.
echo - All sensitive permissions have been revoked.
echo.
echo IMPORTANT TIP FOR BANKING / UPI APPS:
echo If your bank app still displays a warning about "Developer Options" or "USB Debugging":
echo  1. On your phone, go to Settings ^> Additional Settings ^> Developer options.
echo  2. Toggle Developer options to OFF.
echo  3. Open and use your banking/UPI app.
echo  (When you want to turn cross-clip sync back on later, turn Developer options and
echo   USB debugging back ON, connect to PC, and run Option 2 in this tool).
echo.
pause
goto END

:DISABLE_BANKING
echo.
echo [1/3] Restoring SYSTEM_ALERT_WINDOW permission...
"%ADB_CMD%" shell appops set com.crossclip.sync SYSTEM_ALERT_WINDOW allow

echo [2/3] Restoring READ_LOGS permission...
"%ADB_CMD%" shell pm grant com.crossclip.sync android.permission.READ_LOGS

echo [3/3] Restarting cross-clip...
"%ADB_CMD%" shell monkey -p com.crossclip.sync -c android.intent.category.LAUNCHER 1 >nul 2>nul

echo.
echo ===================================================
echo [SUCCESS] Banking Mode is now OFF!
echo ===================================================
echo - Permissions restored.
echo - cross-clip is active and syncing clipboard.
echo.
pause
goto END

:END
exit /b 0
