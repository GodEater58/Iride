@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package it.goletto.iride.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.goletto.iride.MainViewModel
import it.goletto.iride.SeqPhase
import it.goletto.iride.camera.ExpoKey
import it.goletto.iride.ui.theme.LocalIride
import it.goletto.iride.ui.theme.readout
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ShootScreen(vm: MainViewModel) {
    var dialFor by remember { mutableStateOf<ExpoKey?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Viewfinder(vm)
        Spacer(Modifier.height(12.dp))
        if (vm.caps.exposureControl || vm.exposure.values.isNotEmpty()) {
            ExposurePanel(vm) { dialFor = it }
        }
        Spacer(Modifier.height(20.dp))
        ShutterRow(vm)
        Spacer(Modifier.height(28.dp))
        Hairline()
        Spacer(Modifier.height(20.dp))
        Intervalometer(vm)
        Spacer(Modifier.height(24.dp))
    }
    val prop = dialFor
    if (prop != null) {
        DialSheet(vm, prop) { dialFor = null }
    }
}

// ---------------------------------------------------------------- mirino

@Composable
private fun Viewfinder(vm: MainViewModel) {
    val p = LocalIride.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val frame = vm.liveFrame
    LaunchedEffect(vm.liveOn) {
        if (!vm.liveOn) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = "Live view",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val s = (scale * zoom).coerceIn(1f, 8f)
                            val maxX = (wPx * (s - 1f)) / 2f
                            val maxY = (hPx * (s - 1f)) / 2f
                            val o = offset + pan
                            scale = s
                            offset = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { tap ->
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                // ingrandisce 4× sul punto toccato: utile per mettere a fuoco una stella
                                val s = 4f
                                val cx = wPx / 2f
                                val cy = hPx / 2f
                                val maxX = (wPx * (s - 1f)) / 2f
                                val maxY = (hPx * (s - 1f)) / 2f
                                scale = s
                                offset = Offset(
                                    ((cx - tap.x) * s).coerceIn(-maxX, maxX),
                                    ((cy - tap.y) * s).coerceIn(-maxY, maxY),
                                )
                            }
                        })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        } else {
            ViewfinderIdle(vm)
        }

        FinderOverlay(grid = vm.gridOn && frame != null, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.fillMaxSize())

        // indicatori
        if (vm.liveOn) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(p.exposure, pulsing = true, modifier = Modifier.size(8.dp))
                Spacer(Modifier.width(6.dp))
                Text("Live", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        if (scale > 1.01f) {
            Text(
                String.format(Locale.ITALY, "%.1f×", scale),
                style = readout(16),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (frame != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(role = Role.Switch, onClick = vm::toggleGrid),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    IrideIcons.Grid,
                    if (vm.gridOn) "Nascondi griglia" else "Mostra griglia",
                    tint = if (vm.gridOn) p.lcd else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    if (frame != null) {
        Text(
            "Pizzica per ingrandire, doppio tocco per zoom 4× sul punto",
            style = MaterialTheme.typography.labelSmall,
            color = p.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ViewfinderIdle(vm: MainViewModel) {
    val p = LocalIride.current
    val t = rememberInfiniteTransition(label = "lvwait")
    val breathe by t.animateFloat(0.2f, 0.6f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "b")
    Column(
        Modifier
            .fillMaxSize()
            .clickable(enabled = !vm.liveOn && vm.caps.liveView, role = Role.Button, onClick = vm::toggleLive),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Iris(
            openness = if (vm.liveOn) breathe else 0.08f,
            bladeColor = if (p.night) p.groove else Color(0xFF3A3D43),
            edgeColor = p.lcd.copy(alpha = 0.8f),
            holeColor = Color.Black,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                !vm.caps.liveView -> "Live view non disponibile per questa fotocamera"
                vm.liveOn -> "Avvio del live view…"
                else -> "Tocca per avviare il live view"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}

/** Squadrette agli angoli, punto AF centrale e griglia dei terzi. */
@Composable
private fun FinderOverlay(grid: Boolean, color: Color, modifier: Modifier) {
    val p = LocalIride.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val m = 14.dp.toPx()
        val l = 22.dp.toPx()
        val sw = 1.5.dp.toPx()
        val c = p.lcd.copy(alpha = 0.85f)
        // angoli
        listOf(
            Offset(m, m) to Pair(1f, 1f),
            Offset(w - m, m) to Pair(-1f, 1f),
            Offset(m, h - m) to Pair(1f, -1f),
            Offset(w - m, h - m) to Pair(-1f, -1f),
        ).forEach { (o, d) ->
            drawLine(c, o, Offset(o.x + l * d.first, o.y), sw, StrokeCap.Round)
            drawLine(c, o, Offset(o.x, o.y + l * d.second), sw, StrokeCap.Round)
        }
        // punto AF centrale
        val af = 18.dp.toPx()
        drawRect(c, Offset(w / 2 - af / 2, h / 2 - af / 2), Size(af, af), style = Stroke(sw))
        if (grid) {
            val g = color.copy(alpha = 0.35f)
            for (i in 1..2) {
                drawLine(g, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), 1.dp.toPx())
                drawLine(g, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), 1.dp.toPx())
            }
        }
    }
}

// ---------------------------------------------------------------- pannello esposizione

@Composable
private fun ExposurePanel(vm: MainViewModel, onEdit: (ExpoKey) -> Unit) {
    val p = LocalIride.current
    val e = vm.exposure
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.raised)
            .border(1.dp, p.groove, RoundedCornerShape(18.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpoCell(e.text(ExpoKey.MODE), ExpoKey.MODE.short, false, Modifier.weight(0.8f)) {}
        PanelDivider()
        ExpoCell(e.text(ExpoKey.TV), ExpoKey.TV.short, e.canEdit(ExpoKey.TV), Modifier.weight(1.2f)) { onEdit(ExpoKey.TV) }
        ExpoCell(e.text(ExpoKey.AV), ExpoKey.AV.short, e.canEdit(ExpoKey.AV), Modifier.weight(1.1f)) { onEdit(ExpoKey.AV) }
        ExpoCell(e.text(ExpoKey.ISO), ExpoKey.ISO.short, e.canEdit(ExpoKey.ISO), Modifier.weight(1.1f)) { onEdit(ExpoKey.ISO) }
        if (e.values.containsKey(ExpoKey.SHOTS)) {
            PanelDivider()
            ExpoCell(e.text(ExpoKey.SHOTS), ExpoKey.SHOTS.short, false, Modifier.weight(1f), dim = true) {}
        }
    }
}

@Composable
private fun PanelDivider() {
    Box(Modifier.width(1.dp).height(34.dp).background(LocalIride.current.groove))
}

@Composable
private fun ExpoCell(
    value: String,
    label: String,
    editable: Boolean,
    modifier: Modifier,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalIride.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = editable, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = readout(24), color = if (dim) p.text else p.lcd, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1)
            if (editable) {
                Spacer(Modifier.width(3.dp))
                Box(Modifier.size(4.dp).clip(CircleShape).background(p.lcd))
            }
        }
    }
}

// ---------------------------------------------------------------- scatto

@Composable
private fun ShutterRow(vm: MainViewModel) {
    val p = LocalIride.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundToggle(IrideIcons.LiveView, "Live view", vm.liveOn, vm::toggleLive, enabled = vm.caps.liveView)
            ShutterButton(vm)
            RoundToggle(IrideIcons.Focus, "Autofocus", vm.autofocus, { vm.autofocus = !vm.autofocus }, enabled = !vm.intervalRunning && vm.caps.autofocusToggle)
        }
        AnimatedVisibility(vm.intervalRunning, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 14.dp)) {
                Text(
                    "Scatto ${minOf(vm.seqDone + 1, vm.seqTotal)} di ${vm.seqTotal}",
                    style = readout(20),
                    color = p.text,
                )
                Text(vm.intervalStatus, style = MaterialTheme.typography.bodySmall, color = p.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ShutterButton(vm: MainViewModel) {
    val p = LocalIride.current
    val haptics = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val iris = remember { Animatable(0.78f) }
    val exposing = vm.intervalRunning && vm.seqPhase == SeqPhase.EXPOSING

    // scatto singolo: il diaframma si chiude e si riapre
    LaunchedEffect(vm.shotTick) {
        if (vm.shotTick == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        iris.animateTo(0.04f, tween(90, easing = FastOutSlowInEasing))
        if (!(vm.intervalRunning && vm.intBulb.trim().let { it.isNotEmpty() && it != "0" })) {
            iris.animateTo(0.78f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(exposing, vm.seqPhase) {
        if (!exposing && iris.value < 0.5f && vm.seqPhase != SeqPhase.EXPOSING) {
            iris.animateTo(0.78f, tween(320))
        }
    }

    val seqFrac by animateFloatAsState(
        if (vm.seqTotal > 0) vm.seqDone / vm.seqTotal.toFloat() else 0f,
        tween(400),
        label = "seq",
    )
    val ringColor by animateColorAsState(if (exposing) p.exposure else p.lcd, label = "ring")
    val spinT = rememberInfiniteTransition(label = "spin")
    val busySpin by spinT.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "bs")

    Box(
        Modifier
            .size(128.dp)
            .pressScale(src)
            .clip(CircleShape)
            .clickable(src, null, enabled = !vm.intervalRunning, role = Role.Button, onClick = vm::shoot)
            .semantics { contentDescription = "Scatta" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawCircle(p.raised, radius = size.minDimension / 2)
            drawArc(p.groove, 0f, 360f, false, tl, arcSize, style = Stroke(stroke))
            if (vm.intervalRunning) {
                // avanzamento della sequenza
                drawArc(p.lcd, -90f, 360f * seqFrac, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                // fase corrente (posa o attesa) sull'anello interno
                val inner = 10.dp.toPx()
                val iTl = Offset(inset + inner, inset + inner)
                val iSize = Size(arcSize.width - inner * 2, arcSize.height - inner * 2)
                when (vm.seqPhase) {
                    SeqPhase.EXPOSING, SeqPhase.WAITING -> drawArc(
                        if (vm.seqPhase == SeqPhase.EXPOSING) ringColor else p.muted,
                        -90f, 360f * vm.seqPhaseFraction, false, iTl, iSize,
                        style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                    SeqPhase.BUSY -> drawArc(p.muted, busySpin, 70f, false, iTl, iSize, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                    else -> Unit
                }
            }
        }
        Iris(
            openness = if (exposing) 0.04f else iris.value,
            bladeColor = if (p.night) p.groove else Color(0xFF50545B),
            edgeColor = if (exposing) p.exposure else p.lcd,
            holeColor = Color.Black,
            modifier = Modifier.size(92.dp),
        )
        if (vm.intervalRunning && vm.seqSecondsLeft > 0) {
            Text(
                vm.seqSecondsLeft.toString(),
                style = readout(26),
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- intervallometro

@Composable
private fun Intervalometer(vm: MainViewModel) {
    val p = LocalIride.current
    val running = vm.intervalRunning
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(IrideIcons.Timer, null, tint = p.lcd, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text("Intervallometro", style = MaterialTheme.typography.titleLarge, color = p.text, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(vm.estimatedSeconds()), style = readout(20), color = p.text)
            Text("durata stimata", style = MaterialTheme.typography.labelSmall, color = p.muted)
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        vm.presets.forEach { preset ->
            PresetChip(preset.name, enabled = !running) { vm.applyPreset(preset) }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LcdStepper("Scatti", vm.intCount, "foto", { vm.intCount = it }, { vm.stepCount(-1) }, { vm.stepCount(1) }, Modifier.weight(1f), enabled = !running)
        LcdStepper("Pausa", vm.intPause, "secondi", { vm.intPause = it }, { vm.stepPause(-1) }, { vm.stepPause(1) }, Modifier.weight(1f), enabled = !running)
        LcdStepper("Posa B", vm.intBulb, "secondi", { vm.intBulb = it }, { vm.stepBulb(-1) }, { vm.stepBulb(1) }, Modifier.weight(1f), enabled = !running && vm.caps.bulb)
    }
    Spacer(Modifier.height(10.dp))
    Text(
        if (vm.caps.bulb) "Con Posa B a 0 vale il tempo impostato sulla fotocamera. Per pose oltre 30 secondi metti la fotocamera in posa B (su Nikon serve il modo M)." else "La posa B da remoto non è disponibile per questa fotocamera: vale il tempo impostato sulla fotocamera.",
        style = MaterialTheme.typography.bodySmall,
        color = p.muted,
    )
    Spacer(Modifier.height(16.dp))
    if (running) {
        PrimaryButton("Interrompi sequenza", vm::stopInterval, Modifier.fillMaxWidth(), danger = true)
    } else {
        PrimaryButton("Avvia sequenza", vm::startInterval, Modifier.fillMaxWidth(), icon = IrideIcons.Timer)
        if (vm.intervalStatus.isNotEmpty()) {
            Text(
                vm.intervalStatus,
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun PresetChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalIride.current
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(src)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, p.groove, RoundedCornerShape(18.dp))
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (enabled) p.text else p.muted)
    }
}

// ---------------------------------------------------------------- ghiera valori

@Composable
private fun DialSheet(vm: MainViewModel, prop: ExpoKey, onDismiss: () -> Unit) {
    val p = LocalIride.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val options = vm.exposure.options[prop].orEmpty()
    val values = options.map { it.code }
    val labels = options.associate { it.code to it.label }
    val current = vm.exposure.values[prop]?.code
    val startIndex = values.indexOf(current).coerceAtLeast(0)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.raised,
        contentColor = p.text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(prop.title, style = MaterialTheme.typography.titleMedium, color = p.muted)
            Spacer(Modifier.height(4.dp))
            Text(vm.exposure.text(prop), style = readout(56), color = p.lcd)
            Spacer(Modifier.height(16.dp))
            if (values.isEmpty()) {
                Text("La fotocamera non ha inviato i valori disponibili.", color = p.muted)
            } else {
                Dial(values, startIndex, format = { labels[it] ?: it.toString() }) { v ->
                    if (v != vm.exposure.values[prop]?.code) vm.setExposure(prop, v)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Scorri la ghiera o tocca un valore", style = MaterialTheme.typography.labelSmall, color = p.muted)
        }
    }
}

/** Ghiera orizzontale a scatti: il valore al centro viene applicato quando si ferma. */
@Composable
private fun Dial(values: List<Long>, startIndex: Int, format: (Long) -> String, onSelect: (Long) -> Unit) {
    val p = LocalIride.current
    val itemW = 76.dp
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fling = rememberSnapFlingBehavior(state)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemPx = with(density) { itemW.toPx() }
    val centerIndex = if (state.firstVisibleItemScrollOffset > itemPx / 2) state.firstVisibleItemIndex + 1 else state.firstVisibleItemIndex

    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            val idx = centerIndex.coerceIn(0, values.lastIndex)
            onSelect(values[idx])
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = (maxWidth - itemW) / 2
        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side),
            modifier = Modifier.fillMaxWidth().height(84.dp),
        ) {
            itemsIndexed(values) { i, v ->
                val sel = i == centerIndex
                Column(
                    Modifier
                        .width(itemW)
                        .fillMaxSize()
                        .clickable { scope.launch { state.animateScrollToItem(i) } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        format(v),
                        style = readout(if (sel) 26 else 20),
                        color = if (sel) p.lcd else p.muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(2.dp).height(if (sel) 18.dp else 10.dp).background(if (sel) p.lcd else p.groove))
                }
            }
        }
        // indice centrale
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(itemW)
                .height(84.dp)
                .border(1.dp, p.lcd.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
        )
    }
}
