# Заливка jar мода на backend для системы автообновлений.
# Использование:
#   powershell -ExecutionPolicy Bypass -File scripts\upload_mod.ps1 -JarPath "dist\mods\shigusdream-0.5.3.jar" -Version "0.5.3"
# Refresh-токен берётся из указанного инстанса (аккаунт должен быть привязан как owner/admin).
param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$BaseUrl = "https://shigusdream-backend.onrender.com",
    [string]$TokensPath = "$env:APPDATA\PrismLauncher\instances\Pivo 11-2\minecraft\config\shigusdream\tokens.json"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $JarPath)) { throw "Jar не найден: $JarPath" }
if (-not (Test-Path $TokensPath)) { throw "tokens.json не найден: $TokensPath" }

$tokens = Get-Content $TokensPath -Raw | ConvertFrom-Json

# 1. Свежий access-токен по refresh-токену
$refreshBody = @{ refresh_token = $tokens.refreshToken } | ConvertTo-Json -Compress
$refresh = Invoke-RestMethod -Uri "$BaseUrl/auth/refresh" -Method Post `
    -ContentType "application/json" -Body $refreshBody
Write-Host "Access-токен получен (живёт $($refresh.expires_in) c)"

# 2. Заливка jar
$bytes = [IO.File]::ReadAllBytes($JarPath)
$req = [System.Net.HttpWebRequest]::Create("$BaseUrl/mod/upload?version=$Version")
$req.Method = "POST"
$req.ContentType = "application/octet-stream"
$req.Headers.Add("Authorization", "Bearer $($refresh.access_token)")
$req.Headers.Add("X-Filename", "shigusdream-$Version.jar")
$req.ContentLength = $bytes.Length
$bytes | ForEach-Object { } | Out-Null
$stream = $req.GetRequestStream()
$stream.Write($bytes, 0, $bytes.Length)
$stream.Close()

$resp = $req.GetResponse()
$result = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
Write-Host "Загружено: $result"

# 3. Проверка
$latest = Invoke-RestMethod -Uri "$BaseUrl/mod/latest"
Write-Host "На сервере теперь: v$($latest.version), $($latest.size) байт, sha256=$($latest.sha256.Substring(0,16))..."
Write-Host "Клиенты обновятся при следующем входе в мир (или по J — переподключиться)."
