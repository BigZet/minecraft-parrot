param()
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$script = Join-Path $repository 'scripts/package-release.ps1'
$testRoot = Join-Path $repository ('artifacts/package-release-tests/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$fixture = Join-Path $testRoot 'fixture'
New-Item -ItemType Directory -Path $fixture -Force > $null

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { throw "Assertion failed: $message" }
}
function Write-Fixture([string]$relative, [string]$content) {
    $target = Join-Path $fixture $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force > $null
    [IO.File]::WriteAllText($target, $content, [Text.UTF8Encoding]::new($false))
}
function Expect-Failure([scriptblock]$action, [string]$pattern) {
    $failure = $null
    try { & $action > $null } catch { $failure = $_.Exception.Message }
    Assert-True ($null -ne $failure -and $failure -match $pattern) "Expected failure matching '$pattern', actual '$failure'"
}
function Read-Archive([string]$path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        $entries = @{}
        foreach ($entry in $archive.Entries) {
            $stream = $entry.Open()
            $copy = [IO.MemoryStream]::new()
            try { $stream.CopyTo($copy); $entries[$entry.FullName] = $copy.ToArray() }
            finally { $stream.Dispose(); $copy.Dispose() }
        }
        return $entries
    } finally { $archive.Dispose() }
}

foreach ($name in @('README.md','LICENSE','build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','.gitignore','.gitattributes','viewer_parrots_technical_spec.md')) {
    Write-Fixture $name "fixture $name"
}
Write-Fixture 'gradle/wrapper/gradle-wrapper.jar' 'fixture wrapper binary'
Write-Fixture 'gradle/wrapper/gradle-wrapper.properties' 'distributionUrl=https://services.gradle.org/distributions/gradle-9.5.1-bin.zip'
Write-Fixture 'src/main/java/example/NewUntracked.java' 'class NewUntracked {}'
Write-Fixture 'src/main/resources/assets/viewerparrots/lang/ru_ru.json' '{}'
Write-Fixture 'scripts/verify.ps1' 'Write-Output fixture'
Write-Fixture 'scripts/qa/check-benchmark.py' (Get-Content -LiteralPath (Join-Path $repository 'scripts/qa/check-benchmark.py') -Raw)
Write-Fixture 'docs/verification-2026-10-13.md' 'PENDING: final benchmark'
Write-Fixture 'docs/quickstart-ru.md' 'Русский быстрый запуск'
Write-Fixture 'examples/viewerparrots.json' '{"schemaVersion":1}'
Write-Fixture '.github/workflows/build.yml' 'name: fixture'
Write-Fixture 'artifacts/verification/20261013/final/final-clean-build.log' 'BUILD SUCCESSFUL'
Write-Fixture 'artifacts/visual-proof/commands.jsonl' '{"command":"vp sit fixture"}'
foreach ($name in @('.serena/account.json','accounts.json','assets/objects/mojang.bin','.gradle/cache.class','build/minecraft-server.jar','scripts/__pycache__/cache.pyc')) {
    Write-Fixture $name 'excluded fixture content'
}
& git -C $fixture init --quiet
if ($LASTEXITCODE -ne 0) { throw 'Cannot initialize fixture Git repository' }
& git -C $fixture add README.md
if ($LASTEXITCODE -ne 0) { throw 'Cannot mark fixture README as tracked' }
Write-Fixture 'jar-input/fabric.mod.json' '{"id":"viewerparrots","version":"1.0.0"}'
$jar = Join-Path $fixture 'build/libs/viewerparrots-1.0.0.jar'
New-Item -ItemType Directory -Path (Split-Path -Parent $jar) -Force > $null
[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $fixture 'jar-input'), $jar)
$hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash

Assert-True (Test-Path -LiteralPath $script) 'Packaging helper exists'
$output = Join-Path $fixture 'artifacts/release-packages/draft'
$lockedLog = [IO.File]::Open((Join-Path $fixture 'artifacts/verification/20261013/final/final-clean-build.log'), [IO.FileMode]::Open, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
try { $receipt = & $script -ProjectDirectory $fixture -OutputDirectory $output -ExpectedJarSha256 $hash -AdditionalEvidence @('artifacts/visual-proof/commands.jsonl') }
finally { $lockedLog.Dispose() }
$binary = Read-Archive $receipt.BinaryArchive
$source = Read-Archive $receipt.SourceArchive
$evidence = Read-Archive $receipt.EvidenceArchive
Assert-True ([Text.Encoding]::UTF8.GetString($evidence['artifacts/verification/20261013/final/final-clean-build.log']) -eq 'BUILD SUCCESSFUL') 'Open-for-write evidence log is captured read-only'
Assert-True ($evidence.ContainsKey('artifacts/visual-proof/commands.jsonl')) 'Explicit JSONL command protocol is packaged as evidence'
foreach ($entry in @('mods/viewerparrots-1.0.0.jar','config/viewerparrots.json','QUICKSTART-RU.md','LICENSE','docs/verification-2026-10-13.md','PACKAGE-MANIFEST.json')) {
    Assert-True ($binary.ContainsKey($entry)) "Binary contains $entry"
}
foreach ($entry in @('gradlew','gradlew.bat','gradle/wrapper/gradle-wrapper.jar','src/main/java/example/NewUntracked.java','src/main/resources/assets/viewerparrots/lang/ru_ru.json','.github/workflows/build.yml','scripts/verify.ps1','SOURCE-MANIFEST.json')) {
    Assert-True ($source.ContainsKey($entry)) "Source contains $entry"
}
Assert-True (@($source.Keys | Where-Object { $_ -match '^(build|assets|\.serena|\.gradle|\.git)/|^accounts\.json$' }).Count -eq 0) 'No runtime, account, cache or Mojang input is packaged'
Assert-True (-not $source.ContainsKey('scripts/__pycache__/cache.pyc')) 'QA Python bytecode cache remains outside the source archive'
$manifest = [Text.Encoding]::UTF8.GetString($source['SOURCE-MANIFEST.json']) | ConvertFrom-Json
Assert-True ($manifest.status -eq 'DRAFT') 'Pending evidence produces a draft'
Assert-True (@($manifest.inputs | Where-Object { $_.path -eq 'README.md' -and $_.gitStatus -eq 'tracked' }).Count -eq 1) 'Tracked provenance is retained'
Assert-True (@($manifest.inputs | Where-Object { $_.path -eq 'src/main/java/example/NewUntracked.java' -and $_.gitStatus -eq 'untracked' }).Count -eq 1) 'Current untracked code is included explicitly'
Assert-True ($manifest.releaseJarSha256 -eq $hash) 'Exact release JAR identity is recorded'
$binaryJarHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($binary['mods/viewerparrots-1.0.0.jar']))
Assert-True ($binaryJarHash -eq $hash) 'Archived binary has the expected hash'

