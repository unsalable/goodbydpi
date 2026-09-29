<#
.SYNOPSIS
  GoodbyeDPI Android surum APK'sini derler, dogrular, SHA-256'sini hesaplar ve GitHub surumunu
  yayimlama komutunu hazirlar. Yalnizca -Publish verilirse gh ile yayimlar.

.DESCRIPTION
  1. android\gradlew.bat dist  -> build\dist\GoodbyeDPI-Android-<surum>.apk ve GoodbyeDPI-Android.apk
  2. Imza denetimi (apksigner): APK surum anahtariyla (sabitlenmis sertifika SHA-256'si)
     imzali olmali; "CN=Android Debug" ya da baska bir anahtar reddedilir. Farkli imzali bir
     surum kurulu uygulamalar tarafindan indirilip reddedilir ve her denetimde yeniden indirilir.
  3. SHA-256 + surum notu (build\dist\release-notes.md; guncelleyici ozeti APK adinin yanindaki
     satirda arar, GitHub'in asset "digest" alani da ayrica kontrol edilir)
  4. -Publish / -DryRun: GitHub'daki en yeni android-v* surumuyle karsilastirir (versionName ve
     versionCode kesin olarak buyuk olmali), etiketin bos oldugunu dogrular ve
       gh release create android-v<surum> <iki apk> --latest=false --title ... --notes-file <not>
     calistirir (-DryRun'da yalnizca yazar).

  --latest=false sart: ayni depodaki masaustu guncelleyicisi /releases/latest'i okuyor ve
  Android surumunu "en son surum" gorurse kendi setup.exe'sini bulamaz.

  Windows PowerShell 5.1 ile calisir: yerel komutlarin (gh, gradlew, apksigner) stderr ciktisi
  $ErrorActionPreference = "Stop" altinda sonlandirici hataya donusmesin diye hepsi
  Invoke-Native uzerinden, cikis koduna bakilarak calistirilir.

.PARAMETER Publish
  gh release create'i gercekten calistirir (gh auth login gerekli).

.PARAMETER DryRun
  -Publish'in butun denetimlerini yapar (GitHub'a yalnizca okuma istekleri gider) ama surumu
  olusturmaz.

.PARAMETER BuildDir
  Derin klasorlerde Windows yol siniri icin kisa derleme klasoru (-Pgdpi.buildDir), orn. C:\t\rel.

.PARAMETER SkipBuild
  Derlemeyi atla, mevcut dist ciktisini kullan.

.PARAMETER ExpectedCertSha256
  Surum imza sertifikasinin SHA-256'si (apksigner verify --print-certs). Anahtar degisirse
  kurulu uygulamalar kendini guncelleyemez; bu deger bu yuzden sabit.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File android\tools\release.ps1 -BuildDir C:\t\rel -DryRun
  powershell -NoProfile -ExecutionPolicy Bypass -File android\tools\release.ps1 -BuildDir C:\t\rel -Publish
#>
[CmdletBinding()]
param(
    [switch]$Publish,
    [switch]$DryRun,
    [string]$BuildDir = "",
    [switch]$SkipBuild,
    [string]$Repo = "unsalable/goodbydpi",
    [string]$ExpectedCertSha256 = "b819e563f48e618668e37a897ba442fd4a0749f853bae028429fce4db55c74b5",
    # Betik testi icin: GitHub surum listesi yerine bu dosya (satir basina {tag_name,draft,body}).
    [string]$ReleasesFile = ""
)

$ErrorActionPreference = "Stop"
$remoteChecks = $Publish -or $DryRun

# Yerel komutu calistirir; stderr satirlari metne cevrilir, hata sayilmaz. Karar cikis kodunda.
# (PS 5.1'de EAP=Stop iken yonlendirilmis stderr her satirda NativeCommandError firlatir.)
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Exe,
        [string[]]$Arguments = @(),
        [switch]$Stream
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = New-Object System.Collections.Generic.List[string]
        & $Exe @Arguments 2>&1 | ForEach-Object {
            $text = if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
            $lines.Add($text)
            if ($Stream) { Write-Host $text }
        }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    return [pscustomobject]@{ Code = $code; Output = ($lines -join "`n") }
}

