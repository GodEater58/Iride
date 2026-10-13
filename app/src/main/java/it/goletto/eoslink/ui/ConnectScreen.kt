package it.goletto.eoslink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.goletto.eoslink.MainViewModel
import it.goletto.eoslink.ui.theme.LocalEos
import it.goletto.eoslink.ui.theme.readout

@Composable
fun ConnectScreen(vm: MainViewModel) {
    val p = LocalEos.current
    Column(
        Modifier
            .fillMaxSize()
            .background(p.body)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EosLink", style = MaterialTheme.typography.titleMedium, color = p.muted, modifier = Modifier.weight(1f))
            IconTap(
                if (p.night) EosIcons.Sun else EosIcons.Moon,
                if (p.night) "Disattiva modalità notte" else "Attiva modalità notte",
                vm::toggleNight,
                tint = p.muted,
            )
        }

        HeroIris(busy = vm.busy, error = vm.statusError)

        Text("Collega la tua EOS", style = MaterialTheme.typography.displaySmall, color = p.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Scatto remoto, live view, intervallometro e RAW originali via Wi-Fi. Internet resta attivo sui dati mobili.",
            style = MaterialTheme.typography.bodyMedium,
            color = p.muted,
        )
        Spacer(Modifier.height(24.dp))

        Text("Come colleghi la fotocamera", style = MaterialTheme.typography.labelMedium, color = p.muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTile(
                icon = EosIcons.Hotspot,
                title = "Hotspot",
                caption = "La fotocamera entra nell'hotspot del telefono",
                selected = vm.mode == MainViewModel.Mode.HOTSPOT,
                onClick = { vm.mode = MainViewModel.Mode.HOTSPOT },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                icon = EosIcons.Wifi,
                title = "Wi-Fi fotocamera",
                caption = "Il telefono entra nella rete della fotocamera",
                selected = vm.mode == MainViewModel.Mode.CAMERA_WIFI,
                onClick = { vm.mode = MainViewModel.Mode.CAMERA_WIFI },
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(
            vm.mode == MainViewModel.Mode.CAMERA_WIFI,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                EosField("Nome della rete", vm.ssid, { vm.ssid = it }, placeholder = "EOS6D-…")
                Spacer(Modifier.height(12.dp))
                EosField("Chiave di crittografia", vm.password, { vm.password = it }, secret = true, placeholder = "Mostrata sul display della fotocamera")
            }
        }
        Spacer(Modifier.height(16.dp))
        EosField(
            "Indirizzo IP",
            vm.host,
            { vm.host = it },
            placeholder = "Lascia vuoto per cercarla da solo",
            keyboard = KeyboardType.Uri,
        )
        Spacer(Modifier.height(24.dp))

        if (vm.busy) {
            PrimaryButton("Annulla", vm::cancelConnect, Modifier.fillMaxWidth(), danger = true)
        } else {
            PrimaryButton("Collega", vm::connect, Modifier.fillMaxWidth(), icon = EosIcons.Camera)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = when {
                    vm.statusError -> p.exposure
                    vm.busy -> p.lcd
                    else -> p.muted
                },
                pulsing = vm.busy,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                vm.status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (vm.statusError) p.exposure else p.text,
            )
        }

        Spacer(Modifier.height(28.dp))
        Hairline()
        Expandable("Come preparare la fotocamera", initiallyOpen = false) {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SetupStep(1, "Menu Wi-Fi → Attiva, poi scegli la funzione Smartphone.")
                if (vm.mode == MainViewModel.Mode.HOTSPOT) {
                    SetupStep(2, "Accendi l'hotspot del telefono e collega la fotocamera a quella rete.")
                } else {
                    SetupStep(2, "Scegli la modalità punto di accesso della fotocamera e copia qui nome della rete e chiave.")
                }
                SetupStep(3, "Premi Collega. Se l'indirizzo IP è vuoto, EosLink cerca la fotocamera da solo.")
                SetupStep(4, "Se la fotocamera chiede di confermare «EosLink», premi OK.")
            }
        }
        Hairline()
        Expandable("Registro di collegamento") {
            val lines = vm.logs.takeLast(30).asReversed()
            Column(Modifier.padding(bottom = 12.dp)) {
                if (lines.isEmpty()) {
                    Text("Ancora nessun evento.", style = MaterialTheme.typography.bodySmall, color = p.muted)
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            lines.forEach { LogRow(it) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun HeroIris(busy: Boolean, error: Boolean) {
    val p = LocalEos.current
    val t = rememberInfiniteTransition(label = "hero")
    val spin by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "spin",
    )
    val breathe by t.animateFloat(
        0.15f, 0.9f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "breathe",
    )
    val rest by animateFloatAsState(if (error) 0.05f else 0.72f, tween(700), label = "rest")
    val edge by animateColorAsState(if (error) p.exposure else p.lcd, label = "edge")
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Iris(
            openness = if (busy) breathe else rest,
            rotation = if (busy) spin else 0f,
            bladeColor = if (p.night) p.groove else Color(0xFF4A4E55),
            edgeColor = edge,
            holeColor = if (p.night) Color.Black else Color(0xFF07080A),
            rimColor = p.raised,
            modifier = Modifier.size(196.dp),
        )
    }
}

@Composable
private fun ModeTile(
    icon: ImageVector,
    title: String,
    caption: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalEos.current
    val src = remember { MutableInteractionSource() }
    val border by animateColorAsState(if (selected) p.lcd else p.groove, label = "tileB")
    val iconBg by animateColorAsState(if (selected) p.lcd else p.groove, label = "tileI")
    Column(
        modifier
            .pressScale(src)
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(p.raised)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(20.dp))
            .clickable(src, null, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) p.onLcd else p.text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = p.text)
        Spacer(Modifier.height(4.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = p.muted)
    }
}

@Composable
private fun SetupStep(n: Int, text: String) {
    val p = LocalEos.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .border(1.dp, p.lcd, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(n.toString(), style = readout(15), color = p.lcd)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = p.text, modifier = Modifier.padding(top = 2.dp))
    }
}
