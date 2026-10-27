[CmdletBinding()]
param([switch]$InstallPrerequisites,[switch]$BuildImage)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($InstallPrerequisites){
    Write-Host 'Install WSL and Docker Desktop using the official Windows package sources. Review any installer agreements and prompts.'
    & winget install --id Microsoft.WSL --exact
    if($LASTEXITCODE -ne 0){throw 'WSL installation did not complete. Run an administrator terminal, complete WSL setup, and restart Windows if requested.'}
    & winget install --id Docker.DockerDesktop --exact
    if($LASTEXITCODE -ne 0){throw 'Docker Desktop installation did not complete. Complete its installer, start Docker Desktop, and rerun this script with -BuildImage.'}
    Write-Host 'Start Docker Desktop, review its agreement, and complete Windows restart/setup prompts if needed. No restart is performed by this script.'
}
if($BuildImage){
    $dockerCommand=Get-Command docker.exe -ErrorAction SilentlyContinue
    if(!$dockerCommand){throw 'Docker CLI is not on PATH. Start Docker Desktop and open a new terminal first.'}
    $engine=& $dockerCommand.Source info --format '{{.OSType}}'
    if($LASTEXITCODE -ne 0 -or $engine -ne 'linux'){throw 'Start Docker Desktop with Linux containers before building the runner.'}
    & $dockerCommand.Source build --tag interview-java-runner:1 --file (Join-Path $projectRoot 'coding-runner\Dockerfile') (Join-Path $projectRoot 'coding-runner')
    if($LASTEXITCODE -ne 0){throw 'Runner image build failed.'}
    Write-Host 'Runner built. Enable INTERVIEW_CODING_ENABLED=true and restart the Java template preview backend.'
}
if(!$InstallPrerequisites -and !$BuildImage){Write-Host 'Use -InstallPrerequisites to install WSL/Docker, then -BuildImage after Docker Desktop is ready.'}
