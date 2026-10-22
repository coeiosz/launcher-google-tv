# Cold-starts the launcher on the TV, drives a fixed d-pad sequence and prints frame/memory stats.
param([string]$Tv = "192.168.0.126:38227", [string]$Shot = "")
$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"

& $adb -s $Tv shell "am force-stop dev.tvhome; am start -W -n dev.tvhome/.HomeActivity | grep TotalTime; sleep 2; dumpsys gfxinfo dev.tvhome reset >/dev/null; for k in 22 22 22 20 21 21 19 22 20 22 19 21; do input keyevent `$k; sleep 0.4; done; sleep 1; dumpsys gfxinfo dev.tvhome | grep -E 'Total frames|Janky frames:|50th percentile|90th percentile|50th gpu|90th gpu'; dumpsys meminfo dev.tvhome | grep -E 'TOTAL PSS|Java Heap|Native Heap|Graphics'"
if ($Shot) {
    & $adb -s $Tv shell "input keyevent 20; sleep 1; screencap -p /sdcard/bench.png"
    & $adb -s $Tv pull /sdcard/bench.png $Shot | Out-Null
}
