param(
    [string]$ProjectDirectory = (Split-Path -Parent $PSScriptRoot),
    [string]$Version = '1.0.0',
    [string]$ExpectedJarSha256 = '7B552E520BCB85C8027A9CD2AF2FB9D2481F197549D760D1402854D9F208625F',
    [string]$Python = 'python',
    [string]$BenchmarkResults = 'build/acceptance-server/benchmark-results.json',
    [string]$OutputDirectory,
    [string[]]$AdditionalEvidence = @(),
    [switch]$Final
)
$ErrorActionPreference = 'Stop'
$ProjectDirectory = [IO.Path]::GetFullPath($ProjectDirectory)
$BenchmarkResults = $BenchmarkResults.Replace('\','/')
if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Invalid release version.' }
if ($ExpectedJarSha256 -notmatch '^[A-Fa-f0-9]{64}$') { throw 'Expected SHA-256 must contain 64 hexadecimal characters.' }
$ExpectedJarSha256 = $ExpectedJarSha256.ToUpperInvariant()
$packageRoot = Join-Path $ProjectDirectory 'artifacts/release-packages'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $packageRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
} elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $ProjectDirectory $OutputDirectory
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $OutputDirectory.StartsWith($packageRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must be a new subdirectory of artifacts/release-packages; build and live QA directories are never output targets.'
}
if (Test-Path -LiteralPath $OutputDirectory) { throw "Output directory already exists: $OutputDirectory" }

