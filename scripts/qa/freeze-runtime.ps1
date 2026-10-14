$ErrorActionPreference = 'Stop'
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$snapshotDirectory = Join-Path $projectDirectory "artifacts/qa-runtime/$stamp"
if (Test-Path -LiteralPath $snapshotDirectory) { throw 'Runtime snapshot already exists.' }
New-Item -ItemType Directory -Path $snapshotDirectory -Force > $null
$replacements = @{}
foreach ($category in @('classes/java', 'resources')) {
    $destinationParent = Join-Path $snapshotDirectory $category
    New-Item -ItemType Directory -Path $destinationParent -Force > $null
    foreach ($sourceSet in @('main', 'client', 'gametest')) {
        $originalDirectory = Join-Path $projectDirectory "build/$category/$sourceSet"
        if (-not (Test-Path -LiteralPath $originalDirectory -PathType Container)) { throw "Compile first: $originalDirectory" }
        Copy-Item -LiteralPath $originalDirectory -Destination $destinationParent -Recurse
        $replacements[$originalDirectory] = Join-Path $destinationParent $sourceSet
    }
}
function Convert-ToFrozenPath([string]$Value) {
    foreach ($original in $replacements.Keys) {
        $Value = $Value.Replace($original, $replacements[$original])
        $Value = $Value.Replace($original.Replace('\', '/'), $replacements[$original].Replace('\', '/'))
    }
    return $Value
}
$configuration = [IO.File]::ReadAllText((Join-Path $projectDirectory '.gradle/loom-cache/launch.cfg'))
$frozenConfiguration = Join-Path $snapshotDirectory 'launch.cfg'
[IO.File]::WriteAllText($frozenConfiguration, (Convert-ToFrozenPath $configuration))
$arguments = @{}
foreach ($role in @('Server', 'Client', 'Observer')) {
    $originalArguments = Join-Path $projectDirectory "build/loom-cache/argFiles/runAcceptance$role"
    $lines = [IO.File]::ReadAllLines($originalArguments)
    if ($lines[0] -notin @('-classpath', '-cp')) { throw 'Unexpected Loom launch argument format.' }
    $classpath = (Convert-ToFrozenPath $lines[1].Trim('"')).Replace('\', '/')
    $frozenArguments = Join-Path $snapshotDirectory "$role.args"
    [IO.File]::WriteAllText($frozenArguments, "-cp`n`"$classpath`"`n")
    $arguments[$role] = $frozenArguments
}
$classFiles = @(Get-ChildItem -LiteralPath (Join-Path $snapshotDirectory 'classes/java/main') -Recurse -Filter '*.class')
if ($classFiles.Count -eq 0) { throw 'Compiled production classes are missing.' }
$hashInput = ($classFiles | Sort-Object FullName | ForEach-Object { $_.FullName.Substring($snapshotDirectory.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }) -join "`n"
$classTreeHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($hashInput)))
$result = [ordered]@{
    directory = $snapshotDirectory
    configuration = $frozenConfiguration
    arguments = $arguments
    mainClassCount = $classFiles.Count
    mainClassTreeSha256 = $classTreeHash
    created = (Get-Date -Format o)
}
$manifest = $result | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $snapshotDirectory 'manifest.json'), $manifest)
[IO.File]::WriteAllText((Join-Path $projectDirectory 'artifacts/qa-runtime/current.json'), $manifest)
$manifest
