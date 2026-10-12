# PRD — ControlXIA para Windows (.exe)

Estado: **aprobado e implementado (v1)**. Fecha: octubre de 2026.
Las preguntas abiertas de la sección 12 están respondidas y sus decisiones se
reflejan en el resto del documento.

## 1. Resumen

ControlXIA para Windows es el mismo asistente de voz que la app de Android,
pero para la PC. Vive en la bandeja del sistema, escucha una palabra de
activación, entiende pedidos en lenguaje natural con el LLM que elija el
usuario y ejecuta acciones en la computadora, incluido manejar el mouse y el
teclado. Se entrega como un instalador `.exe`.

## 2. Problema y objetivo

**Problema.** Controlar la PC con la voz hoy depende de Windows Voice Access
(limitado, sin LLM) o de proyectos open source difíciles de instalar. La app de
Android ya demuestra la idea, pero no hay equivalente de escritorio con el mismo
cerebro.

**Objetivo.** Que alguien instale un `.exe`, cargue una API key y pueda decir
"Xia, abrí Spotify" o "Xia, ponete un temporizador de 10 minutos" sin tocar el
teclado.

**No objetivos de la v1.** Mac y Linux, llamadas y SMS, y publicación en la
Microsoft Store. *(El control de mouse y teclado salió de esta lista: entra en
la v1, ver D9.)*

## 3. Usuarios

- **Principal:** quien ya usa o va a usar la app de Android y quiere el mismo
  asistente en la PC.
- **Secundario:** personas con las manos ocupadas o con dificultad para usar el
  teclado.

## 4. Decisiones de producto

| # | Decisión | Elección | Alternativa descartada |
|---|---|---|---|
| D1 | Tecnología | **Kotlin + Compose Desktop** (confirmado) | Electron: obliga a reescribir el cerebro en JavaScript |
| D2 | Palabra de activación | Porcupine (JVM, mismo AccessKey que Android) **y** Vosk con gramática restringida como opción sin cuenta (predeterminada) | openWakeWord: requiere Python u ONNX |
| D3 | Voz a texto | Vosk offline; Whisper local como mejora futura | Servicio en la nube: costo y privacidad |
| D4 | Texto a voz | Voz de Windows (SAPI) con voz, tono, velocidad y volumen | ElevenLabs: opción posterior |
| D5 | LLM | Los mismos proveedores que Android (Anthropic, OpenAI, Gemini, compatibles) | — |
| D6 | Claves | Administrador de credenciales de Windows | Archivo de configuración |
| D7 | Conectividad | **LLM en la nube por defecto + Ollama local opcional** como proveedor (y como respaldo) | Todo offline obligatorio |
| D8 | Distribución | **Uso personal ahora, distribución después**: instalador sin firmar, con la tarea `signExe` lista para firmar | Firmar desde el día uno |
| D9 | Mouse y teclado | **En la v1**: captura de pantalla + clic, escritura, atajos y scroll, detrás de confirmación | Dejarlo para una versión posterior |

Nota sobre D2: los `.ppn` de Porcupine son por plataforma; el de Android no sirve
en Windows (ver `docs/PALABRA_PROPIA.md`).

## 5. Requisitos funcionales

**Activación y escucha**
- RF-01. Arranca con Windows y queda en la bandeja, con un ícono que indica el estado.
- RF-02. Detecta la palabra de activación en segundo plano, sin internet.
- RF-03. Se puede cambiar el nombre del asistente y elegir palabras de fábrica o un modelo propio.
- RF-04. Atajo de teclado global como alternativa (por defecto Ctrl+Alt+X).
- RF-05. Se puede pausar la escucha desde la bandeja con un clic.

**Interacción**
- RF-06. Indicador flotante siempre visible con el estado y el texto que va entendiendo.
- RF-07. Responde en voz alta con la voz, el tono y la velocidad elegidos.
- RF-08. Si el usuario dice la palabra mientras el asistente habla, este se calla y escucha.
- RF-09. Mantiene el contexto de la conversación entre pedidos seguidos.

**Acciones de la v1**
- RF-10. Hora y fecha.
- RF-11. Abrir y cerrar programas por nombre.
- RF-12. Abrir una página web o buscar en internet.
- RF-13. Subir, bajar y silenciar el volumen.
- RF-14. Temporizadores y recordatorios con aviso sonoro y notificación de Windows.
- RF-15. Bloquear, apagar la pantalla, suspender y apagar.
- RF-16. Responder preguntas generales con el LLM.
- RF-23. *(nuevo, D9)* Ver la pantalla y manejar mouse y teclado: clic, doble clic, mover, scroll, escribir texto y atajos.

**Seguridad y confirmación**
- RF-17. Las acciones riesgosas piden confirmación por voz o con un botón.
- RF-18. Cada tipo de acción se puede poner en *Preguntar siempre*, *Permitir* o *Bloquear*.
- RF-19. Historial local de comandos y respuestas, que el usuario puede borrar.

**Configuración**
- RF-20. Configuración con la misma estructura que Android: asistente, LLM, voz, privacidad (más permisos e historial).
- RF-21. "Probar conexión" con el error real si falla.
- RF-22. Asistente de primera vez para claves y micrófono.

