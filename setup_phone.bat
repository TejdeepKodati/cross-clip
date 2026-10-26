@echo off
set ADB="C:\Users\tejdeep\Downloads\platform-tools-latest-windows\platform-tools\adb.exe"

echo ========================================================
echo CrossClip: Automated Phone Installer & Permissions Setup
echo ========================================================
echo.

echo 1. Checking connected Android devices...
%ADB% devices
echo.

echo If no device is listed above, please ensure:
echo  - Phone is plugged in via USB cable
echo  - "USB Debugging" is enabled in Developer Options
echo  - You tapped "Allow USB Debugging" on your phone screen
echo.
pause

echo.
echo 2. Installing CrossClip to your phone...
%ADB% install -r -g "D:\cross-clip\app-debug.apk"
echo.

echo 3. Granting System Alert Window permission (for background 20ms trampoline)...
%ADB% shell appops set com.crossclip.sync SYSTEM_ALERT_WINDOW allow

echo 4. Granting System Log reading permission...
%ADB% shell pm grant com.crossclip.sync android.permission.READ_LOGS

echo 5. Exempting from Battery Optimization (prevents sleep on 4G/5G)...
%ADB% shell dumpsys deviceidle whitelist +com.crossclip.sync

echo 6. Launching CrossClip on phone...
%ADB% shell am start -n com.crossclip.sync/.MainActivity

echo.
echo ========================================================
echo SUCCESS! CrossClip is installed and configured on your phone.
echo Tap "Start Background Sync" on your phone to activate it!
echo ========================================================
pause
