#Requires -Version 5.1
<#
    Установщик Viewer Parrots для стримера без опыта с модами.

    Делает всё сам: ставит portable Prism Launcher в отдельную папку, создаёт
    инстанс Minecraft 1.21.1 + Fabric, кладёт Fabric API и мод, записывает
    Twitch-канал и создаёт ярлык на рабочем столе. Java 21 Prism скачает сам
    при первом запуске. Единственное, что остаётся стримеру, — войти в свой
    Microsoft-аккаунт в окне Prism.

    Запускать через «Установить Viewer Parrots.cmd» или:
      powershell -ExecutionPolicy Bypass -File install.ps1 [-Channel имя] [-InstallDir путь] [-NoLaunch]
#>
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'ViewerParrots'),
    [string]$Channel = '',
    [switch]$NoLaunch,
    [switch]$NoShortcut
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

# ---- Версии: меняются вместе с релизом -------------------------------------
$ModVersion       = '1.1.0'
$MinecraftVersion = '1.21.1'
$FabricLoader     = '0.19.5'
$FabricApiJar     = 'fabric-api-0.116.17+1.21.1.jar'
$PrismVersion     = '11.1.1'
$PrismZipName     = "PrismLauncher-Windows-MSVC-Portable-$PrismVersion.zip"
$PrismUrl         = "https://github.com/PrismLauncher/PrismLauncher/releases/download/$PrismVersion/$PrismZipName"
$PrismSha256      = 'AB35A770FB06D89D2CCC098079DB5DB329FB4E68F42B72BABD8B095EFDE3D2D7'
$VcRedistUrl      = 'https://aka.ms/vs/17/release/vc_redist.x64.exe'
$InstanceId       = 'ViewerParrots'
$InstanceName     = 'Viewer Parrots'
# ---------------------------------------------------------------------------

$packageRoot = Split-Path -Parent $PSScriptRoot
$payload = Join-Path $packageRoot 'payload'
$interactive = -not $NoLaunch -and [Environment]::UserInteractive
$script:stepNumber = 0

function Step([string]$text) {
    $script:stepNumber++
    Write-Host ''
    Write-Host ("[{0}] {1}" -f $script:stepNumber, $text) -ForegroundColor Cyan
}
function Ok([string]$text) { Write-Host ("    + " + $text) -ForegroundColor Green }
function Info([string]$text) { Write-Host ("    " + $text) -ForegroundColor Gray }
function Fail([string]$text) {
    Write-Host ''
    Write-Host ("ОШИБКА: " + $text) -ForegroundColor Red
    Write-Host ("Журнал установки: " + $script:logPath) -ForegroundColor Yellow
    if ($interactive) { Read-Host 'Нажмите Enter, чтобы закрыть окно' | Out-Null }
    exit 1
}

function Normalize-Channel([string]$value) {
    $value = $value.Trim().ToLowerInvariant()
    $value = $value -replace '^https?://(www\.)?twitch\.tv/', '' -replace '^[@#]', '' -replace '/.*$', ''
    if ($value -notmatch '^[a-z0-9_]{1,25}$') { return $null }
    return $value
}

function Download([string]$url, [string]$target, [string]$what) {
    Info "Скачивание $what..."
    try {
        Invoke-WebRequest -Uri $url -OutFile $target -UseBasicParsing -TimeoutSec 600
    } catch {
        Fail "Не удалось скачать $what.`n    $url`n    $($_.Exception.Message)`n    Проверьте интернет (и VPN, если GitHub недоступен) и запустите установку снова."
    }
    try { Unblock-File -LiteralPath $target } catch { }
}

function Write-Utf8NoBom([string]$path, [string]$text) {
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding $false))
}

# ---- Старт ----------------------------------------------------------------
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
$script:logPath = Join-Path $InstallDir 'install.log'
try { Start-Transcript -LiteralPath $script:logPath -Append | Out-Null } catch { }

Write-Host ''
Write-Host "=== Viewer Parrots $ModVersion — установка ===" -ForegroundColor Magenta
Write-Host "Папка: $InstallDir"
Write-Host 'Будут установлены: Prism Launcher (portable), Minecraft 1.21.1 + Fabric, Fabric API, мод Viewer Parrots.'
Write-Host 'Сам Minecraft (Java Edition) должен быть куплен на вашем Microsoft-аккаунте.'

