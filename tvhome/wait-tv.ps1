# Waits until the TV is reachable again, then reports which services are up (adb, Wi-Fi remote).
# -Ip defaults to the TV's address from comum.ps1 ($env:TVHOME_IP, else 192.168.0.126).
param([int]$Minutes = 20, [string]$Ip = "")
. "$PSScriptRoot\comum.ps1"
if (-not $Ip) { $Ip = $TvIp }   # not a param default: $TvIp only exists after the dot-source above
$deadline = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $deadline) {
    $c = New-Object Net.Sockets.TcpClient
    $up = $c.ConnectAsync($Ip, 6466).Wait(1500) -and $c.Connected
    $c.Dispose()
    if ($up) {
        Start-Sleep 5
        "REMOTE UP"
        $svc = & $adb mdns services | Select-String "_adb-tls-connect\._tcp\s+(\S+)"
        if ($svc) {
            $addr = $svc.Matches[0].Groups[1].Value
            & $adb connect $addr | Out-Host
            if ((& $adb -s $addr get-state 2>$null) -eq "device") {
                "ADB UP $addr"
                & $adb -s $addr shell "cat /proc/uptime; dumpsys activity activities | grep -m1 topResumedActivity" 2>$null
            }
        } else { "ADB DOWN" }
        exit 0
    }
    Start-Sleep 3
}
"TIMEOUT"
