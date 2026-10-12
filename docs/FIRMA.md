# Firmar el instalador

Para uso personal no hace falta: Windows SmartScreen muestra «Windows protegió
su PC» la primera vez, y se sigue con *Más información › Ejecutar de todas
formas*.

Para distribuir a otras personas conviene firmar el `.exe` con un certificado
de firma de código. El build ya está preparado.

## Qué se necesita

1. Un certificado de firma de código (`.pfx`) de una autoridad reconocida
   (DigiCert, Sectigo, SSL.com, Certum…). Los certificados EV o los servicios
   de firma en la nube (por ejemplo Azure Trusted Signing) evitan la advertencia
   de SmartScreen más rápido.
2. `signtool.exe`, que viene con el Windows SDK.

## Cómo firmar

```bat
set CONTROLXIA_SIGN_PFX=C:\ruta\certificado.pfx
set CONTROLXIA_SIGN_PASSWORD=la-clave-del-pfx
:: opcional si signtool no está en el PATH:
set CONTROLXIA_SIGNTOOL=C:\Program Files (x86)\Windows Kits\10\bin\10.0.22621.0\x64\signtool.exe

gradlew.bat :desktop:signExe
```

`signExe` genera el instalador y lo firma con SHA-256 y sello de tiempo. Si las
variables no están definidas, la tarea se saltea sin error.

Nunca commitees el `.pfx` ni la clave: `.gitignore` ya excluye `*.pfx` y `*.p12`.
