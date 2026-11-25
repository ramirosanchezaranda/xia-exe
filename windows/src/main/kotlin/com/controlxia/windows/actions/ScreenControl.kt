package com.controlxia.windows.actions

import com.controlxia.brain.llm.ToolSpec
import com.controlxia.brain.tools.ActionCategory
import com.controlxia.brain.tools.Tool
import com.controlxia.brain.tools.ToolResult
import com.controlxia.brain.tools.bool
import com.controlxia.brain.tools.intOrNull
import com.controlxia.brain.tools.normalizeText
import com.controlxia.brain.tools.schema
import com.controlxia.brain.tools.str
import com.controlxia.brain.tools.tool
import com.controlxia.windows.platform.PowerShellHost
import com.controlxia.windows.platform.Win32
import com.controlxia.windows.platform.psQuote
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Un elemento de la ventana activa, numerado como en el Acceso por voz. */
data class ScreenElement(
    val number: Int,
    val name: String,
    val type: String,
    /** Coordenadas físicas de la pantalla (píxeles reales). */
    val x: Int, val y: Int, val width: Int, val height: Int,
)

data class ScreenSnapshot(val process: String, val title: String, val elements: List<ScreenElement>)

/**
 * Control de programas por accesibilidad (UI Automation de Windows), el
 * equivalente del control de apps del teléfono: lista los botones, campos y
 * menús de la ventana activa con un número, y los toca o escribe en ellos.
 * Funciona con cualquier modelo, sin capturas ni visión.
 */
class ScreenControl(private val shell: PowerShellHost) {

    @Volatile var last: ScreenSnapshot? = null
        private set

    /** Lee la ventana activa. */
    suspend fun read(max: Int = MAX_ELEMENTS): ScreenSnapshot {
        val lines = run("[XiaUia]::List($max)").lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.split('\t').orEmpty()
        val elements = lines.drop(1).mapNotNull { parseLine(it) }
        return ScreenSnapshot(header.getOrNull(0).orEmpty(), header.getOrNull(1).orEmpty(), elements).also { last = it }
    }

    /** Toca el elemento [number] de la última lectura. */
    suspend fun tap(number: Int): String {
        val result = run("[XiaUia]::Tap($number)")
        if (result.startsWith("ERR:")) throw IllegalStateException(result.removePrefix("ERR:"))
        delay(SETTLE_MS)
        return "Toqué ${describe(number)}"
    }

    /** Escribe [text] en el elemento [number]; si no acepta el valor directo, lo enfoca y tipea. */
    suspend fun type(number: Int, text: String, submit: Boolean): String {
        val result = run("[XiaUia]::SetText($number, ${psQuote(text)})")
        if (result.startsWith("ERR:")) throw IllegalStateException(result.removePrefix("ERR:"))
        if (result.startsWith("FOCUS")) {
            delay(150)
            Win32.typeUnicode(text)
        }
        if (submit) Win32.tapVirtualKey(Win32.VK_RETURN)
        delay(SETTLE_MS)
        return "Escribí en ${describe(number)}"
    }

    /** El elemento que mejor coincide con [label] (de la última lectura o de una nueva). */
    suspend fun find(label: String): ScreenElement? {
        val snapshot = last ?: read()
        return AppTools.bestMatch(label, snapshot.elements) { it.name }
            ?: AppTools.bestMatch(label, read().elements) { it.name }
    }

    fun describe(number: Int): String =
        last?.elements?.firstOrNull { it.number == number }?.let { "«${it.name}»" } ?: "el $number"

    fun isSensitive(number: Int?): Boolean {
        val name = number?.let { n -> last?.elements?.firstOrNull { it.number == n }?.name } ?: return false
        return isSensitiveLabel(name)
    }

    fun tools(): List<Tool> = listOf(
        tool(
            "screen_read",
            "Lee la ventana activa como lista numerada de botones, campos, menús y enlaces. Usala antes de screen_tap o screen_type.",
            ActionCategory.SCREEN_CONTROL,
            progress = { "Mirando la ventana…" },
        ) { ToolResult(format(read())) },
        object : Tool {
            override val spec = ToolSpec(
                "screen_tap",
                "Toca un elemento de la ventana activa por su número (de screen_read) o por su nombre.",
                schema {
                    integer("number", "Número del elemento")
                    string("label", "Nombre del elemento, si no sabés el número")
                },
            )
            override val category = ActionCategory.SCREEN_CONTROL
            override fun categoryFor(args: JsonObject) =
                if (isSensitive(args.intOrNull("number")) || isSensitiveLabel(args.str("label"))) ActionCategory.SENSITIVE else category
            override fun confirmationQuestion(args: JsonObject) =
                "¿Toco ${args.intOrNull("number")?.let { describe(it) } ?: "«${args.str("label")}»"}?"
            override fun progressText(args: JsonObject) = "Tocando…"
            override suspend fun run(args: JsonObject): ToolResult {
                val number = args.intOrNull("number") ?: find(args.str("label"))?.number
                    ?: return ToolResult("No encontré ese elemento en la ventana", isError = true)
                val result = tap(number)
                return ToolResult(result + "\n" + format(read()))
            }
        },
        tool(
            "screen_type",
            "Escribe texto en un campo de la ventana activa (por número o nombre). submit=true aprieta Enter al final.",
            ActionCategory.SCREEN_CONTROL,
            schema {
                integer("number", "Número del campo")
                string("label", "Nombre del campo, si no sabés el número")
                string("text", "Texto a escribir", required = true)
                boolean("submit", "Apretar Enter después")
            },
            progress = { "Escribiendo…" },
        ) { args ->
            val number = args.intOrNull("number") ?: find(args.str("label"))?.number
                ?: return@tool ToolResult("No encontré ese campo en la ventana", isError = true)
            ToolResult(type(number, args.str("text"), args.bool("submit")))
        },
    )

