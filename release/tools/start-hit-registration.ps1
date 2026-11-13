$ErrorActionPreference = 'Stop'
$package = Split-Path -Parent $PSScriptRoot
$debugJar = 'tools/hit-registration/OpenCheatBreaker-1.8.9.jar'
$clientJar = Join-Path $package $debugJar
$originalArgs = Join-Path $package 'runtime/java.args'
$argsFile = Join-Path $PSScriptRoot 'hit-registration-java.args'
$classpathJar = 'versions/OpenCheatBreaker-1.8.9/OpenCheatBreaker-1.8.9.jar'
$javaArgs = [IO.File]::ReadAllText($originalArgs)
$javaArgs = "-Docb.fixMissCooldown=true`n$javaArgs"
if (-not $javaArgs.Contains($classpathJar) -or
    (-not (Test-Path -LiteralPath $clientJar) -and -not (Test-Path -LiteralPath (Join-Path $package $classpathJar)))) {
    throw 'The OpenCheatBreaker client JAR is missing from the runtime.'
}
$debugClasspath = $classpathJar
if (Test-Path -LiteralPath $clientJar) {
    $debugClasspath = $debugJar
}
$openALJar = 'libraries/com/paulscode/librarylwjglopenal/20100824/librarylwjglopenal-20100824.jar'
if ((Test-Path -LiteralPath (Join-Path $package $openALJar)) -and
    (Test-Path -LiteralPath (Join-Path $package 'natives/OpenAL64.dll'))) {
    if (-not $javaArgs.Contains($openALJar)) {
        $debugClasspath += ";$openALJar"
    }
    $javaArgs = "-Docb.hitTraceOpenAL=true`n$javaArgs"
}
[IO.File]::WriteAllText($argsFile, $javaArgs.Replace($classpathJar, $debugClasspath))
$java = 'java.exe'
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
}
$username = $env:OCB_USERNAME
if (-not $username) { $username = 'Player' }
$gameDir = $env:OCB_GAME_DIR
if (-not $gameDir) { $gameDir = 'game' }
$assets = Join-Path $package 'assets'
if (-not (Test-Path -LiteralPath (Join-Path $assets 'indexes/1.8.json'))) {
    $installedAssets = Join-Path $env:APPDATA '.minecraft/assets'
    if (Test-Path -LiteralPath (Join-Path $installedAssets 'indexes/1.8.json')) {
        $assets = $installedAssets
    } else {
        & (Join-Path $PSScriptRoot 'download-assets.ps1') -Target $assets
    }
}
Push-Location -LiteralPath $package
try {
    $extra = @()
    if ($env:OCB_JAVA_OPTS) { $extra = $env:OCB_JAVA_OPTS }
    & $java @extra "@$argsFile" Start --username $username --gameDir $gameDir --assetsDir $assets `
        --uuid 00000000000000000000000000000001 --accessToken 0 --userType legacy `
        --width 854 --height 480 --installationID 00000000-0000-0000-0000-000000000000 `
        --websocketToken 0 --launcherVersion 2026.10.0 @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
