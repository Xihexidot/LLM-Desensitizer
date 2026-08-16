param(
    [string]$JMeterCommand = "jmeter",
    [string]$Host = "127.0.0.1",
    [int]$Port = 8080,
    [int]$UsersDesensitize = 20,
    [int]$UsersPlugin = 20,
    [int]$UsersMonitor = 10
)

$plan = Join-Path $PSScriptRoot "api_safety_gateway_perf_plan.jmx"
$resultsDir = Join-Path $PSScriptRoot "results"
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$jtl = Join-Path $resultsDir "jmeter_$timestamp.jtl"
$html = Join-Path $resultsDir "jmeter_$timestamp-html"

if (-not (Test-Path $resultsDir)) {
    New-Item -ItemType Directory -Path $resultsDir | Out-Null
}

if (-not (Get-Command $JMeterCommand -ErrorAction SilentlyContinue)) {
    throw "未找到 JMeter 命令：$JMeterCommand。请先安装 JMeter，或通过 -JMeterCommand 指定其可执行文件路径。"
}

& $JMeterCommand `
    -n `
    -t $plan `
    -l $jtl `
    -e `
    -o $html `
    -Jhost=$Host `
    -Jport=$Port `
    -Jusers_desensitize=$UsersDesensitize `
    -Jusers_plugin=$UsersPlugin `
    -Jusers_monitor=$UsersMonitor

Write-Host "JTL 结果: $jtl"
Write-Host "HTML 报告: $html"
