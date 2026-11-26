[CmdletBinding()]
param(
    [string]$AppName,
    [string]$AppVersion,
    [string]$IconSource,
    [string]$WebsiteUrl,
    [switch]$TrustHttpOrigin,
    [switch]$Fullscreen,
    [switch]$NonInteractive,
    [switch]$RunTests,
    [ValidateSet('x86_64-pc-windows-msvc', 'aarch64-pc-windows-msvc')]
    [string]$Target = 'x86_64-pc-windows-msvc',
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Read-Required([string]$Value, [string]$Label) {
    if (-not [string]::IsNullOrWhiteSpace($Value)) { return $Value.Trim() }
    if ($NonInteractive) { throw "Missing required input: $Label" }
    do { $Value = Read-Host $Label } while ([string]::IsNullOrWhiteSpace($Value))
    return $Value.Trim()
}
function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE" }
}

if ($env:OS -ne 'Windows_NT') { throw 'Run this builder on Windows.' }
Write-Host 'RWV / Rustmade-WebView' -ForegroundColor Cyan
Write-Host 'by @Jelofen1962 | github.com/Jelofen1962' -ForegroundColor DarkGray
$AppName = Read-Required $AppName 'Application name (e.g. My Web App)'
$AppVersion = Read-Required $AppVersion 'Application version (e.g. 1.0.0)'
$IconSource = Read-Required $IconSource 'PNG icon URL, file:/// URL, or local file path'
$WebsiteUrl = Read-Required $WebsiteUrl 'Website URL (https://, http://, or file:///)'

if ($AppName -match '[\x00-\x1F\x7F"\\]') { throw 'The app name cannot contain control characters, double quotes or backslashes.' }
if ($AppVersion -notmatch '^\d{1,5}(\.\d{1,5}){0,3}$') { throw 'Version must contain 1 to 4 numeric components (example: 1.0.0).' }
foreach ($part in $AppVersion.Split('.')) {
    if ([int]$part -gt 65535) { throw 'Each version component must be between 0 and 65535.' }
}
$webUri = $null
if (-not [Uri]::TryCreate($WebsiteUrl, [UriKind]::Absolute, [ref]$webUri) -or $WebsiteUrl -notmatch '^(?i:https?://|file:///)') {
    throw 'Enter an absolute http://, https://, or file:/// website URL.'
}
if ($webUri.UserInfo) { throw 'Do not embed credentials in the website URL.' }
if ($webUri.Scheme -eq 'http' -and -not $NonInteractive -and -not $PSBoundParameters.ContainsKey('TrustHttpOrigin')) {
    $choice = Read-Host 'Treat this HTTP origin as secure for local development APIs? [y/N]'
    $TrustHttpOrigin = $choice -match '^(?i:y|yes)$'
}
if ($TrustHttpOrigin -and $webUri.Scheme -ne 'http') {
    Write-Warning 'TrustHttpOrigin ignored; it applies only to HTTP URLs.'
    $TrustHttpOrigin = $false
}
if ($TrustHttpOrigin) { Write-Warning 'The selected HTTP origin may gain secure-context APIs. Use only trusted development servers; HTTP traffic remains unencrypted.' }

if (-not $NonInteractive -and -not $PSBoundParameters.ContainsKey('Fullscreen')) {
    $choice = Read-Host 'Start in fullscreen? [y/N]'
    $Fullscreen = $choice -match '^(?i:y|yes)$'
}

