# Shared by the other scripts (dot-source it: . "$PSScriptRoot\comum.ps1").
# Provides $adb (path to adb.exe), $TvIp (the TV's LAN IP, for scripts that can't ask adb) and Get-Tv.

$adb = @(
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
    (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source)
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $adb) { throw "adb não encontrado: instale o Platform Tools (winget install Google.PlatformTools) ou coloque o adb no PATH." }

# Override with $env:TVHOME_IP if the router hands the TV another address.
$TvIp = if ($env:TVHOME_IP) { $env:TVHOME_IP } else { "192.168.0.126" }

# Wireless debugging picks a new port on every TV reboot, so unless given one, take whatever adb has connected.
function Get-Tv([string]$Tv = "") {
    if ($Tv) { return $Tv }
    $dev = & $adb devices | Select-String "^(\S+:\d+)\s+device$" | Select-Object -First 1
    if (-not $dev) { throw "Nenhuma TV conectada: ligue a Depuração sem fio e rode adb connect IP:PORTA" }
    $dev.Matches[0].Groups[1].Value
}
