[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9._:%\[\]-]+$')]
    [string]$DeviceSerial,

    [Parameter(Mandatory = $true)]
    [ValidateSet('Preconnected', 'ConnectDuringPlayback')]
    [string]$Scenario,

    [string]$AdbPath,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AdbPath) {
    $AdbPath = Join-Path $projectRoot '.gradle\codex-toolchain\sdk\platform-tools\adb.exe'
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectRoot 'app\build\diagnostics'
}
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB not found: $AdbPath"
}
$deviceState = & $AdbPath -s $DeviceSerial get-state 2>&1
if ($LASTEXITCODE -ne 0 -or ($deviceState -join '').Trim() -ne 'device') {
    throw "ADB device is not ready: $deviceState"
}

$captureName = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + $Scenario
$captureDirectory = Join-Path $OutputDirectory $captureName
New-Item -ItemType Directory -Path $captureDirectory -Force | Out-Null
Write-Host "Output: $captureDirectory"

function Save-AdbOutput {
    param([string]$Name, [string[]]$Command)
    $result = & $AdbPath -s $DeviceSerial @Command 2>&1
    $exitCode = $LASTEXITCODE
    @("adb exit code: $exitCode") + @($result) |
        Out-File -LiteralPath (Join-Path $captureDirectory $Name) -Encoding utf8
}

function Save-DisplayState {
    param([string]$Stage)
    Save-AdbOutput "$Stage-display.txt" @('shell', 'dumpsys', 'display')
    Save-AdbOutput "$Stage-windows.txt" @('shell', 'dumpsys', 'window', 'windows')
    Save-AdbOutput "$Stage-activities.txt" @('shell', 'dumpsys', 'activity', 'activities')
}

if ($Scenario -eq 'Preconnected') {
    Write-Host 'Connect the glasses now. Keep the phone connected through wireless ADB.'
} else {
    Write-Host 'Unplug the glasses now. Keep wireless ADB connected.'
}
$null = Read-Host 'Press Enter when ready. NeoXR will restart; application data is kept'

$captureProcess = $null
try {
    $captureProcess = Start-Process -FilePath $AdbPath -WindowStyle Hidden -PassThru `
        -ArgumentList @('-s', $DeviceSerial, 'logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-v', 'threadtime', '-T', '1', '*:V') `
        -RedirectStandardOutput (Join-Path $captureDirectory 'logcat.txt') `
        -RedirectStandardError (Join-Path $captureDirectory 'logcat-stderr.txt')
    Start-Sleep -Milliseconds 300
    if ($captureProcess.HasExited) { throw 'Logcat stopped. Check logcat-stderr.txt.' }

    @("Scenario: $Scenario", "Device: $DeviceSerial", "Started: $((Get-Date).ToString('o'))") |
        Out-File -LiteralPath (Join-Path $captureDirectory 'capture-info.txt') -Encoding utf8
    Save-AdbOutput 'device-model.txt' @('shell', 'getprop', 'ro.product.model')
    Save-AdbOutput 'android-version.txt' @('shell', 'getprop', 'ro.build.version.release')
    Save-AdbOutput 'installed-package.txt' @('shell', 'dumpsys', 'package', 'com.neoxr.player')
    Save-DisplayState 'before'
    Save-AdbOutput 'restart.txt' @('shell', 'am', 'force-stop', 'com.neoxr.player')
    Save-AdbOutput 'launch.txt' @('shell', 'am', 'start', '-W', '-n', 'com.neoxr.player/.MainActivity')

    $null = Read-Host 'When the NeoXR home/library is visible, press Enter BEFORE opening a video'
    Save-DisplayState 'menu'
    if ($Scenario -eq 'Preconnected') {
        Write-Host 'Open a video with the glasses already connected. Wait 5 seconds after the result.'
    } else {
        Write-Host 'Start a video on the phone, then connect the glasses. Wait 5 seconds after the result.'
    }
    $outcome = Read-Host 'Describe the result briefly, then press Enter to finish capture'
    @("Finished: $((Get-Date).ToString('o'))", "Outcome: $outcome") |
        Add-Content -LiteralPath (Join-Path $captureDirectory 'capture-info.txt') -Encoding utf8
    Save-DisplayState 'after'
} finally {
    if ($captureProcess -and -not $captureProcess.HasExited) {
        Stop-Process -Id $captureProcess.Id -ErrorAction SilentlyContinue
        $null = $captureProcess.WaitForExit(3000)
    }
}

$archivePath = $captureDirectory + '.zip'
Compress-Archive -LiteralPath $captureDirectory -DestinationPath $archivePath
Write-Host "Saved: $archivePath"
