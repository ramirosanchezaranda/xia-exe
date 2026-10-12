# Palabra de activación propia

Hay dos caminos para que el asistente responda a su nombre (por ejemplo «Xia»).

## 1. Vosk (sin cuenta) — el predeterminado

En Configuración › Asistente › Palabra de activación elegí **Vosk** y escribí la
frase. Usa el mismo modelo en español que la transcripción, restringido a esa
frase. No hay que entrenar nada.

Contras: usa algo más de CPU que Porcupine y puede confundir palabras muy
cortas o parecidas. Si se activa solo, probá con un nombre de dos o tres
sílabas o una frase («oye xia»).

## 2. Porcupine (Picovoice)

Es el motor de la app de Android, más liviano y preciso. Necesita un AccessKey
gratuito de [console.picovoice.ai](https://console.picovoice.ai) (el mismo que
en Android).

- **Palabras de fábrica**: vienen incluidas, en inglés (Jarvis, Computer,
  Porcupine…). Solo hay que pegar el AccessKey.
- **Palabra propia en español**:
  1. En la consola de Picovoice, entrená la palabra (por ejemplo «Xia») con
     idioma **español**.
  2. Descargá el modelo para la plataforma **Windows (x86_64)**. Ojo: los
     `.ppn` son por plataforma; el de Android **no** sirve en la PC.
  3. Descargá el modelo de idioma español `porcupine_params_es.pv` del
     repositorio de Porcupine (`lib/common/`), de la misma versión mayor (v3).
  4. En ControlXIA › Asistente elegí el `.ppn` y el `.pv`.

La app usa `porcupine-java` 3.0.x, compatible con los modelos v3 que usa la app
de Android.
