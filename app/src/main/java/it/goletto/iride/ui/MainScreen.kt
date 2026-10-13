package it.goletto.iride.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import it.goletto.iride.MainViewModel
import it.goletto.iride.ui.theme.LocalIride

@Composable
fun IrideApp(vm: MainViewModel) {
    val p = LocalIride.current
    Box(Modifier.fillMaxSize().background(p.body)) {
        AnimatedContent(
            targetState = vm.connected,
            transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) },
            label = "root",
        ) { connected ->
            if (connected) MainScreen(vm) else ConnectScreen(vm)
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel) {
    val p = LocalIride.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(p.body)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopBar(vm)
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "tab",
            ) { t ->
                when (t) {
                    0 -> ShootScreen(vm)
                    1 -> {
                        LaunchedEffect(Unit) { vm.ensureFilesLoaded() }
                        GalleryScreen(vm)
                    }
                    else -> LogScreen(vm)
                }
            }
        }
        BottomBar(
            tab = tab,
            onTab = { tab = it },
            galleryBadge = vm.files.size,
            logAlert = vm.logs.lastOrNull()?.error == true,
        )
    }
}

@Composable
private fun TopBar(vm: MainViewModel) {
    val p = LocalIride.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(p.ok, pulsing = vm.intervalRunning || vm.downloading)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(vm.model, style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1)
            Text(
                if (vm.mode == MainViewModel.Mode.CAMERA_WIFI) "Wi-Fi della fotocamera, ${vm.cameraHost}" else "Via hotspot, ${vm.cameraHost}",
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
            )
        }
        IconTap(
            if (p.night) IrideIcons.Sun else IrideIcons.Moon,
            if (p.night) "Disattiva modalità notte" else "Attiva modalità notte",
            vm::toggleNight,
            tint = p.muted,
        )
        IconTap(IrideIcons.Power, "Scollega", vm::disconnect, tint = p.muted)
    }
}

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit, galleryBadge: Int, logAlert: Boolean) {
    val p = LocalIride.current
    Column {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            NavItem(IrideIcons.Camera, "Scatto", tab == 0, Modifier.weight(1f)) { onTab(0) }
            NavItem(
                IrideIcons.Gallery,
                if (galleryBadge > 0) "Galleria $galleryBadge" else "Galleria",
                tab == 1,
                Modifier.weight(1f),
            ) { onTab(1) }
            NavItem(IrideIcons.Log, "Registro", tab == 2, Modifier.weight(1f), alert = logAlert) { onTab(2) }
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    alert: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalIride.current
    val pill by animateColorAsState(if (selected) p.lcd else p.body, label = "pill")
    val fg by animateColorAsState(if (selected) p.onLcd else p.muted, label = "navfg")
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(pill)
                    .padding(horizontal = 20.dp, vertical = 5.dp),
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
            }
            if (alert && !selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 12.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(p.exposure)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) p.text else p.muted)
    }
}
