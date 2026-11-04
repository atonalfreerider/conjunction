package com.metavirtuoso.conjunction.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.ui.theme.GoldBrushes
import com.metavirtuoso.conjunction.ui.theme.Palette

/** A consent / option checkbox with the whole row as the touch target (starts unticked by the caller). */
@Composable
fun GoldCheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String, modifier: Modifier = Modifier, note: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Palette.Gold, uncheckedColor = Palette.GoldPale, checkmarkColor = Palette.Midnight),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = Palette.MoonstoneDim)
            }
        }
    }
}

@Composable
fun GoldSwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, title: String, subtitle: String? = null, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.GoldPale)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.MoonstoneDim)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Midnight,
                checkedTrackColor = Palette.Gold,
                uncheckedThumbColor = Palette.MoonstoneDim,
                uncheckedTrackColor = Palette.InkViolet,
                uncheckedBorderColor = Palette.GoldDeep,
            ),
        )
    }
}

/** Single-choice row of engraved "tablet" chips (radio semantics). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier, centered: Boolean = false) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = if (centered) Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val shape = CutCornerShape(8.dp)
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) Palette.Midnight else Palette.GoldPale,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .background(if (isSelected) GoldBrushes.leaf else SolidColor(Palette.Midnight.copy(alpha = 0.5f)), shape)
                    .border(1.dp, GoldBrushes.leaf, shape)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            )
        }
    }
}

@Composable
fun OrnateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supporting: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    minLines: Int = 1,
    isError: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // No label = the placeholder is the always-visible hint (no floating label over the art).
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = Palette.MoonstoneDim.copy(alpha = 0.7f)) } },
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        supportingText = supporting?.let { { Text(it) } },
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = CutCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Gold,
            unfocusedBorderColor = Palette.GoldDeep,
            focusedLabelColor = Palette.Gold,
            unfocusedLabelColor = Palette.MoonstoneDim,
            cursorColor = Palette.GoldPale,
            focusedTextColor = Palette.Moonstone,
            unfocusedTextColor = Palette.Moonstone,
            focusedContainerColor = Palette.Midnight.copy(alpha = 0.85f),
            unfocusedContainerColor = Palette.Midnight.copy(alpha = 0.8f),
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Small italic note in dim moonstone. */
@Composable
fun FinePrint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = Palette.MoonstoneDim, modifier = modifier)
}
