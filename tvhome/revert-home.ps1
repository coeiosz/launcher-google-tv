# Puts the stock Google TV home back: re-enables launcherx and setupwraith's recovery, restores the HOME role.
param([string]$Tv = "")
$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
if (-not $Tv) { $Tv = (& $adb devices | Select-String "^192\.168\.\S+\s+device" | Select-Object -First 1).ToString().Split()[0] }
& $adb -s $Tv shell "pm enable com.google.android.tungsten.setupwraith/.RecoveryActivity; pm enable com.google.android.tungsten.setupwraith; pm enable com.google.android.apps.tv.launcherx; cmd role add-role-holder android.app.role.HOME com.google.android.tungsten.setupwraith; input keyevent HOME"
