param(
    [string]$Instance = '1.21.1-fabric-dev'
)
$ErrorActionPreference = 'Stop'
if ($Instance -match '[\\/]' -or $Instance -in @('.', '..')) {
    throw 'Instance must be a Prism instance directory name.'
}
$projectDirectory = Split-Path -Parent $PSScriptRoot
$modDirectory = Join-Path $env:APPDATA "PrismLauncher\instances\$Instance\minecraft\mods"
if (-not (Test-Path -LiteralPath $modDirectory -PathType Container)) {
    throw "Prism instance mods directory does not exist: $modDirectory"
}
$releaseJar = Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'build\libs') -Filter 'viewerparrots-*.jar' |
    Where-Object { $_.Name -notmatch '-sources|-dev' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $releaseJar) { throw 'Build the release JAR first with gradlew.bat build.' }
$backupDirectory = Join-Path (Split-Path -Parent $modDirectory) ('viewerparrots-backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$oldJars = @(Get-ChildItem -LiteralPath $modDirectory -Filter 'viewerparrots-*.jar')
if ($oldJars.Count -gt 0) {
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    foreach ($oldJar in $oldJars) {
        Move-Item -LiteralPath $oldJar.FullName -Destination (Join-Path $backupDirectory $oldJar.Name)
    }
}
Copy-Item -LiteralPath $releaseJar.FullName -Destination (Join-Path $modDirectory $releaseJar.Name)
Write-Host "Installed $($releaseJar.Name) in $modDirectory"
if ($oldJars.Count -gt 0) { Write-Host "Previous JARs backed up in $backupDirectory" }
