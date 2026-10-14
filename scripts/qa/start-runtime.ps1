param(
    [Parameter(Mandatory)][ValidateSet('Server', 'Client', 'Observer')][string]$Role,
    [string]$ServerJava,
    [string]$ClientJava,
    [long]$ServerAffinity = 0xFF,
    [long]$ClientAffinity = 0xF00
)
$ErrorActionPreference = 'Stop'
if (-not $ServerJava) {
    $ServerJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
}
if (-not $ClientJava) {
    $prismJava = Join-Path $env:APPDATA 'PrismLauncher/java/java-runtime-delta/bin/javaw.exe'
    $ClientJava = if (Test-Path -LiteralPath $prismJava) { $prismJava } else { Join-Path (Split-Path -Parent $ServerJava) 'javaw.exe' }
}
if (-not (Test-Path -LiteralPath $ServerJava) -or -not (Test-Path -LiteralPath $ClientJava)) { throw 'Java runtime executable is missing.' }
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$snapshot = Get-Content -LiteralPath (Join-Path $projectDirectory 'artifacts/qa-runtime/current.json') -Raw | ConvertFrom-Json
$runDirectory = Join-Path $projectDirectory "build/acceptance-$($Role.ToLowerInvariant())"
New-Item -ItemType Directory -Path $runDirectory -Force > $null
$launchArguments = @(
    "-Dfabric.dli.config=$($snapshot.configuration)",
    "-Dfabric.dli.env=$(if ($Role -eq 'Server') {'server'} else {'client'})",
    "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.Knot$(if ($Role -eq 'Server') {'Server'} else {'Client'})",
    '-Dfile.encoding=UTF-8'
)
if ($Role -eq 'Server') {
    $javaExecutable = $ServerJava
    $launchArguments += @('-Xmx4G', '-XX:ActiveProcessorCount=8', "@$($snapshot.arguments.Server)", 'net.fabricmc.devlaunchinjector.Main', 'nogui')
    $affinity = $ServerAffinity
} else {
    $javaExecutable = $ClientJava
    $username = if ($Role -eq 'Client') {'QAStreamer'} else {'QAObserver'}
    $launchArguments += @('-Xmx2G', '-XX:ActiveProcessorCount=4', '-Dviewerparrots.qa.connect=true', "@$($snapshot.arguments.$Role)", 'net.fabricmc.devlaunchinjector.Main', '--username', $username)
    $affinity = $ClientAffinity
}
# Java argument files hold the classpath. Quote individual arguments for paths containing spaces.
$quotedArguments = @($launchArguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' })
$process = Start-Process -FilePath $javaExecutable -ArgumentList $quotedArguments -WorkingDirectory $runDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runDirectory 'final-stdout.log') -RedirectStandardError (Join-Path $runDirectory 'final-stderr.log') -PassThru
$process.ProcessorAffinity = [IntPtr]::new($affinity)
[pscustomobject]@{
    role = $Role
    pid = $process.Id
    affinity = '0x{0:X}' -f $affinity
    snapshot = $snapshot.directory
    mainClassTreeSha256 = $snapshot.mainClassTreeSha256
    java = $javaExecutable
    started = (Get-Date -Format o)
} | ConvertTo-Json
