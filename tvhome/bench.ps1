# Cold-starts the launcher on the TV, drives a fixed d-pad sequence and prints frame/memory stats.
# Without -Tv, uses the TV adb is connected to.
param([string]$Tv = "", [string]$Shot = "")
. "$PSScriptRoot\comum.ps1"
$Tv = Get-Tv $Tv

& $adb -s $Tv shell "am force-stop dev.tvhome; am start -W -n dev.tvhome/.HomeActivity | grep TotalTime; sleep 2; dumpsys gfxinfo dev.tvhome reset >/dev/null; for k in 22 22 22 20 21 21 19 22 20 22 19 21; do input keyevent `$k; sleep 0.4; done; sleep 1; dumpsys gfxinfo dev.tvhome | grep -E 'Total frames|Janky frames:|50th percentile|90th percentile|50th gpu|90th gpu'; dumpsys meminfo dev.tvhome | grep -E 'TOTAL PSS|Java Heap|Native Heap|Graphics'"
# force-stop also makes Android drop the app's accessibility service from the enabled list, so
# HomeGuard ("Início como tela inicial") has to be turned back on.
& $adb -s $Tv shell "settings put secure enabled_accessibility_services dev.tvhome/dev.tvhome.HomeGuard; settings put secure accessibility_enabled 1"
if ($Shot) {
    & $adb -s $Tv shell "input keyevent 20; sleep 1; screencap -p /sdcard/bench.png"
    & $adb -s $Tv pull /sdcard/bench.png $Shot | Out-Null
}
