#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-zA-Z0-9]+$')]
    [string]$ClientId,
    [ValidatePattern('^[a-zA-Z0-9_]+$')]
    [string]$Login = 'numnerd',
    [ValidateSet('chat:read', 'chat:read chat:edit')]
    [string]$Scopes = 'chat:read',
    [string]$TokenPath = (Join-Path $env:APPDATA 'PrismLauncher\instances\1.21.1-fabric-dev\minecraft\config\viewerparrots-twitch-token.txt')
)

# Official public-client device flow; no client secret or Twitch CLI needed.
# https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow
# Run in a hidden process with stdout redirected to see only the approval URL/code.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$VerbosePreference = 'SilentlyContinue'
$DebugPreference = 'SilentlyContinue'

function Invoke-TwitchOAuth {
    param([string]$Endpoint, [hashtable]$Body, [hashtable]$Headers)
    $options = @{
        Uri = "https://id.twitch.tv/oauth2/$Endpoint"
        TimeoutSec = 30
        MaximumRedirection = 0
        SkipHttpErrorCheck = $true
        StatusCodeVariable = 'status'
        ErrorAction = 'Stop'
        Verbose = $false
        Debug = $false
    }
    if ($Body) {
        $options.Method = 'Post'
        $options.ContentType = 'application/x-www-form-urlencoded'
        $options.Body = $Body
    } else {
        $options.Method = 'Get'
        $options.Headers = $Headers
    }
    $data = Invoke-RestMethod @options
    return @{ Status = $status; Data = $data }
}

$safeError = 'Twitch login failed; no token was saved. Please retry.'
try {
    $scopes = $Scopes
    $response = Invoke-TwitchOAuth -Endpoint 'device' -Body @{ client_id = $ClientId; scopes = $scopes }
    if ($response.Status -ne 200) { throw 'Device request rejected.' }
    $device = $response.Data
    $interval = [int]$device.interval
    $expiresIn = [int]$device.expires_in
    $approvalUri = [uri]$device.verification_uri
    if ($interval -lt 1 -or $expiresIn -lt 1 -or
        [string]::IsNullOrWhiteSpace($device.device_code) -or
        [string]::IsNullOrWhiteSpace($device.user_code) -or
        $approvalUri.Scheme -ne 'https' -or $approvalUri.Host -ne 'www.twitch.tv' -or
        $approvalUri.AbsolutePath -ne '/activate') { throw 'Unexpected device response.' }

    [Console]::WriteLine($approvalUri.AbsoluteUri)
    [Console]::WriteLine($device.user_code)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($expiresIn)
    $token = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        # Respect Twitch's polling interval, including slow_down, in <=60s chunks.
        $remaining = $interval
        while ($remaining -gt 0 -and [DateTimeOffset]::UtcNow -lt $deadline) {
            $pause = [Math]::Min(60, $remaining)
            Start-Sleep -Seconds $pause
            $remaining -= $pause
        }
        if ([DateTimeOffset]::UtcNow -ge $deadline) { break }
        $response = Invoke-TwitchOAuth -Endpoint 'token' -Body @{
            client_id = $ClientId
            scopes = $scopes
            device_code = $device.device_code
            grant_type = 'urn:ietf:params:oauth:grant-type:device_code'
        }
        if ($response.Status -eq 200) {
            $token = [string]$response.Data.access_token
            if ([string]::IsNullOrWhiteSpace($token)) { throw 'Missing token.' }
            break
        }
        $errorCode = if ($response.Data.PSObject.Properties['error']) {
            [string]$response.Data.error
        } elseif ($response.Data.PSObject.Properties['message']) {
            [string]$response.Data.message
        } else { '' }
        switch ($errorCode) {
            'authorization_pending' { continue }
            'slow_down' { $interval += 5; continue }
            default {
                $safeError = 'Twitch authorization stopped or was rejected; no token was saved. Please retry.'
                throw 'Authorization stopped.'
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($token)) {
        $safeError = 'Twitch approval code expired; no token was saved. Please retry.'
        throw 'Device code expired.'
    }

    $response = Invoke-TwitchOAuth -Endpoint 'validate' -Headers @{ Authorization = "OAuth $token" }
    if ($response.Status -ne 200) { throw 'Token validation failed.' }
    $validated = $response.Data
    if ($validated.login -ine $Login -or $validated.client_id -cne $ClientId -or
        [int]$validated.expires_in -le 0 -or
        @($scopes.Split(' ') | Where-Object { $_ -cnotin $validated.scopes }).Count -gt 0) {
        $safeError = 'Twitch account or permissions do not match the requested login; no token was saved.'
        throw 'Token identity or scope mismatch.'
    }

    $safeError = 'Twitch token could not be saved; check the configured local path.'
    $fullPath = [IO.Path]::GetFullPath($TokenPath)
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($fullPath))
    [IO.File]::WriteAllText($fullPath, $token, [Text.UTF8Encoding]::new($false))
    exit 0
} catch {
    # Never emit exception details: OAuth responses/headers may contain credentials.
    [Console]::Error.WriteLine($safeError)
    exit 1
}
