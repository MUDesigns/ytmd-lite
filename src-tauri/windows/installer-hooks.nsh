!define YTMD_STOP_BACKEND_SCRIPT "${__FILEDIR__}\stop-backend.ps1"

!macro YTMD_STOP_HELPERS
  Push $0
  Push $1
  InitPluginsDir
  File /oname=$PLUGINSDIR\ytmd-stop-backend.ps1 "${YTMD_STOP_BACKEND_SCRIPT}"
  nsExec::ExecToStack '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "$PLUGINSDIR\ytmd-stop-backend.ps1" -InstallDir "$INSTDIR"'
  Pop $0
  Pop $1
  ${If} $0 != 0
    DetailPrint "$1"
    MessageBox MB_OK|MB_ICONSTOP "Could not close YTMD Lite background processes. Close the app and try again." /SD IDOK
    Abort
  ${EndIf}
  Pop $1
  Pop $0
!macroend

; This also repairs upgrades FROM older releases whose updater skipped cleanup.
!macro NSIS_HOOK_PREINSTALL
  !insertmacro YTMD_STOP_HELPERS
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro YTMD_STOP_HELPERS
!macroend
