@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.goletto.eoslink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.goletto.eoslink.MainViewModel
import it.goletto.eoslink.ptp.CamFile
import java.util.Locale

private val FOLDERS = listOf("" to "Nessuna", "Light" to "Light", "Dark" to "Dark", "Flat" to "Flat", "Bias" to "Bias")

@Composable
fun GalleryTab(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = vm::refreshFiles) { Text("Aggiorna") }
            OutlinedButton(onClick = vm::selectRaw) { Text("Tutti RAW") }
            OutlinedButton(onClick = vm::selectJpg) { Text("Tutti JPG") }
            OutlinedButton(onClick = vm::clearSelection) { Text("Nessuno") }
        }
        Text("Sottocartella di destinazione", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FOLDERS.forEach { (value, label) ->
                FilterChip(
                    selected = vm.subfolder == value,
                    onClick = { vm.subfolder = value },
                    label = { Text(label) },
                )
            }
        }
        Button(
            onClick = vm::downloadSelected,
            enabled = vm.selected.isNotEmpty() && !vm.downloading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scarica selezionati (${vm.selected.size})")
        }
        if (vm.galleryStatus.isNotEmpty()) {
            Text(vm.galleryStatus, style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(vm.files, key = { it.handle }) { f ->
                FileCard(vm, f)
            }
        }
    }
}

@Composable
private fun FileCard(vm: MainViewModel, f: CamFile) {
    val isSelected = f.handle in vm.selected
    LaunchedEffect(f.handle) { vm.loadThumb(f.handle) }
    Card(
        onClick = { vm.toggleSelect(f.handle) },
        border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = vm.thumbs[f.handle]
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = f.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("…", color = Color.LightGray)
                }
                if (isSelected) {
                    Checkbox(checked = true, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
            Column(Modifier.padding(6.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind = when {
                    f.isRaw -> "RAW"
                    f.isJpeg -> "JPG"
                    else -> "File"
                }
                val mb = String.format(Locale.ITALY, "%.1f", f.size / 1048576.0)
                Text("$kind · $mb MB", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
