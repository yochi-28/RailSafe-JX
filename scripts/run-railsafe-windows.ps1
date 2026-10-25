$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $ProjectRoot

if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'pom.xml')) -or
    -not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'mvnw.cmd'))) {
    throw 'Project files pom.xml and mvnw.cmd were not found. Run this script from the extracted RailSafe-JX project.'
}

if (-not $env:RAILSAFE_DB_URL) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'setup-mysql-windows.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $configFile = Join-Path $HOME '.railsafe-jx\mysql.properties'
    if (-not (Test-Path -LiteralPath $configFile)) { throw "MySQL configuration file was not created: $configFile" }
    $config = @{}
    foreach ($line in Get-Content -LiteralPath $configFile) {
        if ($line -match '^([^=]+)=(.*)$') { $config[$Matches[1]] = $Matches[2] }
    }
    if (-not $config.url -or -not $config.user -or -not $config.password) {
        throw "MySQL credentials are incomplete in $configFile. Rerun the setup script."
    }
    $env:RAILSAFE_DB_URL = $config.url
    $env:RAILSAFE_DB_USER = $config.user
    $env:RAILSAFE_DB_PASSWORD = $config.password
    Write-Host 'RailSafe-JX: launching with local MySQL database railsafe_jx.'
} else {
    Write-Host 'RailSafe-JX: using explicitly configured RAILSAFE_DB_URL.'
}

& (Join-Path $ProjectRoot 'mvnw.cmd') javafx:run
exit $LASTEXITCODE
