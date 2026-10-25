$port = 3000

$webRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\frontend\web-admin')).Path.TrimEnd('\')
$processIds = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique)
$projectProcessIds = @()
$foreignProcessIds = @()

foreach ($processId in $processIds) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
    $commandLine = [string]$process.CommandLine
    $normalizedCommandLine = $commandLine.Replace('/', '\')

    if ($normalizedCommandLine -match '(?i)vite' -and
        $normalizedCommandLine.IndexOf($webRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        $projectProcessIds += $processId
    }
    else {
        $foreignProcessIds += $processId
    }
}

if ($foreignProcessIds.Count -gt 0) {
    throw "Port $port is occupied by a process that is not this project's web-admin Vite service (PID(s) $($foreignProcessIds -join ',')). Refusing to stop it or choose another port."
}

foreach ($processId in $projectProcessIds) {
    Stop-Process -Id $processId -Force
    Write-Host "Stopped the existing web-admin Vite process $processId on port $port."

    $deadline = (Get-Date).AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 100
        $stillListening = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            Where-Object OwningProcess -eq $processId)
    } while ($stillListening.Count -gt 0 -and (Get-Date) -lt $deadline)

    if ($stillListening.Count -gt 0) {
        throw "The previous web-admin process $processId did not release port $port in time."
    }
}

Push-Location $webRoot
try {
    npm run dev
}
finally {
    Pop-Location
}