## 6. Requisitos no funcionales

- RNF-01. CPU en reposo baja con la escucha activa (objetivo < 3 % en gama media). Porcupine es el motor más liviano; Vosk consume algo más a cambio de no necesitar cuenta.
- RNF-02. Activación y transcripción sin internet; solo la llamada al LLM sale de la PC (nada, con Ollama).
- RNF-03. Menos de 3 s desde que termina de hablar hasta que empieza a responder con un LLM en la nube.
- RNF-04. El audio no se guarda ni se envía a ningún servicio.
- RNF-05. Windows 10 y 11 de 64 bits, sin permisos de administrador.
- RNF-06. Interfaz en español rioplatense con textos centralizados para sumar idiomas.

## 7. Arquitectura

```
Micrófono → Wake word (Vosk/Porcupine) → STT (Vosk) → brain (LLM + tools + memoria) → Acción de Windows → TTS (SAPI)
```

| Módulo | Contenido |
|---|---|
| `brain` | Kotlin/JVM puro. Proveedores (Anthropic, OpenAI y compatibles, Gemini, Ollama) con tool use en varios pasos e imágenes; `Assistant` (bucle de herramientas, permisos, confirmación, respaldo entre proveedores); memoria; prueba de conexión. |
| `windows` | Micrófono compartido, motores de activación, Vosk, SAPI, PowerShell persistente, Win32 (atajo global, entrada Unicode, bloqueo), acciones, configuración, credenciales, historial y el orquestador `AssistantController`. Sin UI, con tests. |
| `desktop` | Compose Desktop: bandeja, indicador flotante, configuración, asistente de primera vez y empaquetado `packageExe` (jpackage, instalación por usuario). |

Diferencia con el plan original: el cerebro común vive en este repo (`brain`).
Que la app de Android lo consuma (hito M0) es un cambio en el repo de Android
que queda pendiente; las interfaces de voz se mantuvieron equivalentes.

## 8. Plan de entrega y estado

| Hito | Contenido | Estado |
|---|---|---|
| M0 | Cerebro como módulo común | `brain` hecho; falta que Android lo consuma |
| M1 | Ventana, bandeja, configuración, LLM, "Probar conexión" | Hecho |
| M2 | Wake word + STT + TTS | Hecho |
| M3 | Acciones RF-10 a RF-16 | Hecho |
| M4 | Confirmaciones, permisos e historial | Hecho |
| M5 | Indicador flotante, atajo global, instalador `.exe` | Hecho (el `.exe` se genera en Windows) |
| M6 | Pulido | Respaldo entre LLM y cortar al asistente: hechos. Respuesta en streaming: pendiente |
| — | Mouse y teclado (D9) | Hecho |

## 9. Criterios de aceptación de la v1

1. Se instala con el `.exe` en una PC con Windows 11 limpia.
2. Tras cargar la API key, decir la palabra de activación y "qué hora es" produce la respuesta hablada correcta.
3. "Abrí [programa instalado]" lo abre y "silenciá el volumen" lo silencia.
4. "Apagá la PC" pide confirmación y no apaga sin ella.
5. Con la escucha pausada, no se procesa audio (el micrófono se cierra).
6. Cambiar de proveedor de LLM no requiere reiniciar la app.
7. *(nuevo)* "Abrí el bloc de notas y escribí hola" pide permiso para usar el teclado y lo hace.

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| El `.exe` solo se arma en Windows; CI bloqueado por facturación | `docs/COMPILAR.md` + workflow listo para cuando se habilite |
| Calidad de Vosk en rioplatense | Modelo configurable; Whisper local como mejora |
| Falsos positivos de activación | Sensibilidad ajustable, atajo de teclado, pausa |
| Acciones destructivas por error del LLM | Confirmación, permisos por categoría, las bloqueadas no se ofrecen al LLM |
| El LLM maneja mal el mouse | Captura antes y después de cada paso, confirmación por pedido, preferir atajos y tools directas |
| Capturas de pantalla con datos sensibles | Categoría "Ver la pantalla" separada, en *Preguntar siempre* por defecto; con Ollama no salen de la PC |
| Voces en español no instaladas | Aviso en Configuración › Voz con el camino para instalarlas |

## 11. Métricas de éxito

- % de comandos entendidos y ejecutados bien en una lista fija de 30 pedidos (objetivo ≥ 85 %).
- Tiempo medio de respuesta.
- Falsos positivos de activación por hora.
- Instalaciones que llegan a ejecutar un primer comando.

## 12. Preguntas abiertas (respondidas)

1. **¿Compose Desktop o Electron?** → Compose Desktop (D1).
2. **¿Sin internet?** → Nube por defecto, con Ollama como proveedor local opcional (D7).
3. **¿Mouse y teclado?** → Sí, ya en la v1 (D9, RF-23).
4. **¿Uso personal o distribución?** → Personal ahora, distribución después: sin firma en la v1 y empaquetado preparado para firmar (D8, `docs/FIRMA.md`).
