# Sends the photos in a PC folder to the TV for the "Fotos (Início)" screensaver.
# fotos-prep.py crops each photo to 16:9 around the faces it finds (or keeps it whole over a
# blurred copy of itself); then the TV folder is replaced with exactly this set.
#   .\fotos-sync.ps1                    -> uses ..\fotos next to this script
#   .\fotos-sync.ps1 -Pasta D:\MinhasFotos
#   .\fotos-sync.ps1 -Previa            -> only builds the preview sheet, sends nothing
# Needs once: py -3.13 -m pip install --target "$env:LOCALAPPDATA\tvhome-py" pillow opencv-python-headless
# and the YuNet model (face_detection_yunet_2023mar.onnx from opencv/opencv_zoo) in that folder.
param([string]$Pasta = (Join-Path $PSScriptRoot "..\fotos"), [string]$Tv = "", [switch]$Previa)
$ErrorActionPreference = "Stop"

$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
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
if (-not $Tv) { $Tv = (& $adb devices | Select-String "^192\.168\.\S+\s+device" | Select-Object -First 1).ToString().Split()[0] }
& $adb -s $Tv shell "rm -rf $remote; mkdir -p $remote"
& $adb -s $Tv push "$out\." $remote | Out-Null
Write-Host "$($files.Count) foto(s) enviadas para a TV."