$rejected = Join-Path $fixture 'artifacts/release-packages/rejected'
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory $rejected -ExpectedJarSha256 ('0' * 64) } 'SHA-256'
Assert-True (-not (Test-Path -LiteralPath $rejected)) 'Wrong JAR hash is rejected before staging'
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory $rejected -ExpectedJarSha256 $hash -Final } 'PENDING'
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory (Join-Path $fixture 'build/live') -ExpectedJarSha256 $hash } 'release-packages'
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory $output -ExpectedJarSha256 $hash } 'already exists'

Write-Fixture 'src/main/resources/accounts.json' 'forbidden fixture'
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory $rejected -ExpectedJarSha256 $hash } 'Unsafe source input'
Move-Item -LiteralPath (Join-Path $fixture 'src/main/resources/accounts.json') -Destination (Join-Path $fixture 'forbidden-fixture.json')
Write-Fixture 'docs/verification-2026-10-13.md' 'All required acceptance gates completed.'
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory (Join-Path $fixture 'artifacts/release-packages/final') -ExpectedJarSha256 $hash -Final } 'Final evidence is missing'
foreach ($relative in $receipt.MissingEvidenceInputs) { Write-Fixture $relative '{}' }
Write-Fixture 'build/acceptance-server/benchmark-results.json' '[]'
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory (Join-Path $fixture 'artifacts/release-packages/final') -ExpectedJarSha256 $hash -Final } 'nine complete benchmark'
$shortRuns = @(foreach ($scenario in @('baseline','vanilla50','custom50')) { foreach ($number in 1..3) { @{ scenario=$scenario; run=$number; warmupTicks=100; sampleTicks=600 } } })
Write-Fixture 'build/acceptance-server/benchmark-results.json' ($shortRuns | ConvertTo-Json)
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory (Join-Path $fixture 'artifacts/release-packages/final') -ExpectedJarSha256 $hash -Final } 'full-duration'
$runs = @(foreach ($scenario in @('baseline','vanilla50','custom50')) { foreach ($number in 1..3) { @{ scenario=$scenario; run=$number; warmupTicks=6000; sampleTicks=12000 } } })
Write-Fixture 'build/acceptance-server/benchmark-results.json' ($runs | ConvertTo-Json)
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory (Join-Path $fixture 'artifacts/release-packages/final') -ExpectedJarSha256 $hash -Final } 'Benchmark acceptance failed'
$runs = @(foreach ($scenario in @('baseline','vanilla50','custom50')) { foreach ($number in 1..3) { @{ scenario=$scenario; run=$number; warmupTicks=6000; sampleTicks=12000; storageStayedReady=$true; minimumLoadedEntities=$(if($scenario -eq 'baseline'){0}else{50}); tps=20; tickP95Ms=$(if($scenario -eq 'baseline'){1}else{3}); schedulerP95Ms=0.5 } } })
Write-Fixture 'build/acceptance-server/benchmark-results.json' ($runs | ConvertTo-Json)
$failedTps = @($runs | ForEach-Object { $item=@{} + $_; $item.tps=10; $item })
Write-Fixture 'build/acceptance-server/benchmark-results.json' ($failedTps | ConvertTo-Json)
Expect-Failure { & $script -ProjectDirectory $fixture -OutputDirectory (Join-Path $fixture 'artifacts/release-packages/final') -ExpectedJarSha256 $hash -Final } 'Benchmark acceptance failed'
$alternativeResults = 'artifacts/final-benchmark/fixture/benchmark-results.json'
Write-Fixture $alternativeResults ($runs | ConvertTo-Json)
# The historical default remains invalid: only the explicitly selected fresh report may pass.
$final = & $script -ProjectDirectory $fixture -OutputDirectory (Join-Path $fixture 'artifacts/release-packages/final') -ExpectedJarSha256 $hash -BenchmarkResults $alternativeResults -Final
Assert-True ($final.Status -eq 'FINAL') 'Explicit final mode uses the completed verification report'
$finalEvidence = Read-Archive $final.EvidenceArchive
Assert-True ($finalEvidence.ContainsKey($alternativeResults)) 'Evidence ZIP preserves the explicitly selected benchmark report path'
Assert-True (-not $finalEvidence.ContainsKey('build/acceptance-server/benchmark-results.json')) 'Discarded historical default is not substituted for selected benchmark evidence'
Write-Host "PASS: draft/final archives, provenance, exact JAR identity, exclusion, checksum/finalization/output guards. Fixture: $testRoot"
