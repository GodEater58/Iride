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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
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
import it.goletto.iride.camera.LvQuality
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
    // il fotogramma si legge solo nella fase di disegno (LiveImage): qui basta sapere se c'è
    val hasFrame = vm.hasFrame
    val assist = vm.starAssist
    LaunchedEffect(vm.liveOn, assist) {
        // in modalità stelle si usa lo zoom della fotocamera, non quello dell'app
        if (!vm.liveOn || assist) {
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
        if (hasFrame) {
            LiveImage(
                vm,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (assist) {
                            Modifier
                                .pointerInput(assist) {
                                    detectTapGestures(onTap = { tap ->
                                        imageFraction(vm, tap, wPx, hPx)?.let { (fx, fy) -> vm.tapViewfinder(fx, fy) }
                                    })
                                }
                                .pointerInput(assist) {
                                    // trascinando sul mirino ingrandito si sposta il riquadro della fotocamera
                                    var total = Offset.Zero
                                    detectDragGestures(
                                        onDragStart = { total = Offset.Zero },
                                        onDragEnd = {
                                            if (vm.zoomIndex > 0 && vm.caps.zoomPosition) vm.dragZoom(-total.x / wPx, -total.y / hPx)
                                        },
                                    ) { change, drag ->
                                        change.consume()
                                        total += drag
                                    }
                                }
                        } else {
                            Modifier
                                .pointerInput(assist) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val s = (scale * zoom).coerceIn(1f, 8f)
                                        val maxX = (wPx * (s - 1f)) / 2f
                                        val maxY = (hPx * (s - 1f)) / 2f
                                        val o = offset + pan
                                        scale = s
                                        offset = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                                    }
                                }
                                .pointerInput(assist) {
                                    detectTapGestures(onDoubleTap = { tap ->
                                        if (scale > 1.01f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            // ingrandisce 4× sul punto toccato (zoom dell'app)
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
                        },
                    )
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

        FinderOverlay(grid = vm.gridOn && hasFrame, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.fillMaxSize())

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
        if (vm.liveOn && vm.lvStats.isNotEmpty()) {
            Text(
                vm.lvStats,
                style = readout(13),
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
        if (hasFrame) {
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.caps.autofocusButton) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable(enabled = !vm.focusing, role = Role.Button, onClick = vm::focus)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(IrideIcons.Focus, null, tint = if (vm.focusing) p.lcd else Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (vm.focusing) "Fuoco…" else "AF", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (assist) p.lcd else Color.Black.copy(alpha = 0.5f))
                        .clickable(role = Role.Switch, onClick = vm::toggleStarAssist)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IrideIcons.Star, null, tint = if (assist) p.onLcd else Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stelle", style = MaterialTheme.typography.labelMedium, color = if (assist) p.onLcd else Color.White)
                }
            }
        }
        if (hasFrame) {
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
    if (assist && hasFrame) {
        StarFocusPanel(vm)
        return
    }
    if (vm.caps.liveViewQuality) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live view", style = MaterialTheme.typography.labelMedium, color = p.muted, modifier = Modifier.padding(end = 10.dp))
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.raised)
                    .padding(3.dp),
            ) {
                LvQuality.entries.forEach { q ->
                    val sel = q == vm.lvQuality
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (sel) p.lcd else Color.Transparent)
                            .clickable(role = Role.RadioButton) { vm.changeLvQuality(q) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(q.label, style = MaterialTheme.typography.labelMedium, color = if (sel) p.onLcd else p.text)
                    }
                }
            }
        }
    }
    if (hasFrame) {
        Text(
            "Pizzica per ingrandire, doppio tocco per zoom 4× sul punto. «Fluido» per inquadrare, «Dettaglio» per la messa a fuoco manuale.",
            style = MaterialTheme.typography.labelSmall,
            color = p.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Punto toccato → frazione (0..1) dell'immagine, tenendo conto delle bande nere ai lati. */
private fun imageFraction(vm: MainViewModel, tap: Offset, w: Float, h: Float): Pair<Float, Float>? {
    val img = vm.liveFrame ?: return null
    val k = minOf(w / img.width, h / img.height)
    val dw = img.width * k
    val dh = img.height * k
    val fx = (tap.x - (w - dw) / 2f) / dw
    val fy = (tap.y - (h - dh) / 2f) / dh
    if (fx !in 0f..1f || fy !in 0f..1f) return null
    return fx to fy
}

/** Pannello dell'assistente: dimensione della stella, grafico, ingrandimento e passi di messa a fuoco. */
@Composable
private fun StarFocusPanel(vm: MainViewModel) {
    val p = LocalIride.current
    val now = vm.hfrNow
    val best = vm.hfrBest
    val good = now > 0f && best > 0f && now <= best * 1.05f
    val m = vm.starMeasure
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(p.raised)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Dimensione stella (HFR)", style = MaterialTheme.typography.labelSmall, color = p.muted)
                Text(
                    if (now > 0f) String.format(Locale.ITALY, "%.2f", now) else "–",
                    style = readout(40),
                    color = if (good) Color(0xFF4CD964) else p.lcd,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Migliore", style = MaterialTheme.typography.labelSmall, color = p.muted)
                Text(if (best > 0f) String.format(Locale.ITALY, "%.2f", best) else "–", style = readout(22), color = p.text)
            }
        }
        // andamento: si cerca il punto più basso
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(vertical = 6.dp),
        ) {
            val h = vm.hfrHistory
            if (h.size < 2) return@Canvas
            val lo = h.min()
            val hi = maxOf(h.max(), lo + 0.5f)
            val stepX = size.width / (90 - 1)
            fun yOf(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
            val b = vm.hfrBest
            if (b > 0f) drawLine(Color(0xFF4CD964).copy(alpha = 0.5f), Offset(0f, yOf(b)), Offset(size.width, yOf(b)), 1.dp.toPx())
            for (i in 1 until h.size) {
                drawLine(p.lcd, Offset((i - 1) * stepX, yOf(h[i - 1])), Offset(i * stepX, yOf(h[i])), 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text(
            when {
                m == null -> "Nessuna stella: tocca una stella luminosa nel mirino"
                m.saturated -> "Stella satura: abbassa ISO o tempo per una misura più precisa"
                good -> "A fuoco: è il valore migliore finora"
                else -> "Gira la messa a fuoco finché il numero scende al minimo"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (m == null) p.muted else p.text,
        )

        val levels = vm.caps.zoomLevels
        if (levels.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.body)
                    .padding(3.dp),
            ) {
                levels.forEachIndexed { i, label ->
                    val sel = i == vm.zoomIndex
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (sel) p.lcd else Color.Transparent)
                            .clickable(role = Role.RadioButton) { vm.setZoom(i) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (sel) p.onLcd else p.text)
                    }
                }
            }
        }

        if (vm.caps.focusDrive) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("◂ Vicino", style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.weight(1f))
                Text("Infinito ▸", style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-3 to "‹‹‹", -2 to "‹‹", -1 to "‹", 1 to "›", 2 to "››", 3 to "›››").forEach { (step, label) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, p.groove, RoundedCornerShape(12.dp))
                            .clickable(enabled = !vm.focusMoving, role = Role.Button) { vm.focusStep(step) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = readout(20), color = if (vm.focusMoving) p.muted else p.text)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            buildString {
                if (vm.caps.zoomPosition) append("Tocca una stella: la fotocamera ingrandisce lì. Trascina per spostare il riquadro, tocca per scegliere un'altra stella. ")
                else append("Tocca una stella per misurarla. ")
                if (vm.caps.focusDrive) append("Per i tasti di messa a fuoco l'obiettivo deve essere su AF.")
                else append("Metti a fuoco con la ghiera dell'obiettivo.")
            },
            style = MaterialTheme.typography.labelSmall,
            color = p.muted,
        )
    }
}

