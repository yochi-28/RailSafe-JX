[CmdletBinding()]
param(
    [switch]$PortableOnly,
    [ValidateSet('exe', 'msi')]
    [string]$InstallerType = 'exe'
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $ProjectRoot

if ($env:OS -ne 'Windows_NT') { throw 'This builder must run on Windows. jpackage does not cross-build Windows installers.' }
if (-not (Get-Command jpackage.exe -ErrorAction SilentlyContinue)) {
    throw 'JDK 21 including jpackage.exe is required. Install a full JDK 21 and configure JAVA_HOME/PATH.'
}
$Version = [regex]::Match((Get-Content -Raw 'pom.xml'), '<version>([^<]+)</version>').Groups[1].Value
if (-not $Version) { throw 'Could not read the application version from pom.xml.' }
$JarName = "railsafe-jx-$Version.jar"
$InputDir = Join-Path $ProjectRoot 'target\installer-input'
$LibDir = Join-Path $InputDir 'lib'
$DistDir = Join-Path $ProjectRoot 'dist\windows'
$Icon = Join-Path $ProjectRoot 'packaging\icons\railsafe.ico'

# Maven resolves the Windows-specific JavaFX native jars on this Windows host.
& (Join-Path $ProjectRoot 'mvnw.cmd') clean package dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$LibDir"
if ($LASTEXITCODE -ne 0) { throw "Maven packaging failed with exit code $LASTEXITCODE." }
New-Item -ItemType Directory -Force -Path $InputDir, $DistDir | Out-Null
Copy-Item -Force (Join-Path $ProjectRoot "target\$JarName") (Join-Path $InputDir $JarName)

# jpackage accepts a main JAR rather than a --class-path option. Add a manifest
# Class-Path relative to app/lib so copied JavaFX/JDBC dependencies are available.
$DependencyJars = @(Get-ChildItem -LiteralPath $LibDir -Filter '*.jar' -File | Sort-Object Name)
if ($DependencyJars.Count -eq 0) { throw "No runtime dependency jars were copied to $LibDir." }
$ClassPathEntries = @($DependencyJars | ForEach-Object { "lib/$($_.Name)" })
$Manifest = Join-Path $ProjectRoot 'target\installer-manifest.mf'
# JAR manifest physical lines are limited to 72 bytes. Fold Class-Path at spaces.
$ManifestText = "Class-Path:"
$LineLength = 11
$EntryCount = 0
foreach ($entry in $ClassPathEntries) {
    if (($LineLength + 1 + $entry.Length) -gt 69) {
        if ($EntryCount -eq 0) { throw "Dependency path is too long for a manifest entry: $entry" }
        # Preserve the token separator before the continuation; the manifest parser removes the continuation's leading blank.
        $ManifestText += " `r`n "
        $LineLength = 1
    } else {
        $ManifestText += ' '
        $LineLength++
    }
    $ManifestText += $entry
    $LineLength += $entry.Length
    $EntryCount++
}
$ManifestText += "`r`n`r`n"
[System.IO.File]::WriteAllText($Manifest, $ManifestText, [System.Text.Encoding]::ASCII)
& jar.exe --update --file (Join-Path $InputDir $JarName) --manifest $Manifest
if ($LASTEXITCODE -ne 0) { throw "Could not write dependency class path into app JAR (exit $LASTEXITCODE)." }

$PortableDir = Join-Path $DistDir 'portable'
$InstallerDir = Join-Path $DistDir 'installer'
Remove-Item -Recurse -Force $PortableDir, $InstallerDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $PortableDir, $InstallerDir | Out-Null
$Common = @(
    '--name', 'RailSafe-JX',
    '--app-version', $Version,
    '--vendor', 'RailSafe-JX',
    '--description', 'Concurrent railway simulation and deadlock resolution demonstrator',
    '--input', $InputDir,
    '--main-jar', $JarName,
    '--main-class', 'com.railsafe.jx.RailSafeLauncher',
    '--icon', $Icon,
    '--java-options', '-Dfile.encoding=UTF-8'
)

& jpackage.exe --type app-image @Common --dest $PortableDir
if ($LASTEXITCODE -ne 0) { throw "jpackage portable image failed with exit code $LASTEXITCODE." }

if (-not $PortableOnly) {
    # jpackage's Windows EXE/MSI creation needs WiX Toolset installed and on PATH.
    if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
        throw 'Portable app was built, but an installer needs WiX Toolset 3.x (candle.exe and light.exe on PATH). Install WiX, then rerun this script. Or use -PortableOnly.'
    }
    $InstallerOptions = @('--win-menu', '--win-shortcut', '--win-per-user-install', '--win-menu-group', 'RailSafe-JX')
    & jpackage.exe --type $InstallerType @Common @InstallerOptions --dest $InstallerDir
    if ($LASTEXITCODE -ne 0) { throw "jpackage installer build failed with exit code $LASTEXITCODE." }
}

Write-Host 'Build complete:'
Write-Host "  Portable app: $PortableDir\RailSafe-JX\RailSafe-JX.exe"
if (-not $PortableOnly) { Write-Host "  Installer:    $InstallerDir\RailSafe-JX-$Version.$InstallerType" }
Write-Host 'Database note: first run uses saved local MySQL settings if present; otherwise the app uses its embedded H2 fallback. For automatic MySQL setup, run run-railsafe.cmd once from the source project.'
