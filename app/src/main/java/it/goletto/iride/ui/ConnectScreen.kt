package it.goletto.iride.ui

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
import androidx.compose.runtime.LaunchedEffect
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
import it.goletto.iride.MainViewModel
import it.goletto.iride.camera.Brand
import androidx.compose.foundation.horizontalScroll
import it.goletto.iride.ui.theme.LocalIride
import it.goletto.iride.ui.theme.readout
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(vm: MainViewModel) {
    val p = LocalIride.current
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
            Text("Iride", style = MaterialTheme.typography.titleMedium, color = p.muted, modifier = Modifier.weight(1f))
            IconTap(
                if (p.night) IrideIcons.Sun else IrideIcons.Moon,
                if (p.night) "Disattiva modalità notte" else "Attiva modalità notte",
                vm::toggleNight,
                tint = p.muted,
            )
        }

        HeroIris(busy = vm.busy, error = vm.statusError)

        Text("Collega la tua fotocamera", style = MaterialTheme.typography.displaySmall, color = p.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Canon, Nikon, Sony, Fujifilm, Olympus e Panasonic via Wi-Fi: scatto remoto, ISO, tempi, diaframma e bilanciamento del bianco, live view, intervallometro e RAW originali. Internet resta attivo sui dati mobili.",
            style = MaterialTheme.typography.bodyMedium,
            color = p.muted,
        )
        Spacer(Modifier.height(24.dp))

        Text("Marca", style = MaterialTheme.typography.labelMedium, color = p.muted)
        Spacer(Modifier.height(8.dp))
        BrandPicker(vm.brand) { vm.brand = it }
        Spacer(Modifier.height(20.dp))
        Text("Come colleghi la fotocamera", style = MaterialTheme.typography.labelMedium, color = p.muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTile(
                icon = IrideIcons.Hotspot,
                title = "Hotspot",
                caption = "La fotocamera entra nell'hotspot del telefono",
                selected = vm.mode == MainViewModel.Mode.HOTSPOT,
                onClick = { vm.mode = MainViewModel.Mode.HOTSPOT },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                icon = IrideIcons.Wifi,
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
                IrideField("Nome della rete", vm.ssid, { vm.ssid = it }, placeholder = ssidHint(vm.brand))
                Spacer(Modifier.height(12.dp))
                IrideField("Chiave di crittografia", vm.password, { vm.password = it }, secret = true, placeholder = "Mostrata sul display della fotocamera")
            }
        }
        Spacer(Modifier.height(16.dp))
        IrideField(
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
            PrimaryButton("Collega", vm::connect, Modifier.fillMaxWidth(), icon = IrideIcons.Camera)
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

        if (vm.usbSupported) {
            Spacer(Modifier.height(20.dp))
            UsbCard(vm)
        }

        Spacer(Modifier.height(28.dp))
        Hairline()
        Expandable("Come preparare la fotocamera", initiallyOpen = false) {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SetupStep(1, firstStep(vm.brand))
                if (vm.mode == MainViewModel.Mode.HOTSPOT) {
                    SetupStep(2, "Accendi l'hotspot del telefono e collega la fotocamera a quella rete.")
                } else {
                    SetupStep(2, "Scegli la modalità punto di accesso della fotocamera e copia qui nome della rete e chiave.")
                }
                SetupStep(3, "Premi Collega. Se l'indirizzo IP è vuoto, Iride cerca la fotocamera da solo.")
                SetupStep(4, "Se la fotocamera chiede di confermare «Iride», premi OK.")
                SetupStep(5, "Se un tentativo fallisce, spegni e riaccendi il Wi-Fi della fotocamera prima di riprovare: molte fotocamere accettano un solo collegamento alla volta.")
                if (vm.brand == Brand.AUTO) {
                    Text(
                        "Con Automatico Iride cerca da sola Canon, Nikon, Sony, Fujifilm, Olympus/OM e Panasonic. Se conosci la marca, sceglierla rende il collegamento più rapido.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                    )
                }
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
        Spacer(Modifier.height(16.dp))
        GhostButton("Prova senza fotocamera (demo)", vm::connectDemo, Modifier.fillMaxWidth(), icon = IrideIcons.Star, enabled = !vm.busy)
        Spacer(Modifier.height(32.dp))
    }
}

/** Alternativa col cavo: secondaria rispetto al Wi-Fi, utile quando serve il live view più rapido. */
@Composable
private fun UsbCard(vm: MainViewModel) {
    val p = LocalIride.current
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshUsb()
            delay(1500)
        }
    }
    val name = vm.usbCamera
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (name != null) p.lcd.copy(alpha = 0.6f) else p.groove, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IrideIcons.Usb, null, tint = if (name != null) p.lcd else p.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Oppure col cavo USB", style = MaterialTheme.typography.labelLarge, color = p.text)
            Text(
                if (name != null) "$name collegata: live view quasi istantaneo"
                else "Con un adattatore OTG il live view è quasi istantaneo",
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
            )
        }
        if (name != null) {
            Spacer(Modifier.width(10.dp))
            GhostButton("Collega", vm::connectUsb, enabled = !vm.busy)
        }
    }
}