/**
 * Il fotogramma del live view. Lo stato viene letto dentro drawBehind: a ogni fotogramma
 * si ridisegna solo questo riquadro, senza ricomporre né rimisurare il mirino.
 */
@Composable
private fun LiveImage(vm: MainViewModel, modifier: Modifier) {
    Spacer(
        modifier
            .semantics { contentDescription = "Live view" }
            .drawBehind {
                val img = vm.liveFrame ?: return@drawBehind
                val iw = img.width.toFloat()
                val ih = img.height.toFloat()
                val k = minOf(size.width / iw, size.height / ih)
                val dw = (iw * k).roundToInt()
                val dh = (ih * k).roundToInt()
                val ox = ((size.width - dw) / 2f).roundToInt()
                val oy = ((size.height - dh) / 2f).roundToInt()
                drawImage(
                    img,
                    dstOffset = IntOffset(ox, oy),
                    dstSize = IntSize(dw, dh),
                    filterQuality = FilterQuality.Low,
                )
                if (vm.starAssist) {
                    // stella inseguita: cerchio verde se è al meglio della sessione
                    val m = vm.starMeasure
                    if (m != null) {
                        val sx = ox + m.x * dw / m.frameW
                        val sy = oy + m.y * dh / m.frameH
                        val best = vm.hfrBest
                        val good = best > 0f && vm.hfrNow <= best * 1.05f
                        val rad = maxOf(14.dp.toPx(), m.hfr * 3f * dw / m.frameW)
                        drawCircle(if (good) Color(0xFF4CD964) else Color(0xFFF3A83B), rad, Offset(sx, sy), style = Stroke(2.dp.toPx()))
                    }
                }
            },
    )
}

