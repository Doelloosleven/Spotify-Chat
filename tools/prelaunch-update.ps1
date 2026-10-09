# Pre-launch command for Prism Launcher / MultiMC: installs a new Spotify Chat release right before Minecraft
# starts. (In game the jar is locked, so the in-game auto-update can only swap it when the game closes.)
#
# Settings > Custom commands > Pre-launch command:
#   powershell -NoProfile -ExecutionPolicy Bypass -File "C:\path\to\prelaunch-update.ps1"
#
# The launcher passes the instance's folder and Java in INST_MC_DIR and INST_JAVA. The updater runs from a copy
# of the installed jar (so that jar can be replaced) and checks the download's signature before using it.
# Instances without Spotify Chat are skipped, and it never stops the game from starting.
try {
    $mods = Join-Path $env:INST_MC_DIR 'mods'
    $jar = Get-ChildItem -LiteralPath $mods -File -ErrorAction Stop |
        Where-Object Name -match '^spotify-chat-[\d.]+\+[\d.]+\.jar$' | Select-Object -First 1
    if ($jar) {
        # java.exe, not javaw.exe: PowerShell doesn't wait for windowed programs
        $java = if ($env:INST_JAVA) { $env:INST_JAVA -replace 'javaw\.exe$', 'java.exe' } else { 'java' }
        $copy = Join-Path ([IO.Path]::GetTempPath()) "spotify-chat-prelaunch-$PID.jar"
        Copy-Item -LiteralPath $jar.FullName -Destination $copy -Force
        try {
            & $java -cp $copy dev.spotifychat.PreLaunchUpdate $mods 2>$null
            if ($LASTEXITCODE -ne 0) { Write-Output 'Spotify Chat: this version updates in game only (1.5.2+ can update here)' }
        } finally {
            Remove-Item -LiteralPath $copy -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Output "Spotify Chat: update skipped ($($_.Exception.Message))"
}
exit 0
