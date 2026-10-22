# Reativa os apps desativados no debloat de 22/10/2026 (Chromecast com Google TV).
# Uso: .\restaurar-tv.ps1            -> reativa tudo
#      .\restaurar-tv.ps1 katniss     -> reativa só os pacotes que contêm "katniss"
# Antes: parear/conectar a TV (adb connect IP:PORTA da Depuração sem fio).
param([string]$Filtro = "", [string]$Tv = "192.168.0.126:38227")

$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
$pacotes = @(
  "com.google.android.apps.education.cast2class",
  "com.google.android.play.games",
  "com.google.android.feedback",
  "com.android.tv.feedbackconsent",
  "com.google.android.tv.remotecontrol.logging",
  "com.google.android.apps.tv.netoscope",
  "com.android.printspooler",
  "com.android.dreams.basic",
  "com.google.android.marvin.talkback",
  "com.google.android.videos",
  "com.google.android.katniss",
  "com.google.android.syncadapters.calendar",
  "com.android.providers.calendar"
)

foreach ($p in $pacotes | Where-Object { $_ -like "*$Filtro*" }) {
  & $adb -s $Tv shell pm enable $p
}

# Animações de volta ao padrão (1x): descomente se quiser
# & $adb -s $Tv shell "settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1"

# Paramount+ (com.cbs.ca) e Red Ball 4 foram desinstalados: reinstale pela Play Store se quiser.
