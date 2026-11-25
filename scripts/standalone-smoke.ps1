# Smoke test of the Windows standalone package (docs/standalone.md), the counterpart of standalone-smoke.sh:
# removes Java from this process's PATH and JAVA_HOME, unpacks the package into a folder whose name has a space,
# starts DistriCompute.cmd, checks that it listens only on 127.0.0.1, signs in with the initial password it prints,
# checks the two local workers, runs a distributed experiment to a verified result, checks that a second start only
# reports the running instance, and that the data survives a forced stop and a restart.
#   pwsh -File scripts\standalone-smoke.ps1 -Archive dist\DistriCompute-<version>-windows-x64.zip
param([Parameter(Mandatory = $true)][string]$Archive, [int]$Port = 18080)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
@(Get-ChildItem Env: | Where-Object { $_.Name -like 'JAVA_HOME_*' }) | ForEach-Object { Remove-Item "Env:$($_.Name)" }
$env:Path = ($env:Path -split ';' | Where-Object { $_ -and -not (Test-Path (Join-Path $_ 'java.exe')) }) -join ';'
if (Get-Command java -ErrorAction SilentlyContinue) { throw 'java is still on the PATH' }
Write-Host 'No java on the PATH, no JAVA_HOME'

# RUNNER_TEMP on CI: the user's temporary folder can be an 8.3 short path there.
$temp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$work = Join-Path $temp ('districompute smoke ' + [guid]::NewGuid())
$dir = Join-Path $work 'Distri Compute'
$env:DISTRICOMPUTE_DATA_DIR = Join-Path $work 'my data'
$env:DISTRICOMPUTE_HTTP_PORT = "$Port"
$env:DISTRICOMPUTE_NO_PAUSE = '1'
Expand-Archive -Path $Archive -DestinationPath $dir
$launcher = (Get-ChildItem -Path $dir -Recurse -Filter DistriCompute.cmd | Select-Object -First 1).FullName
$base = "http://127.0.0.1:$Port"
$script:starts = 0

function Start-App {
    $script:starts++
    $script:log = Join-Path $work "console-$($script:starts).log"
    $started = Get-Date
    $script:proc = Start-Process -FilePath cmd.exe -NoNewWindow -PassThru `
        -ArgumentList '/c', "`"`"$launcher`" --districompute.standalone.open-browser=false`"" `
        -RedirectStandardOutput $script:log -RedirectStandardError (Join-Path $work "console-$($script:starts).err")
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $script:log) -and (Select-String -Path $script:log -SimpleMatch 'DistriCompute is running at' -Quiet)) {
            Write-Host ("Ready after {0:N0} s" -f ((Get-Date) - $started).TotalSeconds)
            return
        }
        if ($script:proc.HasExited) { Get-Content $script:log; throw "The application exited ($($script:proc.ExitCode))" }
        Start-Sleep -Seconds 1
    }
    Get-Content $script:log
    throw 'Not ready after 180 s'
}

function Get-AppJava {
    @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like "*$dir*" })
}

function Stop-App {
    Get-AppJava | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    $script:proc.WaitForExit(30000) | Out-Null
}

function Get-Token { $script:session.Cookies.GetCookies([Uri]$base)['XSRF-TOKEN'].Value }

function Connect-App([string]$Password) {
    $script:session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-WebRequest "$base/api/v1/auth/session" -WebSession $script:session -UseBasicParsing | Out-Null
    Invoke-WebRequest "$base/api/v1/auth/login" -Method Post -WebSession $script:session -UseBasicParsing `
        -Headers @{ 'X-XSRF-TOKEN' = (Get-Token) } -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ username = 'admin'; password = $Password } | Out-Null
}

function Invoke-Api([string]$Method, [string]$Path, $Body) {
    $request = @{ Uri = "$base$Path"; Method = $Method; WebSession = $script:session
        Headers = @{ 'X-XSRF-TOKEN' = (Get-Token) } }
    if ($null -ne $Body) { $request.Body = ($Body | ConvertTo-Json -Depth 10); $request.ContentType = 'application/json' }
    Invoke-RestMethod @request
}

