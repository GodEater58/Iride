@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package it.goletto.iride.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.goletto.iride.MainViewModel
import it.goletto.iride.camera.CamFile
import it.goletto.iride.ui.theme.LocalIride
import it.goletto.iride.ui.theme.NightImageFilter
import it.goletto.iride.ui.theme.readout
import java.text.SimpleDateFormat
import java.util.Locale

private enum class Filter(val label: String) { ALL("Tutti"), RAW("RAW"), JPG("JPG") }

private val FOLDERS = listOf("" to "Cartella principale", "Light" to "Light", "Dark" to "Dark", "Flat" to "Flat", "Bias" to "Bias")

private val dayIn = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
private val dayOut = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALY)

private val dayCache = HashMap<String, String>()

/** Etichetta del giorno; le date si ripetono molto, quindi si calcola una volta per giorno. */
private fun dayLabel(captureDate: String): String {
    if (captureDate.length < 8) return "Data sconosciuta"
    val key = captureDate.substring(0, 8)
    return dayCache.getOrPut(key) {
        try {
            dayOut.format(dayIn.parse(key)!!).replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Data sconosciuta"
        }
    }
}

private fun timeLabel(captureDate: String): String {
    val t = captureDate.substringAfter('T', "")
    return if (t.length >= 4) "${t.substring(0, 2)}:${t.substring(2, 4)}" else ""
}

private fun sizeLabel(bytes: Long): String = String.format(Locale.ITALY, "%.1f MB", bytes / 1048576.0)

private fun kind(f: CamFile) = f.kind

@Composable
fun GalleryScreen(vm: MainViewModel) {
    val p = LocalIride.current
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var preview by remember { mutableStateOf<CamFile?>(null) }

    val all = vm.files
    // ricalcolati solo quando cambiano i file o il filtro, non a ogni selezione o avanzamento
    val shown by remember(filter) {
        derivedStateOf {
            when (filter) {
                Filter.ALL -> all.toList()
                Filter.RAW -> all.filter { it.isRaw }
                Filter.JPG -> all.filter { it.isJpeg }
            }
        }
    }
    val groups by remember { derivedStateOf { shown.groupBy { dayLabel(it.captureDate) } } }
    val counts by remember { derivedStateOf { Triple(all.size, all.count { it.isRaw }, all.count { it.isJpeg }) } }

    Column(Modifier.fillMaxSize()) {
        // intestazione
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(!vm.selecting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.weight(1f)) {
                Column {
                    Text("Galleria", style = MaterialTheme.typography.titleLarge, color = p.text)
                    Text(
                        vm.galleryStatus.takeIf { it.isNotEmpty() && !vm.downloading } ?: "${all.size} file sulla scheda",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(vm.selecting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.weight(1f)) {
                Text(
                    if (vm.selected.size == 1) "1 selezionato" else "${vm.selected.size} selezionati",
                    style = MaterialTheme.typography.titleLarge,
                    color = p.lcd,
                )
            }
            if (vm.selecting) {
                IconTap(IrideIcons.Check, "Seleziona tutti", { vm.selectAll(shown.map { it.id }) }, tint = p.text)
                IconTap(IrideIcons.Close, "Annulla selezione", vm::clearSelection, tint = p.text)
            } else {
                IconTap(IrideIcons.Refresh, "Aggiorna", vm::refreshFiles, tint = p.muted)
            }
        }

        // filtro
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.raised)
                .padding(4.dp),
        ) {
            Filter.entries.forEach { f ->
                val n = when (f) {
                    Filter.ALL -> counts.first
                    Filter.RAW -> counts.second
                    Filter.JPG -> counts.third
                }
                val sel = f == filter
                val bg by animateColorAsState(if (sel) p.lcd else Color.Transparent, label = "fbg")
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .clickable(role = Role.Tab) { filter = f }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(f.label, style = MaterialTheme.typography.labelMedium, color = if (sel) p.onLcd else p.text)
                    Spacer(Modifier.width(6.dp))
                    Text(n.toString(), style = readout(15), color = if (sel) p.onLcd else p.muted)
                }
            }
        }

        if (!vm.caps.cardBrowsing && vm.caps.galleryNote.isNotEmpty() && shown.isNotEmpty()) {
            Text(
                vm.caps.galleryNote,
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            if (shown.isEmpty()) {
                EmptyGallery(vm, filter != Filter.ALL)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    groups.forEach { (day, list) ->
                        item(key = "h_$day", span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                                Text(day, style = MaterialTheme.typography.titleSmall, color = p.text, modifier = Modifier.weight(1f))
                                Text("${list.size}", style = readout(15), color = p.muted)
                            }
                        }
                        items(list, key = { it.id }) { f ->
                            Tile(
                                vm = vm,
                                f = f,
                                selected = f.id in vm.selected,
                                onClick = { if (vm.selecting) vm.toggleSelect(f.id) else preview = f },
                                onLongClick = { vm.toggleSelect(f.id) },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            vm.selecting || vm.downloading,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            DownloadBar(vm)
        }
    }

    val pv = preview
    if (pv != null) {
        PreviewSheet(vm, pv, onDismiss = { preview = null })
    }
}

@Composable
private fun EmptyGallery(vm: MainViewModel, filtered: Boolean) {
    val p = LocalIride.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(IrideIcons.Gallery, null, tint = p.groove, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                vm.galleryStatus.startsWith("Lettura") -> vm.galleryStatus
                filtered -> "Nessun file di questo tipo sulla scheda"
                else -> "La scheda è vuota"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = p.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            vm.caps.galleryNote.ifEmpty { "Le foto scattate da Iride compaiono qui appena la fotocamera le salva." },
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
    }
}

@Composable
private fun Tile(vm: MainViewModel, f: CamFile, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalIride.current
    val hasThumb = vm.thumbs.containsKey(f.id)
    LaunchedEffect(f.id, hasThumb) { if (!hasThumb) vm.loadThumb(f) }
    val inset by animateFloatAsState(if (selected) 0.88f else 1f, tween(160), label = "inset")
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) p.lcdDim else p.raised)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val thumb = vm.thumbs[f.id]
        Box(
            Modifier
                .fillMaxSize()
                .scale(inset)
                .clip(RoundedCornerShape(if (selected) 8.dp else 0.dp))
                .background(p.groove),
        ) {
            if (thumb != null) {
                Image(thumb, f.name, contentScale = ContentScale.Crop, colorFilter = if (p.night) NightImageFilter else null, modifier = Modifier.fillMaxSize())
            }
        }
        // tipo file
        Text(
            kind(f),
            style = readout(12),
            color = if (f.isRaw) p.onLcd else Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (f.isRaw) p.lcd else Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        if (vm.selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) p.lcd else Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, if (selected) p.lcd else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(IrideIcons.Check, null, tint = p.onLcd, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun DownloadBar(vm: MainViewModel) {
    val p = LocalIride.current
    val frac by animateFloatAsState(vm.dlFraction, tween(250), label = "dl")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(p.raised)
            .padding(16.dp),
    ) {
        if (vm.downloading) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("File ${vm.dlIndex} di ${vm.dlTotal}", style = readout(22), color = p.text, modifier = Modifier.weight(1f))
                Text("${(frac * 100).toInt()}%", style = readout(22), color = p.lcd)
            }
            Spacer(Modifier.height(4.dp))
            Text(vm.galleryStatus, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.groove)) {
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.lcd))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Via Wi-Fi un RAW può richiedere fino a 20 secondi.",
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
            )
        } else {
            Text("Salva in", style = MaterialTheme.typography.labelMedium, color = p.muted)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FOLDERS.forEach { (value, label) ->
                    val sel = vm.subfolder == value
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) p.lcd else Color.Transparent)
                            .border(1.dp, if (sel) p.lcd else p.groove, RoundedCornerShape(16.dp))
                            .clickable(role = Role.RadioButton) { vm.subfolder = value }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (sel) p.onLcd else p.text)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val bytes = vm.files.filter { it.id in vm.selected }.sumOf { it.size }
            PrimaryButton(
                "Scarica ${vm.selected.size} file (${sizeLabel(bytes)})",
                vm::downloadSelected,
                Modifier.fillMaxWidth(),
                icon = IrideIcons.Download,
            )
        }
    }
}

