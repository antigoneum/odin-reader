param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
        Select-Object -First 1
    if (-not $localJdk) { throw 'JDK 17+ not found. Set JAVA_HOME or install it under .tools/jdk-*.' }
    $env:JAVA_HOME = $localJdk.FullName
}
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
}
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'))) {
    throw 'Android SDK Platform 36 not found. Set ANDROID_HOME to your SDK location.'
}

$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
$env:TEMP = Join-Path $projectRoot '.tools/tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force $env:TEMP, $env:ANDROID_USER_HOME | Out-Null

$localProperties = Join-Path $projectRoot 'local.properties'
if (-not (Test-Path -LiteralPath $localProperties)) {
    $sdkPath = $env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:')
    [System.IO.File]::WriteAllText($localProperties, "sdk.dir=$sdkPath", [System.Text.UTF8Encoding]::new($false))
}

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $buildExitCode
