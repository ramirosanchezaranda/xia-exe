# ControlXIA para Windows

Asistente de voz para la PC: vive en la bandeja del sistema, escucha una
palabra de activación, entiende pedidos en lenguaje natural con el LLM que
elijas y ejecuta acciones en la computadora. Es la versión de escritorio de la
[app de Android](https://github.com/ramirosanchezaranda/ControlXIA), con el
mismo cerebro.

> «Xia, abrí Spotify» · «subí el volumen» · «poneme un temporizador de 10 minutos» ·
> «recordame a las 18:30 sacar la ropa» · «abrí el bloc de notas y escribí hola» ·
> «apagá la PC» (pide confirmación)

## Qué hace (v1)

| Área | Detalle |
|---|---|
| Activación | Palabra de activación sin internet (Vosk sin cuenta o Porcupine), atajo global `Ctrl+Alt+X`, pausa desde la bandeja |
| Voz | Transcripción offline con Vosk; respuesta con las voces de Windows (SAPI): voz, tono, velocidad y volumen |
| Cerebro | Anthropic, OpenAI, Gemini, Ollama local o cualquier API compatible con OpenAI; proveedor de respaldo; memoria de la conversación |
| Acciones | Hora y fecha, abrir/cerrar programas, web y búsquedas, volumen y música, temporizadores y recordatorios, bloquear, apagar pantalla, suspender, reiniciar, apagar |
| Mouse y teclado | Mira la pantalla (captura), hace clic, escribe, aprieta atajos y hace scroll |
| Seguridad | Permiso por tipo de acción (*Preguntar siempre / Permitir / Bloquear*), confirmación por voz o con botón, historial local que se puede borrar |
| Privacidad | El audio no sale de la PC; las claves van al Administrador de credenciales de Windows |

## Instalar

1. Descargá o generá el instalador `ControlXIA-x.y.z.exe` (ver [docs/COMPILAR.md](docs/COMPILAR.md)).
2. Instalalo: es por usuario y no pide permisos de administrador.
3. El asistente de primera vez te guía para elegir el LLM y cargar la clave,
   descargar el modelo de voz en español (~40 MB) y elegir la palabra de activación.

Windows 10 u 11 de 64 bits.

## Estructura

```
brain/     Cerebro común en Kotlin/JVM puro: proveedores de LLM, tools, permisos,
           agente con bucle de herramientas y memoria. Sin Android ni Windows.
windows/   Capa de plataforma: micrófono, palabra de activación, Vosk, SAPI,
           acciones de Windows, configuración, credenciales y el orquestador.
desktop/   La app en Compose Desktop: bandeja, indicador flotante, configuración,
           asistente de primera vez y empaquetado .exe.
docs/      PRD, compilación, firma, palabra de activación propia.
```

```
Micrófono → Palabra de activación → Vosk (texto) → brain (LLM + tools) → Acción de Windows → Voz (SAPI)
```

## Desarrollo

```bat
gradlew.bat :brain:test :windows:test   :: tests
gradlew.bat :desktop:run                :: abrir la app
gradlew.bat :desktop:packageExe         :: instalador .exe
```

Más detalles en [docs/COMPILAR.md](docs/COMPILAR.md). El PRD con las decisiones
está en [docs/PRD_DESKTOP.md](docs/PRD_DESKTOP.md).