function Fail([string]$Message) {
    Write-Host ""
    Write-Host "HATA: $Message" -ForegroundColor Red
    exit 1
}

function Parse-Version([string]$Text) {
    if ($Text -match '(\d+(?:\.\d+){0,3})') {
        $parts = $Matches[1].Split('.') | ForEach-Object { [int]$_ }
        while ($parts.Count -lt 4) { $parts += 0 }
        return [version]::new($parts[0], $parts[1], $parts[2], $parts[3])
    }
    return $null
}

$androidDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gradleFile = Join-Path $androidDir "app\build.gradle.kts"

# Surum tek yerde: app/build.gradle.kts -> val appVersionName = "x.y.z", val appVersionCode = N
$m = Select-String -Path $gradleFile -Pattern '^\s*val\s+appVersionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if ($null -eq $m) { Fail "appVersionName $gradleFile icinde bulunamadi" }
$version = $m.Matches[0].Groups[1].Value
$c = Select-String -Path $gradleFile -Pattern '^\s*val\s+appVersionCode\s*=\s*(\d+)' | Select-Object -First 1
if ($null -eq $c) { Fail "appVersionCode $gradleFile icinde bulunamadi" }
$versionCode = [int]$c.Matches[0].Groups[1].Value
$tag = "android-v$version"
Write-Host "Surum: $version (versionCode $versionCode), etiket: $tag"

# SDK: ANDROID_HOME / ANDROID_SDK_ROOT, yoksa local.properties'teki sdk.dir.
function Find-Sdk {
    foreach ($v in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($v -and (Test-Path $v)) { return $v }
    }
    $lp = Join-Path $androidDir "local.properties"
    if (Test-Path $lp) {
        $line = Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $p = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\:', ':' -replace '\\\\', '\'
            if (Test-Path $p) { return $p }
        }
    }
    $def = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $def) { return $def }
    return $null
}

function Find-BuildTool([string]$Name) {
    $sdk = Find-Sdk
    if (-not $sdk) { return $null }
    $bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
        Sort-Object { Parse-Version $_.Name } -Descending
    foreach ($d in $bt) {
        $f = Join-Path $d.FullName $Name
        if (Test-Path $f) { return $f }
    }
    return $null
}

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
        # Yayimlanacaksa derlemeye hic baslama: hata ayiklama anahtariyla imzalanan surum
        # kurulu hicbir uygulamayi guncelleyemez.
        if ($Publish -and -not $DryRun) { Fail "android\keystore.properties yok: surum anahtari olmadan yayimlanamaz." }
        Write-Warning "android\keystore.properties yok: APK hata ayiklama anahtariyla imzalanacak (yayimlanamaz)."
    }
    Push-Location $androidDir
    try {
        $r = Invoke-Native -Exe ".\gradlew.bat" -Arguments $gradleArgs -Stream
        if ($r.Code -ne 0) { Fail "gradlew dist basarisiz ($($r.Code))" }
    } finally {
        Pop-Location
    }
}

$fixedApk = Join-Path $distDir "GoodbyeDPI-Android.apk"
$versionedApk = Join-Path $distDir "GoodbyeDPI-Android-$version.apk"
foreach ($f in @($fixedApk, $versionedApk)) {
    if (-not (Test-Path $f)) { Fail "APK yok: $f (once derleyin ya da -SkipBuild'i kaldirin)" }
}

$sha = (Get-FileHash -Algorithm SHA256 -Path $fixedApk).Hash.ToLowerInvariant()
$sha2 = (Get-FileHash -Algorithm SHA256 -Path $versionedApk).Hash.ToLowerInvariant()
if ($sha -ne $sha2) { Fail "Iki APK farkli ($sha / $sha2); dist ciktisi tutarsiz" }
$size = (Get-Item $fixedApk).Length

