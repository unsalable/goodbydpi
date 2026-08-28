# GoodbyeDPI UI - dagitim paketi olusturur.
#
#   .\publish.ps1                     -> tek dosya, kendi kendine yeten (.NET gerekmez)
#   .\publish.ps1 -FrameworkDependent -> kucuk paket, hedefte .NET 8 Desktop ister
#
# Yayin ayarlari (win-x64 / SelfContained / PublishSingleFile) csproj icinde
# tanimli; bu betik yalnizca onlari calistirip sonucu dogrular.
#
# Cikti: dist\GoodbyeDPI-UI\

[CmdletBinding()]
param(
    [switch]$FrameworkDependent,
    [string]$OutputDir
)

$ErrorActionPreference = 'Stop'

# $PSScriptRoot parametre varsayilaninda her cagirma bicimi icin dolu gelmiyor;
# govdede hesapliyoruz ki paket yanlislikla C:\dist altina cikmasin.
$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $OutputDir) { $OutputDir = Join-Path $root 'dist\GoodbyeDPI-UI' }

$project = Join-Path $root 'src\GoodbyeDpiUI\GoodbyeDpiUI.csproj'

$blockedByElevated = @()

# Cikti klasorundeki dosyalari kilitleyen surecleri durdur.
#
# Yalnizca $OutputDir icinden calisanlara dokunuyoruz: baska bir dizindeki
# kurulumu ya da klasoru hic kilitlemeyen bir ornegi kapatmak icin sebep yok.
# Klasor henuz bossa kilitlenecek bir sey de yoktur, adimi tumden atliyoruz.
if (Test-Path (Join-Path $OutputDir 'GoodbyeDPI-UI.exe')) {
    $outFull = (Resolve-Path $OutputDir).Path

    foreach ($name in 'GoodbyeDPI-UI', 'goodbyedpi') {
        foreach ($p in @(Get-Process $name -ErrorAction SilentlyContinue)) {
            # Yukseltilmis bir surecin yolu normal yetkiyle okunamaz. Boyle bir
            # surec dosyayi kilitliyor OLABILIR ama genelde kilitlemez; pesin
            # olarak durdurmak ya da vazgecmek yerine not alip devam ediyoruz.
            # Yayin gercekten kilit yuzunden patlarsa asagida acik mesaj veriyoruz.
            $path = try { $p.Path } catch { $null }

            if (-not $path) { $script:blockedByElevated += $p; continue }
            if (-not $path.StartsWith($outFull, [StringComparison]::OrdinalIgnoreCase)) { continue }

            try { $p.Kill(); $p.WaitForExit(3000) }
            catch { $script:blockedByElevated += $p }
        }
    }
}

# Klasoru silmiyoruz: goodbyedpi.exe bir surecten dolayi kilitli olabiliyor ve
# zaten ayni dosya. dotnet publish degisenlerin uzerine yaziyor.
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$publishArgs = @('publish', $project, '-c', 'Release', '-o', $OutputDir, '--nologo')

if ($FrameworkDependent) {
    # csproj varsayilanlarini bu calistirma icin geri al.
    $publishArgs += @(
        '--self-contained', 'false'
        '-p:PublishSingleFile=false'
        '-p:IncludeNativeLibrariesForSelfExtract=false'
        '-p:EnableCompressionInSingleFile=false'
    )
}

& dotnet @publishArgs

if ($LASTEXITCODE -ne 0) {
    if ($blockedByElevated.Count -gt 0) {
        Write-Host ""
        Write-Host "Yayinlama basarisiz. Su surecler yonetici olarak calisiyor ve" -ForegroundColor Yellow
        Write-Host "cikti dosyalarini kilitliyor olabilir:"
        $blockedByElevated | ForEach-Object { Write-Host "  $($_.ProcessName) (PID $($_.Id))" }
        Write-Host "Tepsi simgesindeki panelden Cikis yapip tekrar deneyin."
    }
    throw "Yayinlama basarisiz (kod $LASTEXITCODE)."
}

# ---------------------------------------------------------------- dogrulama

$exe = Join-Path $OutputDir 'GoodbyeDPI-UI.exe'
if (-not (Test-Path $exe)) { throw "Cikti bulunamadi: $exe" }

# goodbyedpi.exe ve WinDivert tek dosyaya gomulemez; gercek dosya olarak
# durmalari sart, yoksa uygulama baglanamaz.
$runtime = Join-Path $OutputDir 'Runtime\x86_64'
$required = 'goodbyedpi.exe', 'WinDivert.dll', 'WinDivert64.sys'
$missing = $required | Where-Object { -not (Test-Path (Join-Path $runtime $_)) }
if ($missing) { throw "Runtime dosyalari eksik: $($missing -join ', ')" }

$total = (Get-ChildItem $OutputDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
$exeSize = (Get-Item $exe).Length / 1MB
$looseDlls = @(Get-ChildItem $OutputDir -Filter *.dll -File)

Write-Host ""
Write-Host "Hazir: $OutputDir" -ForegroundColor Green
Write-Host ("  GoodbyeDPI-UI.exe : {0:N1} MB" -f $exeSize)
Write-Host ("  Toplam klasor     : {0:N1} MB" -f $total)

if (-not $FrameworkDependent) {
    if ($looseDlls.Count -gt 0) {
        Write-Host "  UYARI: yanina $($looseDlls.Count) adet .dll dusmus, tek dosya olmadi." -ForegroundColor Yellow
    } else {
        Write-Host "  Tek dosya: exe'nin yaninda yalnizca Runtime klasoru var." -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Paylasirken KLASORUN TAMAMINI verin: GoodbyeDPI-UI.exe tek basina yetmez,"
Write-Host "yanindaki Runtime klasoru olmadan baglanti kurulamaz."
