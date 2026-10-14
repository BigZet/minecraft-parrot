param([switch]$Clean, [switch]$Extended)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    $task = if ($Extended) { 'build' } else { 'assemble' }
    $buildTasks = @($task, '--console=plain')
    if ($Clean) { $buildTasks = @('clean') + $buildTasks }
    & .\gradlew.bat @buildTasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle $task failed." }
    if ($Extended) {
        Write-Host 'Build, unit tests and Minecraft GameTests passed.'
    } else {
        Write-Host 'JAR assembled. Check the Twitch commands in a single-player world before calling the chat flow ready.'
    }
} finally {
    Pop-Location
}
