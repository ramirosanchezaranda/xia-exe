' Lanzador de ControlXIA portable: abre la app sin ventana de consola.
' Doble clic para abrir. Con --tray arranca minimizado en la bandeja.
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
args = ""
For Each a In WScript.Arguments
  args = args & " " & a
Next
cmd = """" & dir & "\runtime\bin\javaw.exe"" -Xmx512m -Dfile.encoding=UTF-8" & _
      " -Dcontrolxia.launcher=""" & WScript.ScriptFullName & """" & _
      " -cp """ & dir & "\lib\*"" com.controlxia.desktop.MainKt" & args
CreateObject("WScript.Shell").Run cmd, 0, False
