# 启动后端（自动注入 AI API Key）
#
# 🔴 为什么需要这个脚本：
#   Spring 的 `${VAR:default}` 读的是**进程环境变量**。Windows 用户变量存在注册表里，
#   但**已启动的会话不会自动继承后来又新增/修改的变量** —— 直接 `mvnw spring-boot:run`
#   会读不到 DASHSCOPE_API_KEY，表现为 AI 走「降级模板」或 `未配置 API Key` 日志。
#   本脚本先从注册表读出用户变量，注入当前进程，再启动，保证子进程能继承。
#
# 用法：powershell -ExecutionPolicy Bypass -File tools\start-backend.ps1 [-Port 18080]
param(
  [int]$Port = 18080
)

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$repo = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $repo

Write-Host "=== 启动后端（MediPartner）===" -ForegroundColor Cyan
Write-Host "  仓库: $repo"
Write-Host "  端口: $Port"

# --- 关键：把注册表里的用户变量注入本进程 ---
$kv = @{
  'DASHSCOPE_API_KEY' = 'AI 模型 API Key（qwen/百炼）'
}
foreach ($name in $kv.Keys) {
  $val = [Environment]::GetEnvironmentVariable($name, 'User')
  if (-not $val) { $val = [Environment]::GetEnvironmentVariable($name, 'Machine') }
  if ($val) {
    Set-Item -Path "Env:$name" -Value $val
    Write-Host ("  ✓ 已注入 {0}（{1} 字符）" -f $name, $val.Length) -ForegroundColor Green
  } else {
    Write-Host ("  ⚠ 未找到 {0} —— {1} 将不可用（AI 会走降级模板）" -f $name, $kv[$name]) -ForegroundColor Yellow
  }
}

# --- 端口检查 ---
$occupied = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($occupied) {
  Write-Host ("  ✗ 端口 {0} 已被 PID {1} 占用，先停止它" -f $Port, $occupied[0].OwningProcess) -ForegroundColor Red
  exit 1
}
if ($Port -eq 8080) {
  Write-Host "  ⚠ 8080 被其它服务占用，本项目请用 18080" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "  启动中…（看到 'Started MediPartnerApplication' 即成功）" -ForegroundColor DarkGray
Write-Host "  健康检查：Invoke-RestMethod http://localhost:$Port/api/common/version" -ForegroundColor DarkGray
Write-Host ""

& "$repo\mvnw.cmd" "-Dmaven.repo.local=$repo/.m2repo" spring-boot:run `
  "-Dspring-boot.run.arguments=--server.port=$Port"
