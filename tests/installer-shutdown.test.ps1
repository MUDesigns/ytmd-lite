$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$testRoot = Join-Path $tempRoot ('ytmd-installer-test-' + [guid]::NewGuid())
$installRoot = Join-Path $testRoot 'YTMD Lite'
$outsideRoot = Join-Path $testRoot 'unrelated'
$processes = @()
try {
    New-Item -ItemType Directory -Path (Join-Path $installRoot 'resources'),$outsideRoot | Out-Null
    $backend = Join-Path $installRoot 'resources\ytmd-backend.exe'
    Add-Type -TypeDefinition 'using System.Threading; class Helper { static void Main(string[] args) { Thread.Sleep(60000); } }' -OutputAssembly $backend -OutputType ConsoleApplication
    $node = Join-Path $installRoot 'resources\node.exe'
    $otherNode = Join-Path $outsideRoot 'node.exe'
    Copy-Item -LiteralPath $backend -Destination $node
    Copy-Item -LiteralPath $backend -Destination $otherNode
    foreach ($exe in @($backend, $node, $otherNode)) {
        $processes += Start-Process -FilePath $exe -WindowStyle Hidden -PassThru
    }
    $python = Join-Path $outsideRoot 'python.exe'
    Copy-Item -LiteralPath $backend -Destination $python
    $installedScript = Join-Path $installRoot 'python-backend\server.py'
    $otherScript = Join-Path $outsideRoot 'server.py'
    $processes += Start-Process -FilePath $python -ArgumentList ('"' + $installedScript + '"') -WindowStyle Hidden -PassThru
    $processes += Start-Process -FilePath $python -ArgumentList ('"' + $otherScript + '"') -WindowStyle Hidden -PassThru
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PSScriptRoot\..\src-tauri\windows\stop-backend.ps1" -InstallDir $installRoot
    if ($LASTEXITCODE -ne 0) { throw 'Installer cleanup failed' }
    if (-not $processes[0].WaitForExit(2000) -or -not $processes[1].WaitForExit(2000)) {
        throw 'Installed helper still running'
    }
    if ($processes[2].HasExited) { throw 'Unrelated Node process was terminated' }
    if (-not $processes[3].WaitForExit(2000)) { throw 'Installed Python script still running' }
    if ($processes[4].HasExited) { throw 'Unrelated Python process was terminated' }
    # Already-stopped processes must be a successful no-op.
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PSScriptRoot\..\src-tauri\windows\stop-backend.ps1" -InstallDir $installRoot
    if ($LASTEXITCODE -ne 0) { throw 'Repeated cleanup failed' }
    Write-Output 'Installer shutdown passed: installed helpers stopped, unrelated process preserved.'
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if (-not $resolved.StartsWith($tempRoot + '\ytmd-installer-test-')) { throw 'Unexpected cleanup path' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
