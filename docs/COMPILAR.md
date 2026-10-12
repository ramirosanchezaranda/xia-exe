# Compilar y generar el instalador

El instalador `.exe` se arma con `jpackage` (vía Compose Desktop) y **tiene que
generarse en Windows**.

## Requisitos

- Windows 10/11 de 64 bits.
- JDK 17 o más nuevo (por ejemplo [Temurin 17](https://adoptium.net/)), con
  `JAVA_HOME` apuntando a él.
- Internet la primera vez (Gradle baja dependencias de Maven Central y de
  `dl.google.com`; Compose descarga WiX para armar el instalador).

No hace falta instalar Gradle: el repo trae `gradlew.bat`.

## Comandos

```bat
:: Tests del cerebro y de la capa de Windows
gradlew.bat :brain:test :windows:test

:: Abrir la app sin instalar (útil para probar)
gradlew.bat :desktop:run

:: Instalador .exe (queda en desktop\build\compose\binaries\main\exe\)
gradlew.bat :desktop:packageExe

:: También hay .msi
gradlew.bat :desktop:packageMsi
```

El instalador es **por usuario** (`perUserInstall`): no pide permisos de
administrador, se instala en `%LOCALAPPDATA%` y crea acceso en el menú Inicio y
en el escritorio. Las versiones nuevas reemplazan a la anterior (mismo
`upgradeUuid`).

## Dónde guarda las cosas

| Qué | Dónde |
|---|---|
| Configuración | `%APPDATA%\ControlXIA\settings.json` |
| Historial | `%APPDATA%\ControlXIA\history.jsonl` |
| Temporizadores pendientes | `%APPDATA%\ControlXIA\timers.json` |
| Modelo de voz (Vosk) | `%APPDATA%\ControlXIA\models\` |
| Log | `%APPDATA%\ControlXIA\controlxia.log` |
| API keys y AccessKey | Administrador de credenciales de Windows › Credenciales de Windows › `ControlXIA/…` |

## CI

`.github/workflows/windows-build.yml` corre los tests y arma el `.exe` en un
runner de Windows y lo deja como artefacto. Mientras GitHub Actions siga
bloqueado por facturación en la cuenta, compilá en la PC con los comandos de
arriba.

## Problemas comunes

- **No me escucha**: Configuración de Windows › Privacidad › Micrófono ›
  «Permitir que las aplicaciones de escritorio accedan al micrófono».
- **La voz habla en inglés**: instalá una voz en español en Configuración ›
  Hora e idioma › Voz, y elegila en ControlXIA › Voz. Solo aparecen las voces
  SAPI (las «Desktop»).
- **El atajo no anda**: otra app ya usa esa combinación; cambiala en
  Configuración › Asistente.
- **Error de Porcupine**: revisá el AccessKey y que el `.ppn` sea el de
  **Windows** (ver [PALABRA_PROPIA.md](PALABRA_PROPIA.md)).