if (-not [Environment]::Is64BitOperatingSystem) { Fail 'Нужна 64-битная Windows.' }
if ([Environment]::OSVersion.Version.Major -lt 10) { Fail 'Нужна Windows 10 или новее.' }

$modJar = Get-ChildItem -LiteralPath $payload -Filter 'viewerparrots-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-sources' } | Select-Object -First 1
$apiJar = Join-Path $payload $FabricApiJar
if (-not $modJar) { Fail "В пакете нет файла мода: $payload\viewerparrots-*.jar. Распакуйте архив полностью." }
if (-not (Test-Path -LiteralPath $apiJar)) { Fail "В пакете нет Fabric API: $apiJar. Распакуйте архив полностью." }

# ---- Twitch-канал ---------------------------------------------------------
Step 'Twitch-канал'
$twitchChannel = $null
if ($Channel) {
    $twitchChannel = Normalize-Channel $Channel
    if (-not $twitchChannel) { Fail "Некорректное имя канала: '$Channel'. Допустимы латинские буквы, цифры и _." }
} elseif ($interactive) {
    while (-not $twitchChannel) {
        $answer = Read-Host 'Введите имя вашего Twitch-канала (как в адресе twitch.tv/ИМЯ; Enter — настроить позже в игре)'
        if ([string]::IsNullOrWhiteSpace($answer)) { break }
        $twitchChannel = Normalize-Channel $answer
        if (-not $twitchChannel) { Write-Host '    Допустимы только латинские буквы, цифры и _. Попробуйте ещё раз.' -ForegroundColor Yellow }
    }
}
if ($twitchChannel) { Ok "Канал: $twitchChannel" } else { Info 'Канал не задан: его можно указать в игре по клавише F8.' }

# ---- Visual C++ Runtime (нужен Prism) --------------------------------------
Step 'Системные компоненты'
$system32 = Join-Path $env:SystemRoot 'System32'
$vcMissing = @('vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll') | Where-Object { -not (Test-Path (Join-Path $system32 $_)) }
if ($vcMissing.Count -eq 0) {
    Ok 'Visual C++ Runtime уже установлен.'
} else {
    $vcInstaller = Join-Path $InstallDir 'vc_redist.x64.exe'
    Download $VcRedistUrl $vcInstaller 'Microsoft Visual C++ Runtime'
    Info 'Установка Visual C++ Runtime (подтвердите запрос Windows, если он появится)...'
    $process = Start-Process -FilePath $vcInstaller -ArgumentList '/install', '/quiet', '/norestart' -Wait -PassThru
    if ($process.ExitCode -notin 0, 1638, 3010) { Fail "Visual C++ Runtime не установился (код $($process.ExitCode))." }
    Remove-Item -LiteralPath $vcInstaller -Force -ErrorAction SilentlyContinue
    Ok 'Visual C++ Runtime установлен.'
}

# ---- Prism Launcher (portable) ---------------------------------------------
Step 'Prism Launcher'
$prismDir = Join-Path $InstallDir 'PrismLauncher'
$prismExe = Join-Path $prismDir 'prismlauncher.exe'
if (Get-Process -Name 'prismlauncher' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $prismExe }) {
    Fail 'Prism Launcher из этой установки сейчас открыт. Закройте его и запустите установку снова.'
}
if (Test-Path -LiteralPath $prismExe) {
    Ok "Prism Launcher уже установлен: $prismDir"
} else {
    $prismZip = Join-Path $InstallDir $PrismZipName
    Download $PrismUrl $prismZip "Prism Launcher $PrismVersion"
    $actual = (Get-FileHash -LiteralPath $prismZip -Algorithm SHA256).Hash
    if ($actual -ne $PrismSha256) { Fail "Скачанный Prism Launcher повреждён (SHA-256 не совпадает). Запустите установку снова." }
    Info 'Распаковка...'
    New-Item -ItemType Directory -Path $prismDir -Force | Out-Null
    Expand-Archive -LiteralPath $prismZip -DestinationPath $prismDir -Force
    Remove-Item -LiteralPath $prismZip -Force
    if (-not (Test-Path -LiteralPath $prismExe)) { Fail 'После распаковки не найден prismlauncher.exe.' }
    if (-not (Test-Path (Join-Path $prismDir 'portable.txt'))) { Set-Content -LiteralPath (Join-Path $prismDir 'portable.txt') -Value '' }
    Get-ChildItem -LiteralPath $prismDir -Recurse -File | Unblock-File -ErrorAction SilentlyContinue
    Ok "Prism Launcher $PrismVersion установлен."
}

