# EXL Interview Platform - Windows development prerequisites
# Run in PowerShell as Administrator. Installs only missing tools.
$ErrorActionPreference = 'Stop'
Write-Host 'EXL Interview Platform - Windows setup' -ForegroundColor Cyan
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) { throw 'Windows Package Manager (winget) is missing. Install App Installer from Microsoft Store first.' }

function Refresh-EnvPath {
  $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
}
function Get-VersionText([string]$command, [string]$argument) {
  if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { return '' }
  try { return ((& $command $argument 2>&1) | Out-String).Trim() } catch { return '' }
}
function Ensure-Package([string]$label,[string]$command,[string]$versionArg,[string]$packageId) {
  $found = Get-VersionText $command $versionArg
  if ($found) {
    Write-Host "[FOUND] $label : $($found -split "`n" | Select-Object -First 1)" -ForegroundColor Green
    return
  }
  Write-Host "[INSTALL] $label ($packageId)" -ForegroundColor Yellow
  & winget install --id $packageId --exact --source winget --accept-package-agreements --accept-source-agreements
  if ($LASTEXITCODE -ne 0) { Write-Warning "Installation of $label returned code $LASTEXITCODE. Check winget output." }
  Refresh-EnvPath
}

# Angular 18 in the starter project supports Node 20 and 22, but not Node 24.
$node = Get-VersionText 'node' '--version'
if ($node -and $node -match '^v(20|22)\.') {
  Write-Host "[FOUND] Compatible Node.js $node" -ForegroundColor Green
} elseif ($node) {
  Write-Warning "Installed Node.js $node is not an Angular 18 supported version. Switch to Node 22 before running npm install; do not install a second system Node concurrently."
} else {
  Ensure-Package 'Node.js 22' 'node' '--version' 'OpenJS.NodeJS.22'
}

$javaText = Get-VersionText 'java' '-version'
if ($javaText -match 'version "21[.\"]' -or $javaText -match 'openjdk 21[.\s]') {
  Write-Host '[FOUND] Java 21' -ForegroundColor Green
} elseif ($javaText) {
  Write-Warning 'Java is present but not Java 21. Installing Java 21; verify that JAVA_HOME and PATH select it.'
  & winget install --id EclipseAdoptium.Temurin.21.JDK --exact --source winget --accept-package-agreements --accept-source-agreements
  Refresh-EnvPath
} else {
  Ensure-Package 'Eclipse Temurin Java 21' 'java' '-version' 'EclipseAdoptium.Temurin.21.JDK'
}

# Use the Temurin Java 21 directory for JAVA_HOME when found.
$adoptium = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
if (Test-Path $adoptium) {
  $jdk = Get-ChildItem $adoptium -Directory | Where-Object { $_.Name -match '^jdk-21' } | Sort-Object Name -Descending | Select-Object -First 1
  if ($jdk) {
    [Environment]::SetEnvironmentVariable('JAVA_HOME',$jdk.FullName,'User')
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$($jdk.FullName)\bin;" + $env:Path
    Write-Host "JAVA_HOME = $($jdk.FullName)" -ForegroundColor Green
  }
}

$mavenText = Get-VersionText 'mvn' '--version'
if ($mavenText -match 'Apache Maven (\d+)\.(\d+)') {
  if ([int]$Matches[1] -gt 3 -or ([int]$Matches[1] -eq 3 -and [int]$Matches[2] -ge 9)) {
    Write-Host '[FOUND] Maven 3.9+' -ForegroundColor Green
  } else { Write-Warning 'Older Maven detected; update Maven to 3.9+ manually or via winget upgrade Apache.Maven.' }
} else { Ensure-Package 'Apache Maven' 'mvn' '--version' 'Apache.Maven' }

Ensure-Package 'Git' 'git' '--version' 'Git.Git'
# VS Code is recommended, not required for running the application.
if (-not (Get-Command code -ErrorAction SilentlyContinue)) {
  Write-Host '[OPTIONAL] Installing Visual Studio Code' -ForegroundColor Yellow
  & winget install --id Microsoft.VisualStudioCode --exact --source winget --accept-package-agreements --accept-source-agreements
  Refresh-EnvPath
}

Write-Host "`nChecking installed tools:" -ForegroundColor Cyan
foreach ($cmd in @(@('node','--version'),@('npm','--version'),@('java','-version'),@('javac','-version'),@('mvn','--version'),@('git','--version'))) {
  $result = Get-VersionText $cmd[0] $cmd[1]
  if ($result) { Write-Host ("{0}: {1}" -f $cmd[0], (($result -split "`n")[0])) }
  else { Write-Warning "$($cmd[0]) not available in this terminal; open a new PowerShell window and retry." }
}
Write-Host "`nNext: unzip the Milestone 1 source, open PowerShell in its candidate-app folder and run 'npm install'." -ForegroundColor Cyan
Write-Host "For the backend, open another terminal in backend and run 'mvn spring-boot:run'." -ForegroundColor Cyan
Write-Host 'Docker, PostgreSQL and Python are NOT required for the current Milestone 1; add them when those services are implemented.' -ForegroundColor DarkGray
