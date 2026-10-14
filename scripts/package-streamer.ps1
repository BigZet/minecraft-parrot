param([string]$Version = '1.0.0')
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') {
    throw 'Version must be a semantic version such as 1.0.0.'
}

$projectDirectory = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$jarName = "viewerparrots-$Version.jar"
$jarPath = Join-Path $projectDirectory "build/libs/$jarName"
$inputs = @(
    @{ Source = $jarPath; Entry = "mods/$jarName" },
    @{ Source = (Join-Path $projectDirectory 'README.md'); Entry = 'README.md' },
    @{ Source = (Join-Path $projectDirectory 'docs/quickstart-ru.md'); Entry = 'QUICKSTART-RU.md' },
    @{ Source = (Join-Path $projectDirectory 'LICENSE'); Entry = 'LICENSE' },
    @{ Source = (Join-Path $projectDirectory 'THIRD_PARTY_NOTICES.md'); Entry = 'THIRD_PARTY_NOTICES.md' }
)
foreach ($inputFile in $inputs) {
    if (-not (Test-Path -LiteralPath $inputFile.Source -PathType Leaf)) {
        throw "Required file is missing: $($inputFile.Source). Assemble the mod and finish its instructions first."
    }
}

function Get-PortableMarkdown([string]$source, [string]$repositoryPath) {
    $content = [IO.File]::ReadAllText($source, [Text.Encoding]::UTF8)
    $baseUri = [Uri]::new("https://github.com/BigZet/minecraft-parrot/blob/main/$repositoryPath")
    return [Text.RegularExpressions.Regex]::Replace(
        $content, '\]\((?![a-z][a-z0-9+.-]*:|//|#)([^)\r\n]+)\)',
        [Text.RegularExpressions.MatchEvaluator] {
            param($match)
            $target = [Uri]::new($baseUri, $match.Groups[1].Value).AbsoluteUri
            return "]($target)"
        }, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

# This packages the existing binary. It never builds or reads local Minecraft settings.
$outputDirectory = Join-Path $projectDirectory 'build/distributions'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$zipPath = Join-Path $outputDirectory "viewerparrots-$Version-streamer.zip"
$temporaryZip = Join-Path $outputDirectory ('.streamer-' + [Guid]::NewGuid().ToString('N') + '.zip')
$archive = [IO.Compression.ZipFile]::Open($temporaryZip, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($inputFile in $inputs) {
        if ($inputFile.Entry -in @('README.md', 'QUICKSTART-RU.md')) {
            $repositoryPath = if ($inputFile.Entry -eq 'README.md') { 'README.md' } else { 'docs/quickstart-ru.md' }
            $entry = $archive.CreateEntry($inputFile.Entry, [IO.Compression.CompressionLevel]::Optimal)
            $entryWriter = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
            try { $entryWriter.Write((Get-PortableMarkdown $inputFile.Source $repositoryPath)) }
            finally { $entryWriter.Dispose() }
        } else {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $inputFile.Source, $inputFile.Entry, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }
} finally {
    $archive.Dispose()
}
Move-Item -LiteralPath $temporaryZip -Destination $zipPath -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $outputDirectory $jarName) -Force

Write-Host "Streamer ZIP: $zipPath"
Write-Host "Mod JAR: $(Join-Path $outputDirectory $jarName)"
Write-Host 'Fabric API is installed separately; see QUICKSTART-RU.md in the archive.'
