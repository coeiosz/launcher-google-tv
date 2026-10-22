# Builds tvhome.apk with the bare SDK tools (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: .\build.ps1 [-Install] [-Tv 192.168.0.126:38227]
param([switch]$Install, [string]$Tv = "192.168.0.126:38227")
$ErrorActionPreference = "Stop"

$sdk   = "$env:LOCALAPPDATA\Android\Sdk"
$bt    = "$sdk\build-tools\36.0.0"
$jar   = "$sdk\platforms\android-34\android.jar"
$jdk   = "C:\Program Files\Java\jdk-25.0.4.1"
$adb   = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
$env:JAVA_HOME = $jdk

$root = $PSScriptRoot
$out  = Join-Path $root "build"
if (Test-Path $out) { Get-ChildItem $out -Recurse -File | Remove-Item -Force }
New-Item -ItemType Directory -Force "$out\res", "$out\gen", "$out\classes", "$out\dex" | Out-Null

function Run($exe) { & $exe @args; if ($LASTEXITCODE) { throw "$exe failed ($LASTEXITCODE)" } }

Run "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
Run "$bt\aapt2.exe" link -I $jar --manifest "$root\AndroidManifest.xml" --min-sdk-version 28 --target-sdk-version 34 `
    --java "$out\gen" -o "$out\unsigned.apk" "$out\res.zip"

$sources = @(Get-ChildItem "$root\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
Run "$jdk\bin\javac.exe" --release 17 -nowarn -classpath $jar -d "$out\classes" @sources

$classes = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
Run "$bt\d8.bat" --release --min-api 28 --lib $jar --output "$out\dex" @classes

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\unsigned.apk", "Update")
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

Run "$bt\zipalign.exe" -p -f 4 "$out\unsigned.apk" "$out\aligned.apk"

# Local signing key for this sideloaded app; keep it, or updates will need an uninstall first.
# Neither the keystore nor its password (signing.local, one line) is committed; see .gitignore.
$ks = Join-Path $root "tvhome.keystore"
$pw = Join-Path $root "signing.local"
if (-not (Test-Path $pw)) { throw "Missing ${pw}: create it with the keystore password on a single line." }
if (-not (Test-Path $ks)) {
    Run "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass:file $pw -keypass:file $pw -alias tvhome `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome"
}
Run "$bt\apksigner.bat" sign --ks $ks --ks-pass "file:$pw" --out "$root\tvhome.apk" "$out\aligned.apk"
Write-Host ("Built tvhome.apk ({0:N0} KB)" -f ((Get-Item "$root\tvhome.apk").Length / 1KB))

if ($Install) { Run $adb -s $Tv install -r "$root\tvhome.apk" }
