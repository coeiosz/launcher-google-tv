# Scans the /24 of the TV's IP (192.168.0.x by default, see comum.ps1) until a Chromecast (cast port 8009) answers, then reports its IP and adb state.
param([int]$Minutes = 30)
. "$PSScriptRoot\comum.ps1"
$prefix = $TvIp -replace '\.\d+$', ''
$deadline = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $deadline) {
    $tasks = @{}
    foreach ($i in 2..254) {
        $c = New-Object Net.Sockets.TcpClient
        $tasks["$prefix.$i"] = @($c, $c.ConnectAsync("$prefix.$i", 8009))
    }
    Start-Sleep -Milliseconds 2500
    $found = @()
    foreach ($k in $tasks.Keys) {
        $t = $tasks[$k]
        if ($t[1].IsCompleted -and -not $t[1].IsFaulted -and $t[0].Connected) { $found += $k }
        $t[0].Dispose()
    }
    if ($found) {
        "TV FOUND: $($found -join ',')"
        Start-Sleep 10
        & $adb mdns services | Out-Host
        exit 0
    }
    Start-Sleep 5
}
"TIMEOUT"
