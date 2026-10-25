$ErrorActionPreference = 'Stop'

# Idempotent local MySQL bootstrap for RailSafe-JX on Windows.
# The script never deletes or resets the railsafe_jx database.

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ConfigDir = Join-Path $HOME '.railsafe-jx'
$ConfigFile = Join-Path $ConfigDir 'mysql.properties'
$DbName = 'railsafe_jx'
$AppUser = 'railsafe_app'
$AdminUser = if ($env:RAILSAFE_MYSQL_ADMIN_USER) { $env:RAILSAFE_MYSQL_ADMIN_USER } else { 'root' }
$OptionFiles = [System.Collections.Generic.List[string]]::new()

function Write-Info([string] $Message) { Write-Host "RailSafe-JX: $Message" }
function Write-Err([string] $Message) { [Console]::Error.WriteLine("RailSafe-JX: $Message") }

function Read-Property([string] $Name) {
    if (-not (Test-Path -LiteralPath $ConfigFile)) { return '' }
    $pattern = '^' + [regex]::Escape($Name) + '=(.*)$'
    foreach ($line in Get-Content -LiteralPath $ConfigFile -ErrorAction SilentlyContinue) {
        if ($line -match $pattern) { return $Matches[1].Trim() }
    }
    return ''
}

function Find-MySqlClient {
    $fromPath = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    $services = @(Get-CimInstance Win32_Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^MySQL(\d+)?$' })
    foreach ($service in $services) {
        $match = [regex]::Match([string]$service.PathName, '^(?:"(?<path>[^"]*mysqld\.exe)"|(?<path>\S*mysqld\.exe))', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($match.Success) {
            $serverPath = $match.Groups['path'].Value
            $candidate = Join-Path (Split-Path -Parent $serverPath) 'mysql.exe'
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }

    $roots = @()
    if ($env:ProgramFiles) { $roots += (Join-Path $env:ProgramFiles 'MySQL') }
    if (${env:ProgramFiles(x86)}) { $roots += (Join-Path ${env:ProgramFiles(x86)} 'MySQL') }
    $roots = $roots | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    foreach ($root in $roots) {
        $candidate = Get-ChildItem -LiteralPath $root -Filter mysql.exe -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    return $null
}

function Get-MySqlServices {
    return @(Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^MySQL(\d+)?$' })
}

function Start-LocalMySql {
    $services = @(Get-MySqlServices)
    if ($services.Count -eq 0) { return $false }
    foreach ($service in $services) {
        if ($service.Status -ne 'Running') {
            try {
                Write-Info "starting Windows service '$($service.Name)'..."
                Start-Service -Name $service.Name
            } catch {
                Write-Err "could not start service '$($service.Name)': $($_.Exception.Message)"
            }
        }
    }
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        $running = @(Get-MySqlServices | Where-Object { $_.Status -eq 'Running' })
        if ($running.Count -gt 0) { return $true }
    }
    return $false
}

function New-ClientOptionFile([string] $Username, [string] $Password) {
    $path = Join-Path $env:TEMP ('railsafe-mysql-' + [guid]::NewGuid().ToString('N') + '.cnf')
    $escapedPassword = $Password.Replace('\', '\\').Replace('"', '\"')
    $content = "[client]`r`nuser=$Username`r`npassword=`"$escapedPassword`"`r`nhost=127.0.0.1`r`nport=3306`r`n"
    [System.IO.File]::WriteAllText($path, $content, [System.Text.Encoding]::ASCII)
    $OptionFiles.Add($path)

    # Restrict this short-lived file to the current Windows user.
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($identity.User)
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
        $identity.User,
        [System.Security.AccessControl.FileSystemRights]::FullControl,
        [System.Security.AccessControl.AccessControlType]::Allow
    )
    [void]$acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $path -AclObject $acl
    return $path
}

function Test-MySqlQuery([string] $ClientPath, [string] $OptionFile, [string] $Username, [string] $Query, [string] $Database = '') {
    $mysqlArgs = @()
    if ($OptionFile) {
        $mysqlArgs += "--defaults-extra-file=$OptionFile"
    } else {
        $mysqlArgs += @('-u', $Username, '--protocol=TCP', '-h', '127.0.0.1', '-P', '3306')
    }
    if ($Database) { $mysqlArgs += ('--database=' + $Database) }
    $mysqlArgs += @('--batch', '--skip-column-names', "--execute=$Query")
    & $ClientPath @mysqlArgs *> $null
    return ($LASTEXITCODE -eq 0)
}

function Invoke-MySql([string] $ClientPath, [string] $OptionFile, [string] $Username, [string] $Query) {
    $mysqlArgs = @()
    if ($OptionFile) {
        $mysqlArgs += "--defaults-extra-file=$OptionFile"
    } else {
        $mysqlArgs += @('-u', $Username, '--protocol=TCP', '-h', '127.0.0.1', '-P', '3306')
    }
    $mysqlArgs += @('--batch', '--skip-column-names', "--execute=$Query")
    & $ClientPath @mysqlArgs
    if ($LASTEXITCODE -ne 0) { throw 'MySQL command failed. Check server status and credentials.' }
}
function Invoke-MySqlFromSchema([string] $ClientPath, [string] $OptionFile, [string] $SchemaPath) {
    if (-not (Test-Path -LiteralPath $SchemaPath)) { throw "Schema file not found: $SchemaPath" }
    # Use the administrator option file for a one-time schema import into a new DB.
    $mysqlArgs = @('--defaults-extra-file=' + $OptionFile, '--batch')
    Get-Content -LiteralPath $SchemaPath -Raw | & $ClientPath @mysqlArgs
    if ($LASTEXITCODE -ne 0) { throw 'Importing the initial database schema failed.' }
}

try {
    if (-not (Test-Path -LiteralPath $ConfigDir)) { New-Item -ItemType Directory -Path $ConfigDir -Force | Out-Null }

    $clientPath = Find-MySqlClient
    if (-not $clientPath) {
        $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
        if (-not $winget) {
            throw "MySQL was not found. Install MySQL Server 8.4 from https://dev.mysql.com/downloads/installer/ (including MySQL Configurator), then rerun scripts\run-railsafe-windows.ps1."
        }
        Write-Info 'MySQL was not found. Starting the MySQL Server installer through WinGet; complete any configuration prompts.'
        & $winget.Source install --id Oracle.MySQL --exact --interactive --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) { throw 'The MySQL installation did not complete successfully.' }
        $clientPath = Find-MySqlClient
        if (-not $clientPath) {
            throw 'MySQL installation finished but mysql.exe was not found. Complete MySQL Configurator, then rerun this launcher.'
        }
    }

    if (-not (Start-LocalMySql)) {
        $services = @(Get-MySqlServices)
        if ($services.Count -eq 0) {
            throw 'MySQL client tools were found, but no MySQL Windows service was found. Open MySQL Configurator, configure a local server on port 3306, and rerun this launcher.'
        }
        throw 'The MySQL service did not start. Check Windows Services or the MySQL error log, then rerun this launcher.'
    }

    # Reuse a working per-user app account. Never rotate credentials unnecessarily.
    $savedUrl = Read-Property 'url'
    $savedUser = Read-Property 'user'
    $savedPassword = Read-Property 'password'
    if ($savedUrl -and $savedUser -and $savedPassword) {
        $savedOptions = New-ClientOptionFile $savedUser $savedPassword
        if (Test-MySqlQuery $clientPath $savedOptions $savedUser 'SELECT 1' $DbName) {
            Write-Info 'saved local MySQL credentials are valid; startup schema initialization will handle missing tables.'
            exit 0
        }
        Write-Info 'saved application credentials did not connect; repairing the local app account.'
    }

    $rootOptionFile = $null
    if (-not (Test-MySqlQuery $clientPath $null $AdminUser 'SELECT 1')) {
        $securePassword = Read-Host "Enter local MySQL administrator password for '$AdminUser' (input hidden)" -AsSecureString
        $pointer = [IntPtr]::Zero
        try {
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
            $adminPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
            $rootOptionFile = New-ClientOptionFile $AdminUser $adminPassword
        } finally {
            if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
            $adminPassword = $null
            $securePassword.Dispose()
        }
        if (-not (Test-MySqlQuery $clientPath $rootOptionFile $AdminUser 'SELECT 1')) {
            throw 'MySQL administrator authentication failed. No database was reset or dropped.'
        }
    }

    $adminOptions = $rootOptionFile
    $databaseAlreadyExists = Invoke-MySql $clientPath $adminOptions $AdminUser "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$DbName'"
    $appPasswordBytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($appPasswordBytes) } finally { $rng.Dispose() }
    $appPassword = -join ($appPasswordBytes | ForEach-Object { $_.ToString('x2') })

    $sql = @"
CREATE DATABASE IF NOT EXISTS $DbName CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$AppUser'@'localhost' IDENTIFIED BY '$appPassword';
ALTER USER '$AppUser'@'localhost' IDENTIFIED BY '$appPassword';
CREATE USER IF NOT EXISTS '$AppUser'@'127.0.0.1' IDENTIFIED BY '$appPassword';
ALTER USER '$AppUser'@'127.0.0.1' IDENTIFIED BY '$appPassword';
GRANT ALL PRIVILEGES ON $DbName.* TO '$AppUser'@'localhost';
GRANT ALL PRIVILEGES ON $DbName.* TO '$AppUser'@'127.0.0.1';
FLUSH PRIVILEGES;
"@
    Invoke-MySql $clientPath $adminOptions $AdminUser $sql | Out-Null

    # Only import the indexed schema when the database did not exist before setup.
    # Existing data/tables are retained; Java initializes missing tables on startup.
    if ([string]$databaseAlreadyExists -eq '0' -and $adminOptions) {
        Invoke-MySqlFromSchema $clientPath $adminOptions (Join-Path $ProjectRoot 'database\schema.sql')
    } elseif ([string]$databaseAlreadyExists -eq '0' -and -not $adminOptions) {
        # Password-free root access: create a temporary root option file for schema import.
        $rootOptionFile = New-ClientOptionFile $AdminUser ''
        Invoke-MySqlFromSchema $clientPath $rootOptionFile (Join-Path $ProjectRoot 'database\schema.sql')
        $adminOptions = $rootOptionFile
    }

    $configTemp = "$ConfigFile.tmp"
    $OptionFiles.Add($configTemp)
    $configText = "url=jdbc:mysql://127.0.0.1:3306/$DbName?serverTimezone=UTC&allowPublicKeyRetrieval=true`r`nuser=$AppUser`r`npassword=$appPassword`r`n"
    [System.IO.File]::WriteAllText($configTemp, $configText, [System.Text.Encoding]::ASCII)
    # Protect the persistent settings file with an ACL for the current user.
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $configAcl = New-Object System.Security.AccessControl.FileSecurity
    $configAcl.SetAccessRuleProtection($true, $false)
    $configAcl.SetOwner($identity.User)
    $configRule = [System.Security.AccessControl.FileSystemAccessRule]::new(
        $identity.User,
        [System.Security.AccessControl.FileSystemRights]::FullControl,
        [System.Security.AccessControl.AccessControlType]::Allow
    )
    [void]$configAcl.AddAccessRule($configRule)
    Set-Acl -LiteralPath $configTemp -AclObject $configAcl
    Move-Item -LiteralPath $configTemp -Destination $ConfigFile -Force

    $verifyOptions = New-ClientOptionFile $AppUser $appPassword
    if (-not (Test-MySqlQuery $clientPath $verifyOptions $AppUser 'SELECT 1' $DbName)) {
        throw 'The RailSafe-JX database account was created, but the connection test failed.'
    }

    Write-Info 'local MySQL setup is complete.'
    Write-Host "Database: $DbName"
    Write-Host "Application account: $AppUser (local connection only)"
    Write-Host "Credentials saved to: $ConfigFile (restricted to the current Windows user)"
    Write-Host 'RailSafe-JX will create any missing required tables automatically on startup.'
    exit 0
}
catch {
    Write-Err $_.Exception.Message
    exit 1
}
finally {
    foreach ($file in $OptionFiles) {
        if (Test-Path -LiteralPath $file) {
            try { Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue } catch {}
        }
    }
}
