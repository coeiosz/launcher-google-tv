# Puts the stock Google TV home back: re-enables launcherx and setupwraith's recovery, restores the HOME role.
# Recovery path: the shell chain uses ";" on purpose so every step is attempted even if one fails.
param([string]$Tv = "")
. "$PSScriptRoot\comum.ps1"
$Tv = Get-Tv $Tv
& $adb -s $Tv shell "pm enable com.google.android.tungsten.setupwraith/.RecoveryActivity; pm enable com.google.android.tungsten.setupwraith; pm enable com.google.android.apps.tv.launcherx; cmd role add-role-holder android.app.role.HOME com.google.android.tungsten.setupwraith; input keyevent HOME"