function Get-InputPath([string]$relative) {
    if ([IO.Path]::IsPathRooted($relative) -or $relative -match '[\x00-\x1F]' -or ($relative -split '[/\\]') -contains '..') {
        throw "Unsafe input path: $relative"
    }
    $absolute = [IO.Path]::GetFullPath((Join-Path $ProjectDirectory $relative))
    if (-not $absolute.StartsWith($ProjectDirectory.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Input escapes the project: $relative"
    }
    $cursor = $absolute
    while ($cursor -ne $ProjectDirectory) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse-point input is forbidden: $relative" }
        }
        $cursor = Split-Path -Parent $cursor
    }
    return $absolute
}
function Write-Json([string]$path, $value) {
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 12) + "`n", [Text.UTF8Encoding]::new($false))
}
function Copy-CheckedFile([string]$relative, [string]$stage, [string]$targetRelative, [string]$gitStatus, [switch]$SharedSnapshot) {
    $inputPath = Get-InputPath $relative
    if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) { throw "Required input is missing: $relative" }
    $destination = Join-Path $stage $targetRelative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force > $null
    if ($SharedSnapshot) {
        # Redirected stdout can remain open for writing. Read exactly its current length,
        # sharing the writer, and store the receipt snapshot only in our fresh output.
        $reader = [IO.File]::Open($inputPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $writer = $null
        try {
            $snapshotBytes = $reader.Length
            $remaining = $snapshotBytes
            $writer = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            $buffer = [byte[]]::new(65536)
            while ($remaining -gt 0) {
                $read = $reader.Read($buffer, 0, [int][Math]::Min($remaining, $buffer.Length))
                if ($read -eq 0) { throw "Evidence log shrank during snapshot: $relative" }
                $writer.Write($buffer, 0, $read)
                $remaining -= $read
            }
        } finally { $reader.Dispose(); if ($writer) { $writer.Dispose() } }
        $hashBefore = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    } else {
        $snapshotBytes = $null
        $hashBefore = (Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash
        Copy-Item -LiteralPath $inputPath -Destination $destination
    }
    $hashAfter = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    if ($hashBefore -ne $hashAfter) { throw "Input changed during copying: $relative; rerun with stable inputs." }
    return [pscustomobject]@{ path = $relative.Replace('\','/'); target = $targetRelative.Replace('\','/'); gitStatus = $gitStatus; bytes = (Get-Item -LiteralPath $destination).Length; sha256 = $hashAfter; sharedLogSnapshotBytes = $snapshotBytes }
}
function Assert-SafeSource([string]$relative) {
    $normalized = $relative.Replace('\','/')
    if ($normalized -match '(^|/)(\.serena|\.git|\.gradle|build|out|run|libraries|screenshots|crash-reports|logs|__pycache__)(/|$)' -or
        $normalized -match '(^|/)(accounts?|launcher_accounts)(\.[^/]+)?$' -or
        $normalized -match '\.(class|exe|dll|mca|zip|tar|gz|bin)$' -or
        ($normalized -match '\.jar$' -and $normalized -ne 'gradle/wrapper/gradle-wrapper.jar') -or
        ($normalized -match '(^|/)assets/' -and $normalized -notmatch '^src/main/resources/assets/viewerparrots/')) {
        throw "Unsafe source input: $relative"
    }
    $null = Get-InputPath $relative
}
function Assert-SafeEvidence([string]$relative) {
    $normalized = $relative.Replace('\','/')
    if ($normalized -notmatch '^(artifacts|build|docs|scripts|\.github)/' -or
        $normalized -match '(^|/)(\.serena|\.git|\.gradle|accounts?|launcher_accounts|libraries|world|worlds|region|entities|saves|assets)(/|\.|$)' -or
        $normalized -notmatch '\.(json|jsonl|xml|log|md|png|txt|ps1|py|yml|yaml|html|css|js)$') {
        throw "Unsafe evidence input: $relative"
    }
    $null = Get-InputPath $relative
}

$jarRelative = "build/libs/viewerparrots-$Version.jar"
$jar = Get-InputPath $jarRelative
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Release JAR is missing; this script does not invoke Gradle: $jarRelative" }
if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $ExpectedJarSha256) { throw 'Release JAR SHA-256 does not match the approved final binary.' }
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $qaEntries = @($archive.Entries | Where-Object { $_.FullName -match 'gametest|AcceptanceHarness|CrashAcceptance|RegistryBootAcceptance|(^|/)qa/' })
    if ($qaEntries.Count -gt 0) { throw 'Release JAR contains QA/GameTest entries.' }
    $jarEntries = $archive.Entries.Count
} finally { $archive.Dispose() }
$reportRelative = 'docs/verification-2026-10-13.md'
Assert-SafeEvidence $BenchmarkResults
$report = Get-InputPath $reportRelative
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw 'Verification report is missing.' }
$reportHash = (Get-FileHash -LiteralPath $report -Algorithm SHA256).Hash
$reportText = [IO.File]::ReadAllText($report)
if ($Final -and $reportText -match '(?i)\bPENDING\b') { throw 'Verification report still contains PENDING; final packaging is refused.' }
$status = if ($Final) { 'FINAL' } else { 'DRAFT' }

$rootFiles = @('README.md','LICENSE','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','.gitignore','.gitattributes','viewer_parrots_technical_spec.md')
$sourceRoots = @('src','scripts','docs','examples','.github','gradle/wrapper')
$pathspecs = $rootFiles + $sourceRoots
$tracked = @(& git -C $ProjectDirectory -c core.quotePath=false ls-files --cached -- @pathspecs)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read tracked packaging inputs; run from a Git checkout.' }
$untracked = @(& git -C $ProjectDirectory -c core.quotePath=false ls-files --others --exclude-standard -- @pathspecs)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read untracked packaging inputs.' }
$gitInputs = @($tracked + $untracked | Sort-Object -Unique)
$excludedGitInputs = @($gitInputs | Where-Object { $_ -match '(^|/)__pycache__/|\.py[co]$' })
$sourceInputs = @($gitInputs | Where-Object { $_ -notin $excludedGitInputs })
foreach ($relative in $sourceInputs) { Assert-SafeSource $relative }
foreach ($required in $rootFiles + @('gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties','examples/viewerparrots.json','docs/quickstart-ru.md',$reportRelative)) {
    if ($required -notin $sourceInputs) { throw "Required source input is absent from the explicit Git allowlist: $required" }
}

# Exact final-evidence files only. No recursive world/runtime/cache copy.
$evidenceInputs = @(
    $reportRelative, 'docs/acceptance-matrix.md', 'scripts/install-prism.ps1', '.github/workflows/build.yml',
    'artifacts/qa-runtime/20261013-204512/manifest.json',
    'artifacts/verification/20261013/final/final-clean-build.log',
    'artifacts/verification/20261013/hardware-final-planned.json',
    'artifacts/verification/20261013/benchmark-affinity-final.json',
    'artifacts/verification/20261013/discarded-unconfined-benchmark/reason.json',
    'artifacts/verification/20261013/discarded-unconfined-benchmark/benchmark-results.json',
    'artifacts/final-benchmark/20261013-2220/runtime/manifest.json',
    'artifacts/final-benchmark/20261013-2220/full-launch-proof.json',
    'artifacts/final-benchmark/20261013-2220/full/hardware-affinity.json',
    'artifacts/final-benchmark/20261013-2220/full/benchmark-arena.json',
    'artifacts/final-benchmark/20261013-2220/full/server-stdout.log',
    'artifacts/final-benchmark/20261013-2220/negative-count-proof.json',
    'artifacts/final-benchmark/20261013-2220/quick-fixture-proof.json',
    'artifacts/verification/20261013/final/gametest-results.xml', 'build/reports/verification/recovery-100-cycles.json',
    'artifacts/acceptance-storage/20261013-async-io/gametest-leak-negative.xml',
    'artifacts/process-crash-final-20261013-2048/process-matrix-results.json',
    'artifacts/registry-bootstrap-formal-4core-20261013-2101/registry-bootstrap-results.json',
    'build/acceptance-server/crud-latency-results.json', $BenchmarkResults,
    'artifacts/production-server-smoke/proof.json', 'artifacts/production-server-smoke/status.txt',
    'artifacts/production-server-smoke/production-stdout.log',
    'artifacts/verification/20261013/final-integrated/receipt.json',
    'artifacts/verification/20261013/final-integrated/integrated-final.log',
    'artifacts/verification/20261013/final-integrated/2026-10-13_21.33.51.png',
    'artifacts/verification/20261013/final-integrated/2026-10-13_21.34.25.png',
    'artifacts/verification/20261013/final-integrated/2026-10-13_21.34.34.png',
    'artifacts/disk-lifecycle-20261013-2142/disk-lifecycle-results.json',
    'artifacts/verification/20261013/network-smoke-transcript.txt',
    'artifacts/verification/20261013/2026-10-13_18.41.42.png',
    'artifacts/verification/20261013/pre-final-async/build_network-final-transcript.txt',
    'build/acceptance-server/final-stdout.log', 'build/acceptance-observer/final-stdout.log', 'build/acceptance-client/final-stdout.log'
) + $AdditionalEvidence
foreach ($reportDirectory in @('build/test-results/test','build/reports/tests/test')) {
    $path = Get-InputPath $reportDirectory
    if (Test-Path -LiteralPath $path -PathType Container) {
        $evidenceInputs += @(Get-ChildItem -LiteralPath $path -Recurse -File | Where-Object { $_.Extension -in @('.xml','.html','.css','.js') } | ForEach-Object { [IO.Path]::GetRelativePath($ProjectDirectory, $_.FullName).Replace('\','/') })
    }
}
$evidenceInputs = @($evidenceInputs | Sort-Object -Unique)
foreach ($relative in $evidenceInputs) { Assert-SafeEvidence $relative }
$missingEvidence = @($evidenceInputs | Where-Object { -not (Test-Path -LiteralPath (Get-InputPath $_) -PathType Leaf) })
if ($Final -and $missingEvidence.Count -gt 0) { throw "Final evidence is missing: $($missingEvidence -join ', ')" }
$benchmarkVerdict = $null
if ($Final) {
    $runs = @(Get-Content -LiteralPath (Get-InputPath $BenchmarkResults) -Raw | ConvertFrom-Json)
    if ($runs.Count -ne 9) { throw 'Final packaging requires nine complete benchmark results.' }
    $keys = @($runs | ForEach-Object { "$($_.scenario):$($_.run)" } | Sort-Object -Unique)
    $expectedKeys = @(foreach ($scenario in @('baseline','vanilla50','custom50')) { foreach ($number in 1..3) { "${scenario}:$number" } })
    if (@(Compare-Object $keys $expectedKeys).Count -ne 0 -or @($runs | Where-Object { $_.warmupTicks -ne 6000 -or $_.sampleTicks -ne 12000 }).Count -gt 0) {
        throw 'Final packaging requires three full-duration benchmark windows of every scenario.'
    }
    $checker = Get-InputPath 'scripts/qa/check-benchmark.py'
    if (-not (Test-Path -LiteralPath $checker -PathType Leaf)) { throw 'Benchmark acceptance checker is missing.' }
    $checkerOutput = @(& $Python $checker (Get-InputPath $BenchmarkResults) 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Benchmark acceptance failed: $($checkerOutput -join [Environment]::NewLine)" }
    $benchmarkVerdict = ($checkerOutput -join [Environment]::NewLine) | ConvertFrom-Json
    if ($benchmarkVerdict.passed -ne $true) { throw 'Benchmark acceptance failed.' }
}

New-Item -ItemType Directory -Path $OutputDirectory > $null
$binaryStage = Join-Path $OutputDirectory 'binary'
$sourceStage = Join-Path $OutputDirectory 'source'
$evidenceStage = Join-Path $OutputDirectory 'evidence'
foreach ($stage in @($binaryStage,$sourceStage,$evidenceStage)) { New-Item -ItemType Directory -Path $stage > $null }
$binaryInputs = @(
    (Copy-CheckedFile $jarRelative $binaryStage "mods/viewerparrots-$Version.jar" 'generated-release'),
    (Copy-CheckedFile 'examples/viewerparrots.json' $binaryStage 'config/viewerparrots.json' 'source-example'),
    (Copy-CheckedFile 'docs/quickstart-ru.md' $binaryStage 'QUICKSTART-RU.md' 'source-document'),
    (Copy-CheckedFile 'LICENSE' $binaryStage 'LICENSE' 'tracked'),
    (Copy-CheckedFile $reportRelative $binaryStage $reportRelative 'source-document')
)
$sourceManifest = @(foreach ($relative in $sourceInputs) { Copy-CheckedFile $relative $sourceStage $relative $(if ($relative -in $tracked) { 'tracked' } else { 'untracked' }) })
$evidenceManifest = @(foreach ($relative in $evidenceInputs) { if ($relative -notin $missingEvidence) { Copy-CheckedFile $relative $evidenceStage $relative 'explicit-evidence' -SharedSnapshot:($relative -match '\.(log|jsonl)$') } })
foreach ($stage in @($binaryStage,$sourceStage,$evidenceStage)) {
    if ((Get-FileHash -LiteralPath (Join-Path $stage $reportRelative) -Algorithm SHA256).Hash -ne $reportHash) { throw 'Verification report changed during packaging; rerun after the final document is stable.' }
}

$missingLinks = @()
foreach ($link in [regex]::Matches($reportText,'\]\(([^)]+)\)')) {
    $target = $link.Groups[1].Value
    if ($target -match '^(https?://|#)') { continue }
    $targetPath = [IO.Path]::GetFullPath((Join-Path (Join-Path $evidenceStage 'docs') $target))
    if (-not $targetPath.StartsWith($evidenceStage + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $targetPath)) { $missingLinks += $target }
}
if ($Final -and $missingLinks.Count -gt 0) { throw "Verification links missing from evidence ZIP: $($missingLinks -join ', '); add explicit -AdditionalEvidence inputs." }
$common = @{ version = $Version; status = $status; createdAt = (Get-Date -Format o); releaseJarSha256 = $ExpectedJarSha256; releaseJarEntries = $jarEntries; qaJarEntries = 0; verificationReportSha256 = $reportHash; acceptanceSource = $reportRelative; benchmarkResults = $BenchmarkResults; benchmarkVerdict = $benchmarkVerdict; archiveLayout = 'Extract evidence ZIP into the binary/source package root to resolve repository-relative verification links.'; finalizationScope = 'Packaging guards verify report readiness, expected binary, full benchmark acceptance and evidence-link closure; remaining acceptance assertions belong to the verification report.' }
Write-Json (Join-Path $binaryStage 'PACKAGE-MANIFEST.json') ($common + @{ inputs = $binaryInputs })
Write-Json (Join-Path $sourceStage 'SOURCE-MANIFEST.json') ($common + @{ inputs = $sourceManifest; trackedInputs = @($sourceManifest | Where-Object { $_.gitStatus -eq 'tracked' }).Count; untrackedInputs = @($sourceManifest | Where-Object { $_.gitStatus -eq 'untracked' }).Count; excludedGitInputs = $excludedGitInputs; exclusions = @('.serena','.git','.gradle','accounts','Mojang assets/binaries','build/runtime outputs','Python bytecode caches') })
Write-Json (Join-Path $evidenceStage 'EVIDENCE-MANIFEST.json') ($common + @{ inputs = $evidenceManifest; missingInputs = $missingEvidence; missingVerificationLinks = $missingLinks })
$suffix = if ($Final) { '' } else { '-draft' }
$archives = @{}
foreach ($kind in @('binary','source','evidence')) {
    $archivePath = Join-Path $OutputDirectory "viewerparrots-$Version-$kind$suffix.zip"
    [IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $OutputDirectory $kind), $archivePath, [IO.Compression.CompressionLevel]::Fastest, $false)
    $archives[$kind] = [pscustomobject]@{ path = $archivePath; sha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash; bytes = (Get-Item -LiteralPath $archivePath).Length }
}
$receipt = [pscustomobject]@{ Status = $status; OutputDirectory = $OutputDirectory; BinaryArchive = $archives.binary.path; SourceArchive = $archives.source.path; EvidenceArchive = $archives.evidence.path; Archives = $archives; ReleaseJarSha256 = $ExpectedJarSha256; SourceInputs = $sourceManifest.Count; TrackedInputs = @($sourceManifest | Where-Object { $_.gitStatus -eq 'tracked' }).Count; UntrackedInputs = @($sourceManifest | Where-Object { $_.gitStatus -eq 'untracked' }).Count; ExcludedGitInputs = $excludedGitInputs; EvidenceInputs = $evidenceManifest.Count; MissingEvidenceInputs = $missingEvidence; MissingVerificationLinks = $missingLinks }
Write-Json (Join-Path $OutputDirectory 'package-receipt.json') $receipt
return $receipt