# ---------------------------------------------------------------- imza ve surum kodu (APK'dan)
$problems = New-Object System.Collections.Generic.List[string]

$apksigner = Find-BuildTool "apksigner.bat"
if (-not $apksigner) {
    $problems.Add("apksigner bulunamadi (Android SDK build-tools); imza dogrulanamadi")
} else {
    $v = Invoke-Native -Exe $apksigner -Arguments @("verify", "--print-certs", $fixedApk)
    if ($v.Code -ne 0) {
        $problems.Add("apksigner verify basarisiz: $($v.Output)")
    } else {
        $digests = @([regex]::Matches($v.Output, 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})') | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })
        $dns = @([regex]::Matches($v.Output, 'certificate DN:\s*(.+)') | ForEach-Object { $_.Groups[1].Value.Trim() })
        Write-Host "Imza   : $($dns -join ' | ')"
        Write-Host "Sertifika SHA-256: $($digests -join ', ')"
        if ($dns | Where-Object { $_ -match 'CN=Android Debug' }) {
            $problems.Add("APK hata ayiklama anahtariyla (CN=Android Debug) imzali")
        }
        if ($digests.Count -ne 1 -or $digests[0] -ne $ExpectedCertSha256.ToLowerInvariant()) {
            $problems.Add("imza sertifikasi beklenen surum anahtari degil (beklenen $ExpectedCertSha256)")
        }
    }
}

$aapt = Find-BuildTool "aapt.exe"
function Get-ApkVersionCode([string]$Apk) {
    if (-not $aapt) { return $null }
    $b = Invoke-Native -Exe $aapt -Arguments @("dump", "badging", $Apk)
    if ($b.Code -eq 0 -and $b.Output -match "versionCode='(\d+)'") { return [int]$Matches[1] }
    return $null
}
$apkCode = Get-ApkVersionCode $fixedApk
if ($null -ne $apkCode -and $apkCode -ne $versionCode) {
    $problems.Add("APK versionCode $apkCode, build.gradle.kts $versionCode (dist eski mi?)")
}

# ---------------------------------------------------------------- surum notu
$notesFile = Join-Path $distDir "release-notes.md"
$notes = @"
GoodbyeDPI Android $version

Kurulum: GoodbyeDPI-Android.apk dosyasını indirip açın. Uygulama yüklüyse ve "Otomatik güncelle" açıksa bu sürüm kendiliğinden kurulur.

versionCode: $versionCode

