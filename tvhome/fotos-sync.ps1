# Sends the photos in a PC folder to the TV for the "Fotos (Início)" screensaver.
# Each photo is turned upright (EXIF orientation), scaled to cover 1920x1080 and saved as JPEG,
# then the TV folder is replaced with exactly this set.
#   .\fotos-sync.ps1                    -> uses ..\fotos next to this script
#   .\fotos-sync.ps1 -Pasta D:\MinhasFotos
param([string]$Pasta = (Join-Path $PSScriptRoot "..\fotos"), [string]$Tv = "")
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
if (-not $Tv) { $Tv = (& $adb devices | Select-String "^192\.168\.\S+\s+device" | Select-Object -First 1).ToString().Split()[0] }
$remote = "/sdcard/Android/data/dev.tvhome/files/fotos"
$W = 1920; $H = 1080

if (-not (Test-Path $Pasta)) { throw "Pasta não encontrada: $Pasta" }
$out = Join-Path $env:TEMP "tvhome-fotos"
if (Test-Path $out) { Get-ChildItem $out -File | Remove-Item -Force } else { New-Item -ItemType Directory $out | Out-Null }

$jpeg = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq "image/jpeg"
$params = New-Object Drawing.Imaging.EncoderParameters 1
$params.Param[0] = New-Object Drawing.Imaging.EncoderParameter([Drawing.Imaging.Encoder]::Quality, [long]88)

$n = 0; $skipped = @()
foreach ($f in Get-ChildItem $Pasta -File | Where-Object Extension -match '^\.(jpe?g|png|bmp)$') {
    try {
        $img = [Drawing.Image]::FromFile($f.FullName)
        if ($img.PropertyIdList -contains 0x0112) {
            switch ($img.GetPropertyItem(0x0112).Value[0]) {
                3 { $img.RotateFlip([Drawing.RotateFlipType]::Rotate180FlipNone) }
                6 { $img.RotateFlip([Drawing.RotateFlipType]::Rotate90FlipNone) }
                8 { $img.RotateFlip([Drawing.RotateFlipType]::Rotate270FlipNone) }
            }
        }
        # Cover the screen (the TV crops the overflow), never upscale.
        $s = [Math]::Min(1.0, [Math]::Max($W / $img.Width, $H / $img.Height))
        $nw = [int][Math]::Round($img.Width * $s); $nh = [int][Math]::Round($img.Height * $s)
        $bmp = New-Object Drawing.Bitmap $nw, $nh
        $g = [Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($img, 0, 0, $nw, $nh)
        $n++
        $bmp.Save((Join-Path $out ("foto_{0:D4}.jpg" -f $n)), $jpeg, $params)
        $g.Dispose(); $bmp.Dispose(); $img.Dispose()
    } catch {
        $skipped += $f.Name
    }
}
$other = @(Get-ChildItem $Pasta -File | Where-Object Extension -notmatch '^\.(jpe?g|png|bmp)$' | ForEach-Object Name)
if ($n -eq 0) { throw "Nenhuma foto JPG/PNG/BMP em $Pasta" }

& $adb -s $Tv shell "rm -rf $remote; mkdir -p $remote"
& $adb -s $Tv push "$out\." $remote | Out-Null
Write-Host "$n foto(s) enviadas para a TV."
if ($skipped) { Write-Host "Não deu para abrir: $($skipped -join ', ')" }
if ($other) { Write-Host "Ignoradas (formato não suportado, ex.: HEIC/WEBP): $($other -join ', ')" }
