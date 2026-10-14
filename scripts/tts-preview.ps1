param(
    [int]$Port = 0,
    [switch]$SetKey
)

$ErrorActionPreference = 'Stop'
$ttsSecretDirectory = Join-Path $env:LOCALAPPDATA 'ViewerParrots'
$ttsSecretFile = Join-Path $ttsSecretDirectory 'openrouter-key.dpapi'

if ($SetKey -or -not (Test-Path -LiteralPath $ttsSecretFile)) {
    [System.IO.Directory]::CreateDirectory($ttsSecretDirectory) | Out-Null
    $ttsSecret = Read-Host 'Ключ OpenRouter' -AsSecureString
    if ($ttsSecret.Length -eq 0) { throw 'Ключ не может быть пустым.' }
    ConvertFrom-SecureString -SecureString $ttsSecret |
        Set-Content -LiteralPath $ttsSecretFile -Encoding utf8
    Remove-Variable ttsSecret
}

$ttsPython = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $ttsPython) { throw 'Для запуска нужен Python 3.' }
$ttsPowerShell = (Get-Command pwsh).Source
Write-Host 'Адрес страницы появится ниже. Для остановки нажми Ctrl+C.'
& $ttsPython (Join-Path $PSScriptRoot 'tts-preview\server.py') --port $Port --key-file $ttsSecretFile --pwsh $ttsPowerShell
exit $LASTEXITCODE