# Настройки лаунчера: русский язык, автозагрузка Java 21, без мастера первого запуска.
# Остаётся только вход в Microsoft-аккаунт.
$prismCfg = Join-Path $prismDir 'prismlauncher.cfg'
if (-not (Test-Path -LiteralPath $prismCfg)) {
    $cfg = @(
        '[General]'
        'Language=ru'
        'ApplicationTheme=dark'
        'IconTheme=pe_colored'
        'AutomaticJavaSwitch=true'
        'AutomaticJavaDownload=true'
        'UserAskedAboutAutomaticJavaDownload=true'
        'IgnoreJavaWizard=true'
        "LastHostname=$env:COMPUTERNAME"
        "SelectedInstance=$InstanceId"
        'AutoUpdate=false'
        ''
    ) -join "`r`n"
    Write-Utf8NoBom $prismCfg $cfg
    Ok 'Настройки лаунчера записаны (русский язык, Java 21 скачается автоматически).'
} else {
    Info 'Настройки лаунчера уже существуют — не трогаю.'
}

# ---- Инстанс Minecraft 1.21.1 + Fabric ---------------------------------------
Step "Инстанс Minecraft $MinecraftVersion + Fabric Loader $FabricLoader"
$instanceDir = Join-Path $prismDir "instances\$InstanceId"
$minecraftDir = Join-Path $instanceDir 'minecraft'
$modsDir = Join-Path $minecraftDir 'mods'
$configDir = Join-Path $minecraftDir 'config'
foreach ($dir in $instanceDir, $minecraftDir, $modsDir, $configDir) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

$instanceCfg = Join-Path $instanceDir 'instance.cfg'
if (-not (Test-Path -LiteralPath $instanceCfg)) {
    Write-Utf8NoBom $instanceCfg (@(
        '[General]'
        'ConfigVersion=1.3'
        'InstanceType=OneSix'
        "name=$InstanceName"
        'iconKey=default'
        'AutomaticJava=true'
        ''
    ) -join "`r`n")
}

