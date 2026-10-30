# The ambient app reads the Chromecast's cast ID from setupwraith, and its settings screen reads the
# account mode from launcherx. launcherx must stay disabled for dev.tvhome to be the home screen;
# setupwraith stays enabled because Cast loads the device credentials from it. This turns launcherx on
# only while ambient settings are being changed.
#   .\ambiente-config.ps1 -Start   -> enables launcherx (DON'T press Home until -End)
#   .\ambiente-config.ps1 -End     -> disables it again, restores dev.tvhome as home, re-enables setupwraith
param([switch]$Start, [switch]$End, [string]$Tv = "")
$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
if (-not $Tv) { $Tv = (& $adb devices | Select-String "^192\.168\.\S+\s+device" | Select-Object -First 1).ToString().Split()[0] }

if ($Start) {
    & $adb -s $Tv shell "pm enable com.google.android.tungsten.setupwraith; pm enable com.google.android.apps.tv.launcherx; am force-stop com.google.android.apps.tv.dreamx"
    Write-Host "Ajuste liberado: Configuracoes > Sistema > Protetor de tela ambiente. Use Voltar, nao Home."
} elseif ($End) {
    & $adb -s $Tv shell "pm disable-user --user 0 com.google.android.tungsten.setupwraith; cmd package set-home-activity dev.tvhome/.HomeActivity; pm disable-user --user 0 com.google.android.apps.tv.launcherx; pm enable com.google.android.tungsten.setupwraith"
    Write-Host "Ajuste encerrado: tela inicial dev.tvhome restaurada."
} else {
    Write-Host "Use -Start ou -End."
}
