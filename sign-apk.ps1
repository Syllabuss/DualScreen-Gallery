param(
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.android/ds-gallery-signing'),
    [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android/Sdk')
)
$ErrorActionPreference = 'Stop'
$taskSigner = Join-Path $AndroidSdk 'build-tools/35.0.0/apksigner.bat'
$taskLineage = Join-Path $SigningDirectory 'signing-lineage.bin'
$taskLegacy = Join-Path $SigningDirectory 'dsgallery-legacy.p12'
$taskRelease = Join-Path $SigningDirectory 'dsgallery-release.p12'
$taskPasswordFile = Join-Path $SigningDirectory 'password.dpapi'
foreach ($taskRequired in @($InputApk, $taskSigner, $taskLineage, $taskLegacy, $taskRelease, $taskPasswordFile)) {
    if (-not (Test-Path -LiteralPath $taskRequired -PathType Leaf)) { throw "Missing signing input: $taskRequired" }
}
if ([IO.Path]::GetFullPath($InputApk) -eq [IO.Path]::GetFullPath($OutputApk)) { throw 'Use a separate output APK.' }
$taskPreviousJava = $env:JAVA_HOME
$taskPreviousPassword = $env:DSGALLERY_SIGNING_PASSWORD
try {
    $env:JAVA_HOME = $JavaHome
    $taskSecure = ConvertTo-SecureString ((Get-Content -LiteralPath $taskPasswordFile -Raw).Trim())
    $env:DSGALLERY_SIGNING_PASSWORD = ([PSCredential]::new('signing', $taskSecure)).GetNetworkCredential().Password
    & $taskSigner sign --ks $taskLegacy --ks-key-alias legacy --ks-pass env:DSGALLERY_SIGNING_PASSWORD --key-pass env:DSGALLERY_SIGNING_PASSWORD --next-signer --ks $taskRelease --ks-key-alias dsgallery --ks-pass env:DSGALLERY_SIGNING_PASSWORD --key-pass env:DSGALLERY_SIGNING_PASSWORD --lineage $taskLineage --rotation-min-sdk-version 28 --min-sdk-version 28 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $OutputApk $InputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $taskSigner verify --min-sdk-version 28 $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $taskHash = (Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$OutputApk.sha256", "$taskHash  $([IO.Path]::GetFileName($OutputApk))`n")
    Write-Output "Signed and verified: $OutputApk"
} finally {
    $env:JAVA_HOME = $taskPreviousJava
    $env:DSGALLERY_SIGNING_PASSWORD = $taskPreviousPassword
    if ($taskSecure) { $taskSecure.Dispose() }
}