@Composable
private fun PreviewSheet(vm: MainViewModel, f: CamFile, onDismiss: () -> Unit) {
    val p = LocalIride.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = p.raised, contentColor = p.text) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = vm.thumbs[f.id]
                if (thumb != null) {
                    Image(thumb, f.name, contentScale = ContentScale.Fit, colorFilter = if (p.night) NightImageFilter else null, modifier = Modifier.fillMaxSize())
                } else {
                    Text("Anteprima non disponibile", color = p.muted)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(f.name, style = MaterialTheme.typography.titleLarge, color = p.text)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Readout(kind(f), "Formato", Modifier.weight(1f), size = 24)
                Readout(sizeLabel(f.size), "Dimensione", Modifier.weight(1f), size = 24, color = p.text)
                Readout(timeLabel(f.captureDate).ifEmpty { "--" }, "Ora", Modifier.weight(1f), size = 24, color = p.text)
            }
            Spacer(Modifier.height(8.dp))
            Text(dayLabel(f.captureDate), style = MaterialTheme.typography.bodySmall, color = p.muted)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    "Seleziona",
                    onClick = {
                        vm.toggleSelect(f.id)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    icon = IrideIcons.Check,
                )
                val alreadySaved = f.id in vm.saved
                PrimaryButton(
                    when {
                        alreadySaved -> "Già sul telefono"
                        f.transient -> "Scarica anteprima"
                        else -> "Scarica originale"
                    },
                    onClick = {
                        vm.downloadOne(f.id)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.4f),
                    icon = if (alreadySaved) IrideIcons.Check else IrideIcons.Download,
                    enabled = !vm.downloading && !alreadySaved,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (f.transient) "Questo file è arrivato dalla fotocamera al momento dello scatto ed è salvato in Download/Iride." else "L'anteprima è la miniatura salvata nel file. Il download porta l'originale completo.",
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
            )
        }
    }
}
