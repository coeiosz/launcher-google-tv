# The ambient app reads the Chromecast's cast ID from setupwraith, and its settings screen reads the
# account mode from launcherx. launcherx must stay disabled for dev.tvhome to be the home screen;
# setupwraith stays enabled because Cast loads the device credentials from it. This turns launcherx on
# only while ambient settings are being changed.
#   .\ambiente-config.ps1 -Start   -> enables launcherx (DON'T press Home until -End)
#   .\ambiente-config.ps1 -End     -> disables it again, restores dev.tvhome as home, re-enables setupwraith
# -End is fail-safe: if any step fails or dev.tvhome doesn't end up holding the HOME role, launcherx is re-enabled
# (Google's home keeps working) and the script exits with an error.
param([switch]$Start, [switch]$End, [string]$Tv = "")
. "$PSScriptRoot\comum.ps1"

$setupwraith = "com.google.android.tungsten.setupwraith"
$launcherx   = "com.google.android.apps.tv.launcherx"

if ($Start) {
    $Tv = Get-Tv $Tv
    & $adb -s $Tv shell "pm enable $setupwraith; pm enable $launcherx; am force-stop com.google.android.apps.tv.dreamx"
    Write-Host "Ajuste liberado: Configuracoes > Sistema > Protetor de tela ambiente. Use Voltar, nao Home."
} elseif ($End) {
    $Tv = Get-Tv $Tv
    # Without dev.tvhome installed, disabling launcherx would leave the TV in the black-screen loop.
    $installed = & $adb -s $Tv shell pm path dev.tvhome | Select-String "^package:"
    if (-not $installed) { Write-Host "dev.tvhome não está instalado na TV: nada foi alterado."; exit 1 }

    # setupwraith must be off while the home activity is set and launcherx disabled; it is re-enabled whatever happens.
    & $adb -s $Tv shell "pm disable-user --user 0 $setupwraith && cmd package set-home-activity dev.tvhome/.HomeActivity && pm disable-user --user 0 $launcherx"
    $ok = ($LASTEXITCODE -eq 0)
    & $adb -s $Tv shell "pm enable $setupwraith"
    if ($LASTEXITCODE -ne 0) { $ok = $false }

    # set-home-activity grants the HOME role, so the role holder tells whether it took. (Not resolve-activity:
    # with setupwraith enabled, a HOME intent may resolve to its RecoveryActivity first even when all is well.)
    $casa = (& $adb -s $Tv shell "cmd role get-role-holders android.app.role.HOME" |
        ForEach-Object { "$_".Trim() } | Where-Object { $_ }) -join ";"
    if ($casa -match '^[\w.;]+$') {
        if (($casa -split ";") -notcontains "dev.tvhome") { $ok = $false }
    } elseif ($ok) {
        # Unexpected output is not proof of failure: the exit codes above already passed, so only warn.
        Write-Host "Aviso: nao consegui confirmar o papel HOME (resposta: '$casa')."
    }

    if (-not $ok) {
        & $adb -s $Tv shell "pm enable $launcherx"
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Falhou (papel HOME com: '$casa'). launcherx reativado: a tela inicial do Google voltou a funcionar; dev.tvhome NÃO é a tela inicial."
        } else {
            Write-Host "Falhou e não consegui reativar o launcherx: estado da TV incerto. Rode .\rescue.ps1 ou .\revert-home.ps1."
        }
        exit 1
    }
    Write-Host "Ajuste encerrado: tela inicial dev.tvhome restaurada."
} else {
    Write-Host "Use -Start ou -End."
}
