param([string]$JavaHome = $env:JAVA_HOME, [switch]$DebugBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskJava = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $taskJava)) { throw 'Indicare un JDK 17 valido con -JavaHome.' }
$taskJavaVersion = (& $taskJava -version 2>&1 | Out-String)
if ($taskJavaVersion -notmatch 'version "17\.') { throw 'Questa build usa Gradle 8.13 e richiede Java 17.' }
$taskPreviousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    Push-Location -LiteralPath $taskRoot
    try {
        $taskHasSigning = Test-Path -LiteralPath (Join-Path $env:USERPROFILE '.android/ds-gallery-signing/password.dpapi')
        $taskVariant = if ($DebugBuild) { 'debug' } elseif ($taskHasSigning) { 'release' } else { 'benchmark' }
        $taskAssemble = ':app:assemble' + (Get-Culture).TextInfo.ToTitleCase($taskVariant)
        & './gradlew.bat' :app:testDebugUnitTest $taskAssemble :app:lintDebug :app:lgplRelinkingKit --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Compilazione o verifiche fallite; APK non pubblicato in dist.' }
        $taskSourceName = if ($taskVariant -eq "release") { "app-release-unsigned.apk" } else { "app-$taskVariant.apk" }
        $taskSource = Join-Path $taskRoot "app/build/outputs/apk/$taskVariant/$taskSourceName"
        $taskDist = Join-Path $taskRoot 'dist'
        New-Item -ItemType Directory -Path $taskDist -Force | Out-Null
        $taskMetadata = Get-Content -LiteralPath (Join-Path $taskRoot "app/build/outputs/apk/$taskVariant/output-metadata.json") -Raw | ConvertFrom-Json
        $taskVersion = $taskMetadata.elements[0].versionName
        if ($taskVersion -notmatch '^[a-zA-Z0-9._-]+$') { throw 'Versione APK non valida.' }
        $taskFilename = if ($DebugBuild) { "DSGallery-$taskVersion-debug.apk" } else { "DSGallery-$taskVersion.apk" }
        $taskApk = Join-Path $taskDist $taskFilename
        if ($taskHasSigning) { & (Join-Path $taskRoot 'sign-apk.ps1') -InputApk $taskSource -OutputApk $taskApk -JavaHome $JavaHome }
        else { Copy-Item -LiteralPath $taskSource -Destination $taskApk }

        $taskHash = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant()
        Get-ChildItem -LiteralPath $taskDist -File | Where-Object { $_.Extension -in '.apk', '.zip' } | Sort-Object Name | ForEach-Object {
            $taskArtifactHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$taskArtifactHash  $($_.Name)"
        } | Set-Content -LiteralPath (Join-Path $taskDist 'SHA256SUMS.txt') -Encoding utf8
        Write-Output "APK pronto: $taskApk"
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $taskPreviousJava }
