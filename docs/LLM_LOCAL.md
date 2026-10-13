# Usar un LLM local con Ollama

Con Ollama el cerebro corre en tu PC: sin API key, sin límites de uso y sin
que los pedidos (ni las capturas de pantalla) salgan de la computadora. El
costo es que necesita una placa de video para responder rápido.

## Instalar

1. Descargá Ollama de [ollama.com](https://ollama.com) e instalalo.
2. En una consola, bajá el modelo (una sola vez):
   ```
   ollama pull qwen3:8b
   ```
3. En ControlXIA › Cerebro elegí **Ollama**, escribí el modelo y tocá
   *Probar conexión* o *Ver modelos instalados*.

ControlXIA usa la API nativa de Ollama con un contexto de 16k tokens y deja el
modelo cargado 30 minutos para que los pedidos siguientes sean rápidos.

## Qué modelo elegir

El asistente necesita un modelo que sepa usar herramientas ("tool calling").

| Tu PC | Modelo | Comentario |
|---|---|---|
| Placa de video con 16 GB o más | `gpt-oss:20b` | El mismo modelo que usabas en Groq, sin límite de tokens |
| Placa de video con 8–12 GB | `qwen3:8b` | Buen equilibrio; recomendado para empezar |
| Sin placa de video o 8 GB de RAM | `qwen3:4b` | Funciona, pero cada respuesta tarda varios segundos |
| Para "mirá la pantalla" | `qwen2.5vl:7b` | Modelo con visión; los otros no ven capturas |

Sin placa de video, cada pedido puede tardar entre 10 y 30 segundos porque el
modelo tiene que leer las instrucciones y las 26 herramientas antes de
contestar.

## Lo más cómodo: nube + local de respaldo

En ControlXIA › Cerebro podés dejar un proveedor en la nube como principal y
Ollama como **respaldo**: si la nube falla o te limita (error 429), el pedido
sigue con el modelo local sin que tengas que hacer nada.