# Prism сам скачает Minecraft, Intermediary и Fabric Loader по этому списку компонентов.
$mmcPack = Join-Path $instanceDir 'mmc-pack.json'
Write-Utf8NoBom $mmcPack (@"
{
    "components": [
        { "cachedName": "LWJGL 3", "cachedVersion": "3.3.3", "cachedVolatile": true, "dependencyOnly": true, "uid": "org.lwjgl3", "version": "3.3.3" },
        { "cachedName": "Minecraft", "cachedRequires": [ { "suggests": "3.3.3", "uid": "org.lwjgl3" } ], "cachedVersion": "$MinecraftVersion", "important": true, "uid": "net.minecraft", "version": "$MinecraftVersion" },
        { "cachedName": "Intermediary Mappings", "cachedRequires": [ { "equals": "$MinecraftVersion", "uid": "net.minecraft" } ], "cachedVersion": "$MinecraftVersion", "cachedVolatile": true, "dependencyOnly": true, "uid": "net.fabricmc.intermediary", "version": "$MinecraftVersion" },
        { "cachedName": "Fabric Loader", "cachedRequires": [ { "uid": "net.fabricmc.intermediary" } ], "cachedVersion": "$FabricLoader", "uid": "net.fabricmc.fabric-loader", "version": "$FabricLoader" }
    ],
    "formatVersion": 1
}
"@)
Ok "Инстанс «$InstanceName» готов."

# ---- Моды -------------------------------------------------------------------
Step 'Моды'
Get-ChildItem -LiteralPath $modsDir -Filter 'viewerparrots-*.jar' | Where-Object { $_.Name -ne $modJar.Name } | ForEach-Object {
    Info "Удаляю старую версию $($_.Name)"
    Remove-Item -LiteralPath $_.FullName -Force
}
Get-ChildItem -LiteralPath $modsDir -Filter 'fabric-api-*.jar' | Where-Object { $_.Name -ne $FabricApiJar } | Remove-Item -Force
Copy-Item -LiteralPath $modJar.FullName -Destination (Join-Path $modsDir $modJar.Name) -Force
Copy-Item -LiteralPath $apiJar -Destination (Join-Path $modsDir $FabricApiJar) -Force
Ok "$($modJar.Name) и $FabricApiJar скопированы."

# ---- Настройки мода и Minecraft ---------------------------------------------
Step 'Настройки'
$settingsJson = Join-Path $configDir 'viewerparrots-settings.json'
$twitchJson = Join-Path $configDir 'viewerparrots-twitch.json'
if ($twitchChannel) {
    if (Test-Path -LiteralPath $settingsJson) {
        Info 'Настройки мода уже есть — канал не меняю. Его можно поменять в игре: F8 → Twitch.'
    } else {
        # Мод при первом запуске переносит этот файл в viewerparrots-settings.json (гостевое чтение чата).
        Write-Utf8NoBom $twitchJson ("{`"autoConnect`": true, `"channel`": `"$twitchChannel`", `"login`": `"`"}`n")
        Ok "Twitch-канал $twitchChannel записан; чат подключится автоматически."
    }
}
$optionsTxt = Join-Path $minecraftDir 'options.txt'
if (-not (Test-Path -LiteralPath $optionsTxt)) {
    # Русский интерфейс и игра без паузы при переходе в браузер/OBS.
    Write-Utf8NoBom $optionsTxt "lang:ru_ru`npauseOnLostFocus:false`n"
    Ok 'Minecraft: русский язык, без паузы при Alt+Tab.'
}

# ---- Ярлык ------------------------------------------------------------------
if (-not $NoShortcut) {
    Step 'Ярлык на рабочем столе'
    try {
        $desktop = [Environment]::GetFolderPath('Desktop')
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut((Join-Path $desktop 'Viewer Parrots.lnk'))
        $shortcut.TargetPath = $prismExe
        $shortcut.Arguments = "-l $InstanceId"
        $shortcut.WorkingDirectory = $prismDir
        $shortcut.IconLocation = "$prismExe,0"
        $shortcut.Description = 'Запустить Minecraft с модом Viewer Parrots'
        $shortcut.Save()
        Ok 'Ярлык «Viewer Parrots» создан.'
    } catch {
        Info "Ярлык создать не удалось: $($_.Exception.Message). Запускайте $prismExe"
    }
}

# ---- Итог -------------------------------------------------------------------
Write-Host ''
Write-Host '=== Установка завершена ===' -ForegroundColor Green
Write-Host ''
Write-Host 'Что дальше:' -ForegroundColor Yellow
Write-Host '  1. Сейчас откроется Prism Launcher. Нажмите «Добавить Microsoft» и войдите в аккаунт, на котором куплен Minecraft.'
Write-Host '  2. Запустите инстанс «Viewer Parrots» (двойной клик) или ярлык на рабочем столе.'
Write-Host '     Первый запуск долгий: скачиваются Java 21 и Minecraft.'
Write-Host '  3. В игре создайте одиночный мир, нажмите F8 → «Назначить себя стримером».'
if (-not $twitchChannel) { Write-Host '  4. F8 → Twitch: укажите канал, включите гостевое чтение, примените и подключитесь.' }
Write-Host '  Зрители пишут в чат !parrot — и получают своего попугая.'
Write-Host ''
Write-Host "Папка установки: $InstallDir"
Write-Host "Папка Minecraft (mods, config, saves): $minecraftDir"
Write-Host "Журнал: $script:logPath"
try { Stop-Transcript | Out-Null } catch { }

if (-not $NoLaunch) {
    Start-Process -FilePath $prismExe -WorkingDirectory $prismDir
    if ($interactive) { Read-Host 'Нажмите Enter, чтобы закрыть это окно' | Out-Null }
}
exit 0
