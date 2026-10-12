package com.controlxia.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Etiqueta técnica en mayúsculas, estilo plano. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Muted,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun Hint(text: String, color: Color = Muted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun Hairline() = HorizontalDivider(color = Hairline, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

/** Selector simple: botón con el valor actual y menú desplegable. */
@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(display(selected), modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(display(option)) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Int,
    range: IntRange,
    valueLabel: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueLabel(value), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

/**
 * Campo para claves: se edita en memoria y solo se guarda (en el
 * Administrador de credenciales) al tocar Guardar.
 */
@Composable
fun SecretField(label: String, initial: String, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    var saved by remember(initial) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.trim(); saved = false },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { onSave(value); saved = true }) {
            Text(if (saved) Texts.SAVED else Texts.SAVE)
        }
    }
}

@Composable
fun FileField(label: String, path: String, extensions: List<String>, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = path,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { pickFile(label, extensions)?.let(onChange) }) { Text(Texts.BROWSE) }
    }
}

fun pickFile(title: String, extensions: List<String>): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> extensions.isEmpty() || extensions.any { name.endsWith(it, ignoreCase = true) } }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).absolutePath
}

@Composable
fun VSpace(h: Int = 8) = Spacer(Modifier.height(h.dp))

val RowSpacing = Arrangement.spacedBy(8.dp)
