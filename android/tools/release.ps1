<#
.SYNOPSIS
  GoodbyeDPI Android surum APK'sini derler, SHA-256'sini hesaplar ve GitHub surumunu yayimlama
  komutunu hazirlar. Yalnizca -Publish verilirse gh ile yayimlar.

.DESCRIPTION
  1. android\gradlew.bat dist  -> build\dist\GoodbyeDPI-Android-<surum>.apk ve GoodbyeDPI-Android.apk
  2. SHA-256 + surum notu dosyasi (build\dist\release-notes.md; guncelleyici ozeti APK adinin
     yanindaki satirda arar, GitHub'in asset "digest" alani da ayrica kontrol edilir)
  3. Asagidaki komutu yazar; -Publish ile calistirir:
       gh release create android-v<surum> <apk'lar> --latest=false --title "GoodbyeDPI Android <surum>" --notes-file <not>

  --latest=false sart: ayni depodaki masaustu guncelleyicisi /releases/latest'i okuyor ve
  Android surumunu "en son surum" gorurse kendi setup.exe'sini bulamaz.

.PARAMETER Publish
  gh release create'i gercekten calistirir (gh auth login gerekli).

.PARAMETER BuildDir
  Derin klasorlerde Windows yol siniri icin kisa derleme klasoru (-Pgdpi.buildDir), orn. C:\t\rel.

.PARAMETER SkipBuild
  Derlemeyi atla, mevcut dist ciktisini kullan.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File android\tools\release.ps1
  powershell -ExecutionPolicy Bypass -File android\tools\release.ps1 -BuildDir C:\t\rel -Publish
#>
[CmdletBinding()]
param(
    [switch]$Publish,
    [string]$BuildDir = "",
    [switch]$SkipBuild,
    [string]$Repo = "unsalable/goodbydpi"
)

$ErrorActionPreference = "Stop"
$androidDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gradleFile = Join-Path $androidDir "app\build.gradle.kts"

# Surum tek yerde: app/build.gradle.kts -> val appVersionName = "x.y.z"
$m = Select-String -Path $gradleFile -Pattern '^\s*val\s+appVersionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if ($null -eq $m) { throw "appVersionName $gradleFile icinde bulunamadi" }
$version = $m.Matches[0].Groups[1].Value
$c = Select-String -Path $gradleFile -Pattern '^\s*val\s+appVersionCode\s*=\s*(\d+)' | Select-Object -First 1
$versionCode = if ($null -ne $c) { $c.Matches[0].Groups[1].Value } else { "?" }
$tag = "android-v$version"
Write-Host "Surum: $version (versionCode $versionCode), etiket: $tag"

# Gradle JDK 17 ister; PATH'teki java eski olabilir.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($null -ne $jdk) { $env:JAVA_HOME = $jdk.FullName; Write-Host "JAVA_HOME=$($env:JAVA_HOME)" }
}

$gradleArgs = @("dist")
if ($BuildDir) { $gradleArgs = @("-Pgdpi.buildDir=$BuildDir") + $gradleArgs }
$distDir = if ($BuildDir) { Join-Path $BuildDir "root\dist" } else { Join-Path $androidDir "build\dist" }

if (-not $SkipBuild) {
    if (-not (Test-Path (Join-Path $androidDir "keystore.properties"))) {
        Write-Warning "android\keystore.properties yok: APK hata ayiklama anahtariyla imzalanacak. Yayimlanan surumler hep AYNI anahtarla imzalanmali, yoksa uygulama kendini guncelleyemez."
    }
    Push-Location $androidDir
    try {
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "gradlew dist basarisiz ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

$fixedApk = Join-Path $distDir "GoodbyeDPI-Android.apk"
$versionedApk = Join-Path $distDir "GoodbyeDPI-Android-$version.apk"
foreach ($f in @($fixedApk, $versionedApk)) {
    if (-not (Test-Path $f)) { throw "APK yok: $f (once derleyin ya da -SkipBuild'i kaldirin)" }
}

$sha = (Get-FileHash -Algorithm SHA256 -Path $fixedApk).Hash.ToLowerInvariant()
$sha2 = (Get-FileHash -Algorithm SHA256 -Path $versionedApk).Hash.ToLowerInvariant()
if ($sha -ne $sha2) { throw "Iki APK farkli ($sha / $sha2); dist ciktisi tutarsiz" }
$size = (Get-Item $fixedApk).Length

$notesFile = Join-Path $distDir "release-notes.md"
$notes = @"
GoodbyeDPI Android $version

Kurulum: GoodbyeDPI-Android.apk dosyasını indirip açın. Uygulama yüklüyse ve "Otomatik güncelle" açıksa bu sürüm kendiliğinden kurulur.

SHA-256:
``````
$sha  GoodbyeDPI-Android.apk
$sha  GoodbyeDPI-Android-$version.apk
``````
"@
[System.IO.File]::WriteAllText($notesFile, $notes, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "APK     : $fixedApk ($size bayt)"
Write-Host "SHA-256 : $sha"
Write-Host "Not     : $notesFile"
Write-Host ""

$ghArgs = @(
    "release", "create", $tag, $fixedApk, $versionedApk,
    "--repo", $Repo,
    "--latest=false",
    "--title", "GoodbyeDPI Android $version",
    "--notes-file", $notesFile
)
$quoted = $ghArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
Write-Host "Yayimlama komutu:"
Write-Host ("  gh " + ($quoted -join " "))

if (-not $Publish) {
    Write-Host ""
    Write-Host "Yayimlanmadi (yalnizca -Publish ile calisir)."
    exit 0
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "gh (GitHub CLI) bulunamadi" }
& gh release view $tag --repo $Repo *> $null
if ($LASTEXITCODE -eq 0) { throw "$tag zaten var; versionName/versionCode'u artirin" }
& gh @ghArgs
if ($LASTEXITCODE -ne 0) { throw "gh release create basarisiz ($LASTEXITCODE)" }
Write-Host "Yayimlandi: $tag"