try {
    Write-Host '== First start'
    Start-App
    $java = Get-AppJava
    if ($java.Count -ne 1) { throw "$($java.Count) java processes of the package" }
    $listening = @(Get-NetTCPConnection -State Listen -OwningProcess $java[0].ProcessId)
    $outside = $listening | Where-Object { $_.LocalAddress -ne '127.0.0.1' }
    if ($outside) { throw "Listening beyond this computer: $($outside | Out-String)" }
    Write-Host "Listening only on 127.0.0.1 (ports $(($listening.LocalPort | Sort-Object) -join ', '))"

    $match = Select-String -Path $script:log -Pattern 'First sign-in:  user admin   password (\S+)'
    if (-not $match) { Get-Content $script:log; throw 'No initial password printed' }
    $password = $match.Matches[0].Groups[1].Value
    Connect-App $password
    Write-Host 'Signed in'

    # Invoke-RestMethod may pass a JSON array on as one object: ForEach-Object unrolls it. The worker records are
    # updated from the coordinator's events, so give them a moment.
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $active = @(Invoke-Api GET '/api/v1/workers' | ForEach-Object { $_ } |
            Where-Object { $_.status -eq 'ACTIVE' -and $_.workerId -like 'local-*' })
        if ($active.Count -eq 2) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    if ($active.Count -ne 2) { throw "$($active.Count) active local workers" }
    Write-Host '2 local workers active'

    $task = @{ algorithm = 'matrix.multiply'; parameters = @{ rowsA = 128; colsA = 96; colsB = 64; seed = 3 }
        execution = @{ mode = 'DISTRIBUTED_CPU' } }
    $id = (Invoke-Api POST '/api/v1/experiments' @{ name = 'smoke'; task = $task }).summary.id
    $deadline = (Get-Date).AddSeconds(120)
    do {
        Start-Sleep -Seconds 1
        $detail = Invoke-Api GET "/api/v1/experiments/$id"
    } while ((@('COMPLETED', 'FAILED', 'CANCELLED') -notcontains $detail.summary.status) -and ((Get-Date) -lt $deadline))
    if ($detail.summary.status -ne 'COMPLETED') { throw "Experiment $($detail.summary.status)" }
    if (-not $detail.runs[0].verificationPassed) { throw 'Result not verified' }
    Write-Host 'Distributed experiment completed and verified'

    Write-Host '== Second start while running'
    $secondLog = Join-Path $work 'second-start.txt'
    $second = Start-Process -FilePath cmd.exe -NoNewWindow -PassThru -Wait -ArgumentList '/c', "`"`"$launcher`"`"" `
        -RedirectStandardOutput $secondLog -RedirectStandardError (Join-Path $work 'second-start.err')
    $text = Get-Content $secondLog -Raw
    if ($second.ExitCode -ne 0 -or $text -notmatch 'already running') { throw "Second start ($($second.ExitCode)): $text" }
    Write-Host 'Reported the running instance'

    Write-Host '== Forced stop and restart'
    Stop-App
    Start-App
    Connect-App $password
    $kept = (Invoke-Api GET "/api/v1/experiments/$id").summary.status
    if ($kept -ne 'COMPLETED') { throw "Experiment after restart: $kept" }
    Write-Host 'Data kept across the restart'

    foreach ($file in Get-ChildItem $work -Filter 'console-*.log') {
        $errors = Select-String -Path $file.FullName -CaseSensitive -Pattern ' ERROR ', 'Exception'
        if ($errors) { throw "Errors in $($file.Name): $($errors | Out-String)" }
    }
    Write-Host 'PASS'
} finally {
    if ($script:proc) { Stop-App }
    $logs = Join-Path $temp 'districompute-smoke-logs'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    Copy-Item (Join-Path $work 'console-*') $logs -ErrorAction SilentlyContinue
    Copy-Item (Join-Path $work 'second-start.*') $logs -ErrorAction SilentlyContinue
}
