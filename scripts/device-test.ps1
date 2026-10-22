param(
    [string] $Serial,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localAdb = Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe'
if (Test-Path -LiteralPath $localAdb) {
    $adbExecutable = $localAdb
} else {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adbCommand) { throw 'ADB not found. Install Android SDK platform-tools first.' }
    $adbExecutable = $adbCommand.Source
}

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & (Join-Path $PSScriptRoot 'dev.ps1') assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Android build or local checks failed.' }
    }

    $deviceOutput = & $adbExecutable devices
    if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to ADB.' }
    $connected = @($deviceOutput | ForEach-Object {
        if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
    })
    if ($Serial) {
        if ($Serial -notin $connected) { throw "Device $Serial is not connected and authorized." }
    } elseif ($connected.Count -eq 1) {
        $Serial = $connected[0]
    } else {
        throw 'Connect exactly one authorized device, or pass -Serial explicitly.'
    }

    $appApk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
    $testApk = Join-Path $projectRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    if (-not (Test-Path $appApk) -or -not (Test-Path $testApk)) {
        throw 'APK files are missing. Run this script without -SkipBuild first.'
    }
    & $adbExecutable -s $Serial install -r $appApk
    if ($LASTEXITCODE -ne 0) { throw 'Application installation failed.' }
    & $adbExecutable -s $Serial install -r $testApk
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }

    $artifactFolder = Join-Path $projectRoot 'artifacts/device-validation'
    New-Item -ItemType Directory -Force $artifactFolder | Out-Null
    $logFile = Join-Path $artifactFolder 'instrumentation-latest.txt'
    $testLines = [System.Collections.Generic.List[string]]::new()
    & $adbExecutable -s $Serial shell am instrument -w -r io.github.odinreader.test/androidx.test.runner.AndroidJUnitRunner 2>&1 |
        ForEach-Object {
            $line = $_.ToString()
            $testLines.Add($line)
            Write-Host $line
        }
    $adbResult = $LASTEXITCODE
    $report = [string]::Join([Environment]::NewLine, $testLines)
    [System.IO.File]::WriteAllText($logFile, $report, [System.Text.UTF8Encoding]::new($false))

    # ADB often exits 0 even when JUnit fails. Require the runner's success summary.
    if ($adbResult -ne 0 -or $report -notmatch 'OK \(\d+ tests?\)' -or
        $report -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed') {
        throw "Device tests failed or did not complete. See $logFile"
    }
    Write-Host "Device tests passed on $Serial. Report: $logFile"
} finally {
    Pop-Location
}
