@echo off
set ADB="C:\Users\tejdeep\Downloads\platform-tools-latest-windows\platform-tools\adb.exe"

echo ===================================================
echo CrossClip: Automated Phone Permissions Setup
echo ===================================================
echo.

echo Checking connected Android devices...
%ADB% devices
echo.

echo Granting System Alert Window permission (for background focus trampoline)...
%ADB% shell appops set com.crossclip.sync SYSTEM_ALERT_WINDOW allow

echo Granting System Log reading permission...
%ADB% shell pm grant com.crossclip.sync android.permission.READ_LOGS

echo Exempting from Battery Optimization (prevents sleep on 4G/5G)...
%ADB% shell dumpsys deviceidle whitelist +com.crossclip.sync

echo Restarting CrossClip on phone...
%ADB% shell am force-stop com.crossclip.sync

echo.
echo ===================================================
echo Setup Complete! Your phone is now ready for background sync.
echo ===================================================
pause
