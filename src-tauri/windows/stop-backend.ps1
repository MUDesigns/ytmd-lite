param([Parameter(Mandatory = $true)][string]$InstallDir)

$ErrorActionPreference = 'Stop'

function Normalize-ExecutablePath([string]$Value) {
    if (-not $Value) { return '' }
    if ($Value.StartsWith('\\?\')) { $Value = $Value.Substring(4) }
    return [IO.Path]::GetFullPath($Value).TrimEnd('\')
}

$installRoot = Normalize-ExecutablePath $InstallDir
if (-not [IO.Path]::IsPathRooted($InstallDir) -or $installRoot -eq [IO.Path]::GetPathRoot($installRoot).TrimEnd('\')) {
    throw 'Expected a specific, absolute application installation directory'
}
$executables = @(
    'ytmd-backend.exe', 'resources\ytmd-backend.exe',
    'node.exe', 'resources\node.exe'
) | ForEach-Object { Join-Path $installRoot $_ }
$scripts = @('python-backend\server.py', '_up_\python-backend\server.py', 'resources\python-backend\server.py') |
    ForEach-Object { [regex]::Escape((Join-Path $installRoot $_)) }

function Get-InstalledHelpers {
    Get-CimInstance Win32_Process -Filter "Name = 'ytmd-backend.exe' OR Name = 'node.exe' OR Name = 'python.exe' OR Name = 'pythonw.exe' OR Name = 'py.exe'" |
        Where-Object {
            $executable = Normalize-ExecutablePath $_.ExecutablePath
            if ($executable -in $executables) { return $true }
            if ($_.Name -in @('python.exe', 'pythonw.exe', 'py.exe')) {
                foreach ($script in $scripts) {
                    if ($_.CommandLine -match ('(?i)(?:^|[\s"])' + $script + '(?:[\s"]|$)')) { return $true }
                }
            }
            return $false
        }
}

try {
    # Path matching is deliberate: never terminate unrelated Python/Node apps.
    # Enumerate again after stopping in case a bootloader was still spawning.
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $helpers = @(Get-InstalledHelpers)
        if ($helpers.Count -eq 0) { exit 0 }
        foreach ($helper in $helpers) {
            $process = Get-Process -Id $helper.ProcessId -ErrorAction SilentlyContinue
            if (-not $process) { continue }
            if ((Normalize-ExecutablePath $process.Path) -ne (Normalize-ExecutablePath $helper.ExecutablePath)) { continue }
            try {
                Stop-Process -InputObject $process -Force
                if (-not $process.WaitForExit(5000)) { throw 'Helper did not exit' }
            } catch {
                if (-not $process.HasExited) { throw }
            } finally {
                $process.Dispose()
            }
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    if (@(Get-InstalledHelpers).Count -ne 0) { throw 'Application helpers are still running' }
    exit 0
} catch {
    Write-Output "Could not stop YTMD Lite helpers: $($_.Exception.Message)"
    exit 1
}
