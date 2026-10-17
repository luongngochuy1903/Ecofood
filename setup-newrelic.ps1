$ErrorActionPreference = "Stop"

$NewRelicDir = Join-Path $PSScriptRoot "newrelic"
$ZipPath = Join-Path $PSScriptRoot "newrelic-java.zip"
$DownloadUrl = "https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic-java.zip"

Write-Host "Setting up New Relic Java Agent..."

# Tạo folder nếu chưa có
if (!(Test-Path $NewRelicDir)) {
    New-Item -ItemType Directory -Path $NewRelicDir | Out-Null
}

# Nếu đã có newrelic.jar thì không download lại
$AgentJar = Join-Path $NewRelicDir "newrelic.jar"

if (Test-Path $AgentJar) {
    Write-Host "New Relic Agent already exists:"
    Write-Host $AgentJar
    exit 0
}

Write-Host "Downloading New Relic Java Agent..."

Invoke-WebRequest `
    -Uri $DownloadUrl `
    -OutFile $ZipPath

Write-Host "Extracting..."

$TempDir = Join-Path $PSScriptRoot "newrelic-temp"

if (Test-Path $TempDir) {
    Remove-Item $TempDir -Recurse -Force
}

Expand-Archive `
    -Path $ZipPath `
    -DestinationPath $TempDir `
    -Force

# New Relic zip thường extract thành newrelic/
$ExtractedDir = Join-Path $TempDir "newrelic"

if (!(Test-Path $ExtractedDir)) {
    throw "Cannot find extracted New Relic directory."
}

# Copy jar và các file cần thiết.
# Không overwrite newrelic.yml của project.
Get-ChildItem $ExtractedDir | ForEach-Object {

    if ($_.Name -ne "newrelic.yml") {
        Copy-Item `
            $_.FullName `
            $NewRelicDir `
            -Recurse `
            -Force
    }
}

# Xóa file tạm
Remove-Item $ZipPath -Force
Remove-Item $TempDir -Recurse -Force

Write-Host ""
Write-Host "New Relic setup completed."
Write-Host "Agent:"
Write-Host $AgentJar
Write-Host ""
Write-Host "IntelliJ VM option:"
Write-Host "-javaagent:./newrelic/newrelic.jar"