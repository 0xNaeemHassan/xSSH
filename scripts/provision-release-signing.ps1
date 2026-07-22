[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$keystoreDirectory = Join-Path $repoRoot 'keystore'
$keystorePath = Join-Path $keystoreDirectory 'xssh-release.jks'
$localPropertiesPath = Join-Path $repoRoot 'local.properties'
$keyAlias = 'xssh'

if (Test-Path -LiteralPath $keystorePath) {
    throw "Refusing to replace the existing release key: $keystorePath"
}
if (-not (Test-Path -LiteralPath $localPropertiesPath)) {
    throw "Missing local.properties: $localPropertiesPath"
}

$localProperties = [IO.File]::ReadAllText($localPropertiesPath)
if ($localProperties -match '(?m)^xssh\.signing\.') {
    throw 'Refusing to replace existing xSSH release-signing properties.'
}

$javaHomeCandidates = @(
    $env:JAVA_HOME,
    'C:\Program Files\Android\Android Studio\jbr'
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$keytool =
    $javaHomeCandidates |
    ForEach-Object { Join-Path $_ 'bin\keytool.exe' } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if (-not $keytool) {
    throw 'keytool.exe was not found. Set JAVA_HOME to JDK 17 or use Android Studio embedded JDK.'
}

$secretBytes = [byte[]]::new(32)
$randomNumberGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $randomNumberGenerator.GetBytes($secretBytes)
} finally {
    $randomNumberGenerator.Dispose()
}
$password =
    [Convert]::ToBase64String($secretBytes).
        TrimEnd('=').
        Replace('+', '-').
        Replace('/', '_')

[IO.Directory]::CreateDirectory($keystoreDirectory) | Out-Null
$keytoolArguments = @(
    '-genkeypair',
    '-keystore', $keystorePath,
    '-storetype', 'PKCS12',
    '-storepass', $password,
    '-alias', $keyAlias,
    '-keypass', $password,
    '-keyalg', 'EC',
    '-groupname', 'secp256r1',
    '-sigalg', 'SHA256withECDSA',
    '-validity', '10000',
    '-dname', 'CN=xSSH Release, O=xSSH, C=PK',
    '-noprompt'
)

try {
    & $keytool @keytoolArguments
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE"
    }

    $separator = if ($localProperties.EndsWith("`n")) { '' } else { "`r`n" }
    $signingProperties = @(
        '',
        '# Local xSSH release signing. Never commit this file or the keystore.',
        'xssh.signing.storeFile=keystore/xssh-release.jks',
        "xssh.signing.storePassword=$password",
        "xssh.signing.keyAlias=$keyAlias",
        "xssh.signing.keyPassword=$password",
        ''
    ) -join "`r`n"
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText(
        $localPropertiesPath,
        $localProperties + $separator + $signingProperties,
        $utf8WithoutBom
    )
} catch {
    if (Test-Path -LiteralPath $keystorePath) {
        [IO.File]::Delete($keystorePath)
    }
    throw
} finally {
    [Array]::Clear($secretBytes, 0, $secretBytes.Length)
    $password = $null
}

Write-Output "Created release signing key: $keystorePath"
Write-Output "Configured ignored local signing properties: $localPropertiesPath"
Write-Output 'Back up the keystore and its local.properties credentials now; losing them prevents future upgrades.'
