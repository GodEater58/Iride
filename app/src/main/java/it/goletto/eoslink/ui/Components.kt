package it.goletto.eoslink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.goletto.eoslink.ui.theme.LocalEos
import it.goletto.eoslink.ui.theme.readout

/** Leggera pressione "meccanica": il tasto rientra quando viene premuto. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "press")
    return this.scale(s)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    val p = LocalEos.current
    val src = remember { MutableInteractionSource() }
    val bg = when {
        !enabled -> p.groove
        danger -> p.exposure
        else -> p.lcd
    }
    val fg = if (enabled) p.onLcd else p.muted
    Row(
        modifier
            .pressScale(src)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val p = LocalEos.current
    val src = remember { MutableInteractionSource() }
    val fg = if (enabled) p.text else p.muted
    Row(
        modifier
            .pressScale(src)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, p.groove, RoundedCornerShape(22.dp))
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Tasto tondo con stato acceso/spento, come i tasti funzione sul corpo macchina. */
@Composable
fun RoundToggle(
    icon: ImageVector,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    enabled: Boolean = true,
) {
    val p = LocalEos.current
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) p.lcd else p.raised, label = "tbg")
    val fg by animateColorAsState(if (!enabled) p.groove else if (on) p.onLcd else p.text, label = "tfg")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .pressScale(src)
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, if (on) Color.Transparent else p.groove, CircleShape)
                .clickable(src, null, enabled = enabled, role = Role.Switch, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = fg, modifier = Modifier.size(size * 0.44f))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) p.lcd else p.muted)
    }
}

@Composable
fun IconTap(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = LocalEos.current.text) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dotA",
    )
    Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (pulsing) a else 1f))
    )
}

/** Campo di testo nello stile della app: etichetta sopra, incavo scuro, nessun riquadro Material. */
@Composable
fun EosField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val p = LocalEos.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.muted)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
            cursorBrush = SolidColor(p.lcd),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.raised)
                        .border(1.dp, p.groove, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = p.muted.copy(alpha = 0.7f))
                    }
                    inner()
                }
            },
        )
    }
}

/** Valore da display con tasti − e +, modificabile anche a mano. */
@Composable
fun LcdStepper(
    label: String,
    value: String,
    unit: String,
    onChange: (String) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalEos.current
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(p.raised)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = p.muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
            BasicTextField(
                value = value,
                onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ',' || it == '.' }.take(6)) },
                enabled = enabled,
                singleLine = true,
                textStyle = readout(34).copy(color = if (enabled) p.lcd else p.muted, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(p.lcd),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(72.dp),
            )
        }
        Text(unit, style = MaterialTheme.typography.labelSmall, color = p.muted)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepKey(EosIcons.Minus, "Diminuisci $label", enabled, onMinus)
            StepKey(EosIcons.Plus, "Aumenta $label", enabled, onPlus)
        }
    }
}

@Composable
private fun StepKey(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalEos.current
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(src)
            .size(36.dp)
            .clip(CircleShape)
            .background(p.groove)
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) p.text else p.muted, modifier = Modifier.size(18.dp))
    }
}

/** Sezione richiudibile con freccia che ruota. */
@Composable
fun Expandable(
    title: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = LocalEos.current
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "chev")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { open = !open }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.text, modifier = Modifier.weight(1f))
            Icon(EosIcons.Chevron, null, tint = p.muted, modifier = Modifier.size(20.dp).rotate(angle))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            content()
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalEos.current.groove))
}

fun formatDuration(totalSeconds: Int): String {
    if (totalSeconds <= 0) return "--"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h} h ${m} min"
        m > 0 -> if (s > 0 && m < 10) "${m} min ${s} s" else "${m} min"
        else -> "${s} s"
    }
}

@Composable
fun Readout(value: String, label: String, modifier: Modifier = Modifier, size: Int = 28, color: Color = LocalEos.current.lcd) {
    val p = LocalEos.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = readout(size), color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1)
    }
}