@Composable
private fun HeroIris(busy: Boolean, error: Boolean) {
    val p = LocalIride.current
    // animazioni solo durante il collegamento: a riposo lo schermo resta fermo
    val spin by rememberLoop(busy, 0f, (2 * Math.PI).toFloat(), 9000)
    val breathe by rememberLoop(busy, 0.15f, 0.9f, 1400, reverse = true)
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
    val p = LocalIride.current
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
    val p = LocalIride.current
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


/** Marche con driver disponibile, nell'ordine mostrato. */
val SupportedBrands = listOf(Brand.AUTO, Brand.CANON, Brand.NIKON, Brand.SONY, Brand.FUJIFILM, Brand.OLYMPUS, Brand.PANASONIC, Brand.OTHER_PTP)

@Composable
private fun BrandPicker(selected: Brand, onSelect: (Brand) -> Unit) {
    val p = LocalIride.current
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SupportedBrands.forEach { b ->
            val sel = b == selected
            val bg by animateColorAsState(if (sel) p.lcd else Color.Transparent, label = "brand")
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(bg)
                    .border(1.dp, if (sel) p.lcd else p.groove, RoundedCornerShape(18.dp))
                    .clickable(role = Role.RadioButton) { onSelect(b) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(b.label, style = MaterialTheme.typography.labelLarge, color = if (sel) p.onLcd else p.text)
            }
        }
    }
}

private fun ssidHint(b: Brand): String = when (b) {
    Brand.CANON -> "EOS6D-…"
    Brand.NIKON -> "Nikon_WU2_… / D750_…"
    Brand.SONY -> "DIRECT-…"
    Brand.FUJIFILM -> "FUJIFILM-X-…"
    else -> "Nome mostrato dalla fotocamera"
}

private fun firstStep(b: Brand): String = when (b) {
    Brand.CANON -> "Menu Wi-Fi → Attiva, poi scegli la funzione Smartphone."
    Brand.NIKON -> "Nikon con Wi-Fi integrato (D7200, D750, D5300…): nel menu Impostazioni → Wi-Fi attiva la connessione di rete. La fotocamera crea la sua rete: usa la modalità «Wi-Fi fotocamera» qui sopra (l'hotspot non è supportato). Per il live view la ghiera va su P, A, S o M e il tempo non su posa B."
    Brand.SONY -> "Sony a7 III e successive: nel menu Rete attiva la funzione PC remoto via Wi-Fi (se c'è l'autenticazione di accesso, disattivala). Sony meno recenti: avvia sulla fotocamera la funzione Controllo con smartphone."
    Brand.FUJIFILM -> "Avvia sulla fotocamera la connessione Wi-Fi per il controllo remoto da smartphone."
    Brand.OLYMPUS -> "Menu Wi-Fi: avvia la connessione allo smartphone in modalità privata. Usa la modalità «Wi-Fi fotocamera» qui sopra."
    Brand.PANASONIC -> "Menu Wi-Fi: avvia il controllo remoto da smartphone. Usa la modalità «Wi-Fi fotocamera» qui sopra."
    else -> "Attiva il Wi-Fi della fotocamera nella modalità di collegamento a uno smartphone o a un computer."
}
