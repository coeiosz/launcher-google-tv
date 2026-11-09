# Sends the photos in a PC folder to the TV for the "Fotos (Início)" screensaver.
# fotos-prep.py crops each photo to 16:9 around the faces it finds (or keeps it whole over a
# blurred copy of itself); then the TV folder is replaced with exactly this set. The photos go to a
# temp folder first and replace the old ones only after the count checks out, so a failed push loses nothing.
#   .\fotos-sync.ps1                    -> uses ..\fotos next to this script
#   .\fotos-sync.ps1 -Pasta D:\MinhasFotos
#   .\fotos-sync.ps1 -Previa            -> only builds the preview sheet, sends nothing
# Needs once: py -3.13 -m pip install --target "$env:LOCALAPPDATA\tvhome-py" pillow opencv-python-headless
# and the YuNet model (face_detection_yunet_2023mar.onnx from opencv/opencv_zoo) in that folder.
param([string]$Pasta = (Join-Path $PSScriptRoot "..\fotos"), [string]$Tv = "", [switch]$Previa)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\comum.ps1"

$remote = "/sdcard/Android/data/dev.tvhome/files/fotos"
if (-not (Test-Path $Pasta)) { throw "Pasta não encontrada: $Pasta" }

$out = Join-Path $env:TEMP "tvhome-fotos"
if (Test-Path $out) { Get-ChildItem $out -File | Remove-Item -Force }
$prepArgs = @((Join-Path $PSScriptRoot "fotos-prep.py"), $Pasta, $out)
if ($Previa) { $prepArgs += "--preview" }
& py -3.13 @prepArgs 2>&1 | Where-Object { "$_" -notmatch "WARN" }
if ($LASTEXITCODE) { throw "fotos-prep.py falhou" }
if ($Previa) { Write-Host "Prévia: $out\_previa.jpg"; return }

$files = @(Get-ChildItem $out -Filter "foto_*.jpg")
if (-not $files) { throw "Nenhuma foto em $Pasta" }
$Tv = Get-Tv $Tv

$tmp = "$remote.novo"
$n = "?"
& $adb -s $Tv shell "rm -rf $tmp; mkdir -p $tmp"
$ok = ($LASTEXITCODE -eq 0)
if ($ok) { & $adb -s $Tv push "$out\." $tmp | Out-Null; $ok = ($LASTEXITCODE -eq 0) }
if ($ok) {
    $n = (& $adb -s $Tv shell "ls $tmp | grep -c '^foto_.*\.jpg$'") -join ""
    $ok = ($n.Trim() -eq "$($files.Count)")
}
if (-not $ok) {
    & $adb -s $Tv shell "rm -rf $tmp" | Out-Null
    throw "Falha ao enviar as fotos (esperado: $($files.Count) na TV, encontrado: $n). As fotos que já estavam na TV foram mantidas."
}
& $adb -s $Tv shell "rm -rf $remote && mv $tmp $remote"
if ($LASTEXITCODE) { throw "Falha ao trocar a pasta de fotos na TV; as fotos novas ficaram em $tmp." }
Write-Host "$($files.Count) foto(s) enviadas para a TV."