if (-not (Get-Command cargo -ErrorAction SilentlyContinue) -or -not (Get-Command rustup -ErrorAction SilentlyContinue)) {
    throw 'Rust and rustup are required. Install Rust from https://rustup.rs/ and install Visual Studio Build Tools with Desktop development with C++.'
}
$root = $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root 'dist' }
$OutputDirectory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
$buildRoot = Join-Path $root '.webview-build'
$stage = Join-Path $buildRoot ([Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Force -Path $stage
$png = Join-Path $stage 'icon.png'
$configPath = Join-Path $stage 'config.json'
$previousConfig = $env:WEBVIEW_APP_CONFIG
$previousIcon = $env:WEBVIEW_APP_ICON
$pushed = $false
try {
    if ($IconSource -match '^(?i:https?://)') {
        Write-Host 'Downloading PNG icon...'
        Invoke-WebRequest -Uri $IconSource -UseBasicParsing -TimeoutSec 60 -OutFile $png
    } else {
        $local = $IconSource
        if ($IconSource -match '^(?i:file://)') { $local = ([Uri]$IconSource).LocalPath }
        $resolved = (Resolve-Path -LiteralPath $local -ErrorAction Stop).ProviderPath
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw 'Icon must be a file.' }
        Copy-Item -LiteralPath $resolved -Destination $png
    }
    $stream = [IO.File]::OpenRead($png)
    try {
        $signature = New-Object byte[] 8
        $count = $stream.Read($signature, 0, 8)
        if ($count -ne 8 -or [BitConverter]::ToString($signature) -ne '89-50-4E-47-0D-0A-1A-0A') { throw 'The icon is not a PNG image.' }
        if ($stream.Length -gt 33554432) { throw 'The PNG must be no larger than 32 MiB.' }
    } finally { $stream.Dispose() }
    $config = [ordered]@{
        name = $AppName
        version = $AppVersion
        website_url = $WebsiteUrl
        trust_http_origin = [bool]$TrustHttpOrigin
        fullscreen = [bool]$Fullscreen
    } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($configPath, $config, (New-Object System.Text.UTF8Encoding($false)))
    $env:WEBVIEW_APP_CONFIG = $configPath
    $env:WEBVIEW_APP_ICON = $png
    Push-Location -LiteralPath $root
    $pushed = $true
    Invoke-Checked 'rustup' @('target', 'add', '--toolchain', 'stable', $Target)
    Write-Host 'Compiling customized Windows EXE...' -ForegroundColor Cyan
    $targetDir = Join-Path $root 'target'
    if ($RunTests) {
        Write-Host 'Running the Rust tests...' -ForegroundColor Cyan
        Invoke-Checked 'cargo' @('+stable', 'test', '--target', $Target, '--target-dir', $targetDir)
    }
    Invoke-Checked 'cargo' @('+stable', 'build', '--release', '--target', $Target, '--target-dir', $targetDir)
    $arch = if ($Target -eq 'aarch64-pc-windows-msvc') { 'arm64' } else { 'x64' }
    $safeName = $AppName -replace '[<>:"/\\|?*\x00-\x1F]', '_'
    $safeName = $safeName.Trim().TrimEnd('.')
    if ([string]::IsNullOrWhiteSpace($safeName)) { $safeName = 'WebViewApp' }
    if ($safeName.Length -gt 100) { $safeName = $safeName.Substring(0, 100).TrimEnd('.', ' ') }
    if ($safeName -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)') { $safeName = '_' + $safeName }
    $null = New-Item -ItemType Directory -Force -Path $OutputDirectory
    $sourceExe = Join-Path $targetDir "$Target\release\rwv.exe"
    $destination = Join-Path $OutputDirectory "$safeName-$AppVersion-windows-$arch.exe"
    Copy-Item -LiteralPath $sourceExe -Destination $destination -Force
    Write-Host "Built: $destination" -ForegroundColor Green
    Write-Host "SHA-256: $((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash)"
    if ($env:GITHUB_OUTPUT) { [IO.File]::AppendAllText($env:GITHUB_OUTPUT, "exe=$destination`n", (New-Object System.Text.UTF8Encoding($false))) }
} finally {
    if ($pushed) { Pop-Location }
    $env:WEBVIEW_APP_CONFIG = $previousConfig
    $env:WEBVIEW_APP_ICON = $previousIcon
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
}
