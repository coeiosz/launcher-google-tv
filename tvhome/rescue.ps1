# Waits for the TV's wireless-debugging service, reconnects and re-enables the stock launcher.
param([int]$Minutes = 30)
$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
$deadline = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $deadline) {
    $svc = & $adb mdns services | Select-String "_adb-tls-connect\._tcp\s+(\S+)"
    if ($svc) {
        $addr = $svc.Matches[0].Groups[1].Value
        & $adb connect $addr | Out-Host
        $state = & $adb -s $addr get-state 2>$null
        if ($state -eq "device") {
            & $adb -s $addr shell "pm enable com.google.android.apps.tv.launcherx; sleep 2; input keyevent HOME; sleep 2; dumpsys activity activities | grep -m1 topResumedActivity" | Out-Host
            "RESCUED via $addr"
            exit 0
        }
    }
    Start-Sleep 3
}
"TIMEOUT: TV debugging service never came back"
exit 1