SHA-256:
``````
$sha  GoodbyeDPI-Android.apk
$sha  GoodbyeDPI-Android-$version.apk
``````
"@
[System.IO.File]::WriteAllText($notesFile, $notes, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "APK     : $fixedApk ($size bayt)"
Write-Host "APK     : $versionedApk"
Write-Host "SHA-256 : $sha"
Write-Host "Not     : $notesFile"
Write-Host ""

# ---------------------------------------------------------------- GitHub denetimleri (salt okuma)
if ($remoteChecks) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "gh (GitHub CLI) bulunamadi" }

    # Etiket bos mu? 404 = bos; baska her hata (oturum, ag) belirsizdir ve durdurur.
    foreach ($path in @("repos/$Repo/releases/tags/$tag", "repos/$Repo/git/ref/tags/$tag")) {
        $t = Invoke-Native -Exe "gh" -Arguments @("api", $path)
        if ($t.Code -eq 0) {
            $problems.Add("$tag zaten var ($path); versionName/versionCode'u artirin")
        } elseif ($t.Output -notmatch 'HTTP 404|Not Found') {
            Fail "GitHub'a sorulamadi ($path): $($t.Output)"
        }
    }

    # En yeni android-v* surumu (taslaklar dahil degil). Liste yeniden eskiye; 100'luk sayfalar.
    # --jq her surumu tek satirlik bir JSON nesnesi olarak yazar (govdedeki satir sonlari kacisli).
    # jq ifadesinde cift tirnak yok: PS 5.1 yerel komutlara gomulu tirnaklari bozuk geciriyor.
    if ($ReleasesFile) {
        # Yalnizca betik testi: ayni bicimde (satir basina bir surum nesnesi) hazir liste.
        $listText = [System.IO.File]::ReadAllText($ReleasesFile)
    } else {
        $list = Invoke-Native -Exe "gh" -Arguments @("api", "--paginate", "repos/$Repo/releases?per_page=100", "--jq", ".[] | {tag_name, draft, body}")
        if ($list.Code -ne 0) { Fail "Surum listesi alinamadi: $($list.Output)" }
        $listText = $list.Output
    }
    $releases = @($listText -split "`n" | Where-Object { $_.Trim().StartsWith("{") } | ForEach-Object { ConvertFrom-Json $_ })
    $android = @($releases | Where-Object { -not $_.draft -and $_.tag_name -like "android-v*" } |
        Sort-Object { Parse-Version ($_.tag_name -replace '^android-v', '') } -Descending)
    if ($android.Count -eq 0) {
        Write-Host "Onceki Android surumu yok: ilk yayin."
    } else {
        $prev = $android[0]
        $prevVersion = ($prev.tag_name -replace '^android-v', '')
        Write-Host "Onceki Android surumu: $($prev.tag_name)"
        if ((Parse-Version $version) -le (Parse-Version $prevVersion)) {
            $problems.Add("versionName $version, yayimdaki $prevVersion'dan buyuk degil")
        }
        # versionCode: notta "versionCode: N"; eski notlarda yoksa APK'yi indirip oku.
        $prevCode = $null
        if ($prev.body -match 'versionCode:\s*(\d+)') { $prevCode = [int]$Matches[1] }
        if ($null -eq $prevCode) {
            $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("gdpi-prev-" + [guid]::NewGuid().ToString("N"))
            New-Item -ItemType Directory -Path $tmp | Out-Null
            try {
                $d = Invoke-Native -Exe "gh" -Arguments @("release", "download", $prev.tag_name, "--repo", $Repo, "--pattern", "GoodbyeDPI-Android.apk", "--dir", $tmp)
                $prevApk = Join-Path $tmp "GoodbyeDPI-Android.apk"
                if ($d.Code -eq 0 -and (Test-Path $prevApk)) { $prevCode = Get-ApkVersionCode $prevApk }
            } finally {
                Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
            }
        }
        if ($null -eq $prevCode) {
            $problems.Add("$($prev.tag_name) surumunun versionCode'u okunamadi; artis dogrulanamadi")
        } else {
            Write-Host "Onceki versionCode: $prevCode"
            if ($versionCode -le $prevCode) {
                $problems.Add("versionCode $versionCode, yayimdaki $prevCode'dan buyuk degil (istemciler indirip reddeder)")
            }
        }
    }
}

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
Write-Host ""

if ($problems.Count -gt 0) {
    Write-Host "Yayimlanamaz:" -ForegroundColor Yellow
    $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    if ($remoteChecks) { Fail "$($problems.Count) sorun var; surum olusturulmadi." }
}

if (-not $Publish -or $DryRun) {
    if ($DryRun) {
        Write-Host "Kuru calisma: butun denetimler gecti, surum olusturulmadi."
    } else {
        Write-Host "Yayimlanmadi (yalnizca -Publish ile calisir; once -DryRun ile denetleyin)."
    }
    exit 0
}

$r = Invoke-Native -Exe "gh" -Arguments $ghArgs -Stream
if ($r.Code -ne 0) { Fail "gh release create basarisiz ($($r.Code))" }
Write-Host "Yayimlandi: $tag"
exit 0
