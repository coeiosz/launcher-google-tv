# Scans 192.168.0.0/24 until a Chromecast (cast port 8009) answers, then reports its IP and adb state.
param([int]$Minutes = 30)
$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
$deadline = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $deadline) {
    $tasks = @{}
    foreach ($i in 2..254) {
        $c = New-Object Net.Sockets.TcpClient
        $tasks["192.168.0.$i"] = @($c, $c.ConnectAsync("192.168.0.$i", 8009))
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