@Composable
private fun ViewfinderIdle(vm: MainViewModel) {
    val p = LocalIride.current
    val breathe by rememberLoop(vm.liveOn, 0.2f, 0.6f, 1100, reverse = true)
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.raised)
            .border(1.dp, p.groove, RoundedCornerShape(18.dp))
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        // valori principali, come sul display superiore
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(ExpoKey.TV, ExpoKey.AV, ExpoKey.ISO, ExpoKey.EV).forEach { k ->
                ExpoCell(e.text(k), k.short, e.canEdit(k), Modifier.weight(1f)) { onEdit(k) }
            }
        }
        Hairline(Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryCell(e.text(ExpoKey.MODE), ExpoKey.MODE.short, e.canEdit(ExpoKey.MODE), Modifier.weight(0.8f)) { onEdit(ExpoKey.MODE) }
            SecondaryCell(e.text(ExpoKey.WB), ExpoKey.WB.short, e.canEdit(ExpoKey.WB), Modifier.weight(1.4f)) { onEdit(ExpoKey.WB) }
            SecondaryCell(e.text(ExpoKey.SHOTS), ExpoKey.SHOTS.short, false, Modifier.weight(1f)) {}
        }
    }
    if (vm.exposure.values.isNotEmpty()) {
        Text(
            if (listOf(ExpoKey.TV, ExpoKey.AV, ExpoKey.ISO, ExpoKey.EV, ExpoKey.WB, ExpoKey.MODE).any { e.canEdit(it) })
                "Tocca un valore con il punto per cambiarlo. I valori grigi dipendono dalla ghiera o dal modo di scatto."
            else
                "La fotocamera non permette di cambiare questi valori da remoto nel modo di scatto attuale.",
            style = MaterialTheme.typography.labelSmall,
            color = p.muted,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
}

@Composable
private fun SecondaryCell(value: String, label: String, editable: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalIride.current
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = editable, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1)
            Text(value, style = readout(18), color = if (editable) p.lcd else p.text, maxLines = 1)
        }
        if (editable) Icon(IrideIcons.Chevron, null, tint = p.lcd, modifier = Modifier.size(14.dp))
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
            FocusButton(vm)
        }
        if (vm.caps.autofocusToggle) {
            Spacer(Modifier.height(12.dp))
            SmallSwitch("Autofocus allo scatto", vm.autofocus, enabled = !vm.intervalRunning) { vm.autofocus = !vm.autofocus }
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
        val bulbSequence = vm.intervalRunning && vm.caps.bulb && vm.intBulb.trim().let { it.isNotEmpty() && it != "0" }
        if (!bulbSequence) {
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
    val busySpin by rememberLoop(vm.intervalRunning && vm.seqPhase == SeqPhase.BUSY, 0f, 360f, 1200)

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
            Text(vm.exposure.text(prop), style = readout(if (prop.isList) 36 else 56), color = p.lcd)
            Spacer(Modifier.height(16.dp))
            if (values.isEmpty()) {
                Text("La fotocamera non ha inviato i valori disponibili.", color = p.muted)
            } else if (prop.isList) {
                ChoiceList(options.map { it.code to it.label }, current) { v ->
                    if (v != vm.exposure.values[prop]?.code) vm.setExposure(prop, v)
                }
            } else {
                Dial(values, startIndex, format = { labels[it] ?: it.toString() }) { v ->
                    if (v != vm.exposure.values[prop]?.code) vm.setExposure(prop, v)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (prop.isList) "Tocca la voce da impostare" else "Scorri la ghiera o tocca un valore", style = MaterialTheme.typography.labelSmall, color = p.muted)
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

    // applica il valore solo dopo che l'utente ha girato la ghiera, non all'apertura
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            touched = true
        } else if (touched) {
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


/** Elenco a riquadri per i valori testuali (bilanciamento del bianco, modo di scatto). */
@Composable
private fun ChoiceList(items: List<Pair<Long, String>>, current: Long?, onSelect: (Long) -> Unit) {
    val p = LocalIride.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (code, label) ->
                    val sel = code == current
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (sel) p.lcd else p.body)
                            .border(1.dp, if (sel) p.lcd else p.groove, RoundedCornerShape(14.dp))
                            .clickable(role = Role.RadioButton) { onSelect(code) }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) p.onLcd else p.text, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


/** Tasto «Metti a fuoco»: anello che gira durante la messa a fuoco, verde o rosso all'esito. */
@Composable
private fun FocusButton(vm: MainViewModel) {
    val p = LocalIride.current
    val enabled = vm.caps.autofocusButton && !vm.intervalRunning
    val src = remember { MutableInteractionSource() }
    val spin by rememberLoop(vm.focusing, 0f, 360f, 900)
    val ring by animateColorAsState(
        when (vm.focusResult) {
            true -> p.ok
            false -> p.exposure
            null -> p.lcd
        },
        label = "afring",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .pressScale(src)
                .size(52.dp)
                .clip(CircleShape)
                .background(p.raised)
                .border(1.dp, p.groove, CircleShape)
                .clickable(src, null, enabled = enabled && !vm.focusing, role = Role.Button, onClick = vm::focus),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 2.5.dp.toPx()
                val tl = Offset(st, st)
                val sz = Size(size.width - st * 2, size.height - st * 2)
                when {
                    vm.focusing -> drawArc(ring, spin, 90f, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
                    vm.focusResult != null -> drawArc(ring, 0f, 360f, false, tl, sz, style = Stroke(st))
                }
            }
            Icon(IrideIcons.Focus, "Metti a fuoco", tint = if (enabled) p.text else p.groove, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("Metti a fuoco", style = MaterialTheme.typography.labelSmall, color = if (vm.focusing) p.lcd else p.muted)
    }
}

@Composable
private fun SmallSwitch(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalIride.current
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 34.dp, height = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) p.lcd else p.groove)
                .padding(2.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(if (on) p.onLcd else p.muted))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) p.text else p.muted)
    }
}
