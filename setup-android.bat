@echo off
setlocal enabledelayedexpansion
title cross-clip - Android Phone Setup

echo ===================================================
echo             cross-clip Phone Setup Tool
echo ===================================================
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
        echo [ERROR] ADB was not found on your PC.
        echo Please make sure the 'tools\platform-tools' folder exists.
        pause
        exit /b 1
    )
)

echo [1/5] Checking connected Android device...
"%ADB_CMD%" devices > "%TEMP%\cc_devices.txt"
type "%TEMP%\cc_devices.txt"
echo.

findstr /C:"unauthorized" "%TEMP%\cc_devices.txt" >nul
if %ERRORLEVEL% EQU 0 (
    echo [ACTION REQUIRED] Your phone is connected but UNAUTHORIZED!
    echo Please look at your phone screen right now:
    echo 1. You should see a popup saying "Allow USB debugging?".
    echo 2. Check the box "Always allow from this computer".
    echo 3. Tap "Allow".
    echo.
    echo Press any key after tapping Allow on your phone...
    pause >nul
    "%ADB_CMD%" devices
)

findstr /C:"device" "%TEMP%\cc_devices.txt" >nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] No authorized Android device detected.
    echo Troubleshooting tips:
    echo 1. Make sure your phone is connected with a data-capable USB cable.
    echo 2. On your phone, change USB mode from "Charging only" to "File transfer".
    echo 3. In Developer options, verify "USB debugging" is turned ON.
    pause
    exit /b 1
)

echo.
echo [2/5] Locating cross-clip APK...
set "APK_FILE=%~dp0cross-clip.apk"
if not exist "%APK_FILE%" (
    if exist "%~dp0android\app\build\outputs\apk\release\app-release.apk" (
        set "APK_FILE=%~dp0android\app\build\outputs\apk\release\app-release.apk"
    ) else (
        echo [INFO] cross-clip.apk not found in the root directory.
        echo If building from GitHub Actions, download cross-clip.apk and place it here:
        echo %~dp0cross-clip.apk
        echo.
        set /p APK_FILE="Enter full path to APK or press Enter to skip installation: "
    )
)

if exist "%APK_FILE%" (
    echo Disabling ADB package verifier check...
    "%ADB_CMD%" shell settings put global verifier_verify_adb_installs 0 >nul 2>nul
    "%ADB_CMD%" shell settings put global package_verifier_enable 0 >nul 2>nul
    echo Installing %APK_FILE% to phone...
    "%ADB_CMD%" install -r -d "%APK_FILE%"
    if %ERRORLEVEL% NEQ 0 (
        echo [WARNING] APK install returned an error. If prompted on your phone, tap "Install".
    ) else (
        echo [OK] cross-clip installed successfully!
    )
) else (
    echo [SKIP] Skipping APK install step.
)

echo.
echo [3/5] Granting required permissions via ADB...
echo - Granting SYSTEM_ALERT_WINDOW (Allows seamless background clipboard reading)...
"%ADB_CMD%" shell appops set com.crossclip.sync SYSTEM_ALERT_WINDOW allow

echo - Granting READ_LOGS...
"%ADB_CMD%" shell pm grant com.crossclip.sync android.permission.READ_LOGS

echo - Whitelisting app from battery optimization...
"%ADB_CMD%" shell dumpsys deviceidle whitelist +com.crossclip.sync

echo.
echo [4/5] Verifying granted permissions...
"%ADB_CMD%" shell appops get com.crossclip.sync SYSTEM_ALERT_WINDOW
echo.

echo ===================================================
echo [5/5] ColorOS (Oppo K13 5G) Essential Steps
echo ===================================================
echo Due to ColorOS background restrictions, do these 3 quick steps on your phone:
echo.
echo  Step A: Auto-launch
echo    1. Open phone "Settings" ^> "Apps" ^> "App management" ^> "cross-clip".
echo    2. Tap "Auto-launch" and turn it ON (allow foreground and background).
echo.
echo  Step B: Unrestricted Battery
echo    1. In the same cross-clip menu, tap "Battery usage".
echo    2. Turn ON "Allow background activity" and "Allow auto-launch".
echo.
echo  Step C: Lock in Recents (Memory Protection)
echo    1. Open cross-clip on your phone.
echo    2. Swipe up and hold to open Recent Apps.
echo    3. Tap the 3 dots (or lock icon) at the top of cross-clip and tap "Lock".
echo.
echo  Step D: Pair with PC
echo    1. Open the cross-clip app on your phone.
echo    2. Copy the Pairing Code from your Windows PC cross-clip tray app.
echo    3. Paste it into the phone app and tap "Save & Connect".
echo.
echo Setup Complete! Press any key to exit.
pause >nul
