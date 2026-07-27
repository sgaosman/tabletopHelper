param(
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$NoBuild
)

$ErrorActionPreference = "Continue"
$rootDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "=== TabletopHelper Startup ===" -ForegroundColor Cyan

# Step 1: Docker
Write-Host "[1/4] Checking Docker..." -ForegroundColor Yellow

$dockerRunning = $false
try {
    $null = docker info 2>&1
    $dockerRunning = $true
    Write-Host "  OK - Docker is running" -ForegroundColor Green
} catch {
    Write-Host "  [WARN] Docker not detected. Starting Docker Desktop..." -ForegroundColor Red
    try {
        Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
        Write-Host "  Waiting up to 60s for Docker..." -ForegroundColor Yellow
        $timeout = 30
        while ($timeout -gt 0) {
            try {
                $null = docker info 2>&1
                Write-Host "  OK - Docker started" -ForegroundColor Green
                break
            } catch {
                Start-Sleep -Seconds 2
                $timeout -= 2
            }
        }
        if ($timeout -le 0) {
            Write-Host "  [FAIL] Docker didn't start. Launch Docker Desktop manually then re-run." -ForegroundColor Red
            exit 1
        }
    } catch {
        Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

# Step 2: Database
Write-Host "[2/4] Checking PostgreSQL..." -ForegroundColor Yellow

Push-Location $rootDir
try {
    $container = docker ps --filter "name=tabletophelper-db" --filter "status=running" --format "{{.Names}}"
    if ($container -eq "tabletophelper-db") {
        Write-Host "  OK - PostgreSQL container running" -ForegroundColor Green
    } else {
        throw "container not running"
    }
    $ready = docker compose exec -T db pg_isready -U tabletophelper 2>&1
    if ($ready -match "accepting connections") {
        Write-Host "  OK - PostgreSQL accepting connections" -ForegroundColor Green
    } else {
        throw "not accepting"
    }
} catch {
    Write-Host "  Starting PostgreSQL via Docker Compose..." -ForegroundColor Yellow
    try {
        docker compose up -d 2>&1 | Out-Null
        $timeout = 30
        while ($timeout -gt 0) {
            $result = docker compose exec -T db pg_isready -U tabletophelper 2>&1
            if ($result -match "accepting connections") {
                break
            }
            Start-Sleep -Seconds 2
            $timeout -= 2
        }
        if ($timeout -le 0) {
            throw "Database didn't start within 30s"
        }
        Write-Host "  OK - PostgreSQL started" -ForegroundColor Green
    } catch {
        Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red
        Pop-Location
        exit 1
    }
}
Pop-Location

# Step 3: Backend
if (-not $FrontendOnly) {
    Write-Host "[3/4] Checking backend..." -ForegroundColor Yellow

    $port8080 = netstat -ano 2>$null | Select-String ":8080 " | Select-String "LISTENING"
    if ($port8080) {
        Write-Host "  OK - Backend already running on port 8080" -ForegroundColor Green
    } else {
        Write-Host "  Building backend..." -ForegroundColor Yellow
        Push-Location "$rootDir\backend"
        try {
            if (-not $NoBuild) {
                & .\gradlew.bat build -x test
                if ($LASTEXITCODE -ne 0) {
                    throw "Backend build failed (exit code $LASTEXITCODE)"
                }
            }
            Write-Host "  Starting backend..." -ForegroundColor Yellow
            $psi = @{
                FilePath = "cmd.exe"
                ArgumentList = "/c cd /d `"$rootDir\backend`" && .\gradlew.bat bootRun"
                WindowStyle = "Normal"
                PassThru = $true
            }
            $proc = Start-Process @psi

            $timeout = 90
            $started = $false
            while ($timeout -gt 0) {
                try {
                    $response = Invoke-WebRequest -Uri "http://localhost:8080/auth" -UseBasicParsing -TimeoutSec 2
                    $started = $true
                    break
                } catch {
                    if ($_.Exception.Response.StatusCode) {
                        $started = $true
                        break
                    }
                }
                Start-Sleep -Seconds 3
                $timeout -= 3
            }
            if ($started) {
                Write-Host "  OK - Backend running on http://localhost:8080" -ForegroundColor Green
            } else {
                Write-Host "  [WARN] Backend may still be starting (check the terminal window)" -ForegroundColor Yellow
            }
        } catch {
            Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red
        }
        Pop-Location
    }
}

# Step 4: Frontend
if (-not $BackendOnly) {
    Write-Host "[4/4] Checking frontend..." -ForegroundColor Yellow

    $port5173 = netstat -ano 2>$null | Select-String ":5173 " | Select-String "LISTENING"
    if ($port5173) {
        Write-Host "  OK - Frontend dev server already running on port 5173" -ForegroundColor Green
    } else {
        Push-Location "$rootDir\frontend"
        try {
            if (-not (Test-Path "node_modules\.package-lock.json")) {
                Write-Host "  Installing dependencies..." -ForegroundColor Yellow
                npm install
            }
            Write-Host "  Starting frontend dev server..." -ForegroundColor Yellow
            $psi = @{
                FilePath = "cmd.exe"
                ArgumentList = "/c cd /d `"$rootDir\frontend`" && npm run dev"
                WindowStyle = "Normal"
                PassThru = $true
            }
            $proc = Start-Process @psi

            Start-Sleep -Seconds 5
            $port5173 = netstat -ano 2>$null | Select-String ":5173 " | Select-String "LISTENING"
            if ($port5173) {
                Write-Host "  OK - Frontend running on http://localhost:5173" -ForegroundColor Green
            } else {
                Write-Host "  [WARN] Frontend may still be starting (check the terminal window)" -ForegroundColor Yellow
            }
        } catch {
            Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red
        }
        Pop-Location
    }
}

Write-Host "=== Startup Complete ===" -ForegroundColor Cyan
Write-Host "  Frontend: http://localhost:5173" -ForegroundColor White
Write-Host "  Backend:  http://localhost:8080" -ForegroundColor White
Write-Host "  Database: postgresql://localhost:5432" -ForegroundColor White