    private suspend fun run(expression: String): String = shell.run(DEFINE + "\n" + expression, timeoutMs = 25_000)

    companion object {
        const val MAX_ELEMENTS = 60
        private const val MAX_CHARS = 2_500
        private const val SETTLE_MS = 350L

        private val TYPES = mapOf(
            "Button" to "botón", "SplitButton" to "botón", "MenuItem" to "menú", "Hyperlink" to "enlace",
            "Edit" to "campo", "Document" to "documento", "CheckBox" to "casilla", "RadioButton" to "opción",
            "ComboBox" to "lista", "ListItem" to "ítem", "TabItem" to "pestaña", "TreeItem" to "ítem", "DataItem" to "fila",
        )

        private val SENSITIVE = Regex(
            "\\b(enviar|envia|send|comprar|compra|buy|pagar|pago|pay|checkout|publicar|publish|post|borrar|eliminar|" +
                "suprimir|delete|remove|vaciar|confirmar compra|finalizar compra|transferir|desinstalar|uninstall|formatear)\\b",
        )

        fun isSensitiveLabel(label: String): Boolean = label.isNotBlank() && SENSITIVE.containsMatchIn(normalizeText(label))

        internal fun parseLine(line: String): ScreenElement? {
            val p = line.split('\t')
            if (p.size < 7) return null
            return ScreenElement(
                number = p[0].toIntOrNull() ?: return null,
                name = p[1].trim(),
                type = p[2].trim(),
                x = p[3].toIntOrNull() ?: 0, y = p[4].toIntOrNull() ?: 0,
                width = p[5].toIntOrNull() ?: 0, height = p[6].toIntOrNull() ?: 0,
            )
        }

        /** Lista legible y corta (los modelos chicos tienen poco lugar). */
        fun format(s: ScreenSnapshot): String {
            val head = "Ventana: ${s.title.ifBlank { s.process }}" + if (s.process.isNotBlank()) " (${s.process})" else ""
            if (s.elements.isEmpty()) return "$head\nNo encontré elementos que se puedan tocar (puede ser un programa poco accesible)."
            val sb = StringBuilder(head)
            for (e in s.elements) {
                val line = "\n${e.number}. ${TYPES[e.type] ?: e.type.lowercase()} «${e.name.ifBlank { "sin nombre" }}»"
                if (sb.length + line.length > MAX_CHARS) {
                    sb.append("\n(hay más elementos)")
                    break
                }
                sb.append(line)
            }
            return sb.toString()
        }

        // UI Automation desde PowerShell: la clase se compila una vez y queda en
        // el PowerShell persistente, con la última lista guardada para tocar por número.
        private val DEFINE = """
            if (-not ('XiaUia' -as [type])) {
            Add-Type -ReferencedAssemblies UIAutomationClient, UIAutomationTypes, WindowsBase -TypeDefinition @'
            using System;
            using System.Collections.Generic;
            using System.Runtime.InteropServices;
            using System.Text;
            using System.Threading;
            using System.Windows;
            using System.Windows.Automation;
            public static class XiaUia {
              [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
              [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr v);
              [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
              [DllImport("user32.dll")] static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
              [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
              [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint cmd);
              [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
              static List<AutomationElement> items = new List<AutomationElement>();
              static bool dpi;
              static void Dpi() { if (!dpi) { try { SetProcessDpiAwarenessContext(new IntPtr(-4)); } catch { } dpi = true; } }
              static string ProcName(uint pid) { try { return System.Diagnostics.Process.GetProcessById((int)pid).ProcessName; } catch { return ""; } }
              // La ventana de arriba que no sea de ControlXIA (Java) ni de este PowerShell.
              static IntPtr Target() {
                IntPtr h = GetForegroundWindow();
                int me = System.Diagnostics.Process.GetCurrentProcess().Id;
                for (int i = 0; i < 50 && h != IntPtr.Zero; i++) {
                  uint pid; GetWindowThreadProcessId(h, out pid);
                  string name = ProcName(pid);
                  if (IsWindowVisible(h) && name != "java" && name != "javaw" && name != "ControlXIA" && pid != me) return h;
                  h = GetWindow(h, 2);
                }
                return GetForegroundWindow();
              }
              static readonly ControlType[] Types = { ControlType.Button, ControlType.SplitButton, ControlType.MenuItem,
                ControlType.Hyperlink, ControlType.Edit, ControlType.CheckBox, ControlType.RadioButton, ControlType.ComboBox,
                ControlType.ListItem, ControlType.TabItem, ControlType.TreeItem, ControlType.DataItem };
              static string Clean(string s) {
                if (s == null) return "";
                s = s.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ').Trim();
                return s.Length > 80 ? s.Substring(0, 80) : s;
              }
              public static string List(int max) {
                Dpi();
                items.Clear();
                IntPtr h = Target();
                AutomationElement root = AutomationElement.FromHandle(h);
                uint pid; GetWindowThreadProcessId(h, out pid);
                var sb = new StringBuilder();
                sb.Append(Clean(ProcName(pid))).Append('\t').Append(Clean(root.Current.Name)).Append('\n');
                var conds = new List<Condition>();
                foreach (var t in Types) conds.Add(new PropertyCondition(AutomationElement.ControlTypeProperty, t));
                var cond = new AndCondition(new OrCondition(conds.ToArray()),
                  new PropertyCondition(AutomationElement.IsOffscreenProperty, false),
                  new PropertyCondition(AutomationElement.IsEnabledProperty, true));
                AutomationElementCollection found = root.FindAll(TreeScope.Descendants, cond);
                foreach (AutomationElement e in found) {
                  if (items.Count >= max) break;
                  Rect r; string name; string type;
                  try { r = e.Current.BoundingRectangle; name = e.Current.Name; type = e.Current.ControlType.ProgrammaticName.Replace("ControlType.", ""); }
                  catch { continue; }
                  if (r.IsEmpty || r.Width < 3 || r.Height < 3) continue;
                  if (string.IsNullOrWhiteSpace(name)) { try { name = e.Current.HelpText; } catch { } }
                  if (string.IsNullOrWhiteSpace(name) && type != "Edit") continue;
                  items.Add(e);
                  sb.Append(items.Count).Append('\t').Append(Clean(name)).Append('\t').Append(type).Append('\t')
                    .Append((int)r.X).Append('\t').Append((int)r.Y).Append('\t').Append((int)r.Width).Append('\t').Append((int)r.Height).Append('\n');
                }
                return sb.ToString();
              }
              static string Click(AutomationElement e) {
                try {
                  Rect r = e.Current.BoundingRectangle;
                  SetCursorPos((int)(r.X + r.Width / 2), (int)(r.Y + r.Height / 2));
                  mouse_event(2, 0, 0, 0, UIntPtr.Zero);
                  mouse_event(4, 0, 0, 0, UIntPtr.Zero);
                  return "OK:click";
                } catch (Exception ex) { return "ERR:" + ex.Message; }
              }
              // Invoke puede quedarse esperando si abre un cuadro de diálogo: se corre aparte con tope.
              static bool Run(Action a) {
                Exception err = null;
                var t = new Thread(() => { try { a(); } catch (Exception ex) { err = ex; } });
                t.IsBackground = true; t.Start();
                if (!t.Join(2500)) return true;
                return err == null;
              }
              public static string Tap(int i) {
                Dpi();
                if (i < 1 || i > items.Count) return "ERR:No hay un elemento " + i + ". Leé la ventana de nuevo.";
                AutomationElement e = items[i - 1];
                object p;
                try {
                  if (e.TryGetCurrentPattern(InvokePattern.Pattern, out p)) { var ip = (InvokePattern)p; if (Run(() => ip.Invoke())) return "OK:invoke"; }
                  if (e.TryGetCurrentPattern(TogglePattern.Pattern, out p)) { ((TogglePattern)p).Toggle(); return "OK:toggle"; }
                  if (e.TryGetCurrentPattern(SelectionItemPattern.Pattern, out p)) { ((SelectionItemPattern)p).Select(); return "OK:select"; }
                  if (e.TryGetCurrentPattern(ExpandCollapsePattern.Pattern, out p)) {
                    var ec = (ExpandCollapsePattern)p;
                    if (ec.Current.ExpandCollapseState == ExpandCollapseState.Collapsed) ec.Expand(); else ec.Collapse();
                    return "OK:expand";
                  }
                } catch { }
                return Click(e);
              }
              public static string SetText(int i, string text) {
                Dpi();
                if (i < 1 || i > items.Count) return "ERR:No hay un elemento " + i + ". Leé la ventana de nuevo.";
                AutomationElement e = items[i - 1];
                object p;
                try {
                  if (e.TryGetCurrentPattern(ValuePattern.Pattern, out p) && !((ValuePattern)p).Current.IsReadOnly) {
                    ((ValuePattern)p).SetValue(text); return "OK:value";
                  }
                } catch { }
                try { e.SetFocus(); return "FOCUS"; } catch { }
                Click(e);
                return "FOCUS";
              }
            }
            '@
            }
        """.trimIndent()
    }
}
