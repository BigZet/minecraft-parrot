<#
    Собирает viewerparrots-<версия>-installer.zip: установщик для стримера,
    JAR мода из build/libs и Fabric API с Modrinth (проверяется по SHA-512 из API).
#>
param(
    [string]$Version = '1.2.0',
    [string]$FabricApiVersion = '0.116.17+1.21.1'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$project = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$modJar = Join-Path $project "build/libs/viewerparrots-$Version.jar"
if (-not (Test-Path -LiteralPath $modJar)) { throw "Сначала соберите мод: нет $modJar" }

# Fabric API: берём ссылку и хэш из Modrinth API, кэшируем в build/.
$apiCache = Join-Path $project 'build/fabric-api'
New-Item -ItemType Directory -Path $apiCache -Force | Out-Null
$apiJar = Join-Path $apiCache "fabric-api-$FabricApiVersion.jar"
$versions = Invoke-RestMethod -UseBasicParsing -Uri 'https://api.modrinth.com/v2/project/fabric-api/version?loaders=%5B%22fabric%22%5D'
$file = ($versions | Where-Object { $_.version_number -eq $FabricApiVersion }).files | Where-Object { $_.primary } | Select-Object -First 1
if (-not $file) { throw "Fabric API $FabricApiVersion не найден на Modrinth." }
if (-not (Test-Path -LiteralPath $apiJar) -or (Get-FileHash -LiteralPath $apiJar -Algorithm SHA512).Hash -ne $file.hashes.sha512.ToUpperInvariant()) {
    Invoke-WebRequest -UseBasicParsing -Uri $file.url -OutFile $apiJar
    if ((Get-FileHash -LiteralPath $apiJar -Algorithm SHA512).Hash -ne $file.hashes.sha512.ToUpperInvariant()) { throw 'SHA-512 Fabric API не совпал.' }
}

$installer = Join-Path $project 'installer'
$entries = @(
    @{ Source = (Join-Path $installer 'Установить Viewer Parrots.cmd'); Entry = 'Установить Viewer Parrots.cmd' },
    @{ Source = (Join-Path $installer 'ИНСТРУКЦИЯ.txt');                Entry = 'ИНСТРУКЦИЯ.txt' },
    @{ Source = (Join-Path $installer 'install.ps1');                   Entry = 'installer/install.ps1' },
    @{ Source = $modJar;                                                Entry = "payload/viewerparrots-$Version.jar" },
    @{ Source = $apiJar;                                                Entry = "payload/fabric-api-$FabricApiVersion.jar" },
    @{ Source = (Join-Path $project 'LICENSE');                         Entry = 'payload/LICENSE' },
    @{ Source = (Join-Path $project 'THIRD_PARTY_NOTICES.md');          Entry = 'payload/THIRD_PARTY_NOTICES.md' }
)
foreach ($entry in $entries) {
    if (-not (Test-Path -LiteralPath $entry.Source -PathType Leaf)) { throw "Нет файла: $($entry.Source)" }
}

$outDir = Join-Path $project 'build/distributions'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$zipPath = Join-Path $outDir "viewerparrots-$Version-installer.zip"
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
$archive = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in $entries) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $entry.Source, $entry.Entry, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
Write-Host "Installer ZIP: $zipPath ($([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MB)"
