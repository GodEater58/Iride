@file:OptIn(ExperimentalMaterial3Api::class)

package it.goletto.eoslink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.goletto.eoslink.MainViewModel

@Composable
fun EosLinkApp(vm: MainViewModel) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (vm.connected) MainScreen(vm) else ConnectScreen(vm)
    }
}

@Composable
private fun ConnectScreen(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("EosLink", style = MaterialTheme.typography.headlineMedium)
        Text("Controllo remoto Canon EOS via Wi-Fi", style = MaterialTheme.typography.bodyMedium)

        ModeOption(
            selected = vm.mode == MainViewModel.Mode.HOTSPOT,
            title = "Hotspot del telefono o stessa rete",
            subtitle = "La fotocamera si collega all'hotspot del telefono (o al Wi-Fi di casa). I dati mobili restano sempre attivi.",
        ) { vm.mode = MainViewModel.Mode.HOTSPOT }
        ModeOption(
            selected = vm.mode == MainViewModel.Mode.CAMERA_WIFI,
            title = "Wi-Fi della fotocamera",
            subtitle = "Il telefono si collega alla rete della fotocamera solo per questa app: internet continua sui dati mobili.",
        ) { vm.mode = MainViewModel.Mode.CAMERA_WIFI }

        if (vm.mode == MainViewModel.Mode.CAMERA_WIFI) {
            OutlinedTextField(
                value = vm.ssid,
                onValueChange = { vm.ssid = it },
                label = { Text("Nome rete fotocamera (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.password,
                onValueChange = { vm.password = it },
                label = { Text("Chiave di crittografia") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = vm.host,
            onValueChange = { vm.host = it },
            label = { Text("IP fotocamera (vuoto = ricerca automatica)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = vm::connect, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.busy) "Connessione in corso…" else "Connetti")
        }
        if (vm.busy) {
            OutlinedButton(onClick = vm::cancelConnect, modifier = Modifier.fillMaxWidth()) { Text("Annulla") }
        }
        Text(vm.status, style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sulla fotocamera", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Menu Wi-Fi → Attiva, poi scegli la funzione Smartphone.\n" +
                        "2. Hotspot: collega la fotocamera alla rete dell'hotspot del telefono.\n" +
                        "   Wi-Fi fotocamera: usa la modalità punto di accesso e copia qui nome rete e chiave.\n" +
                        "3. Se la fotocamera chiede di confermare il dispositivo «EosLink…», premi OK.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text("Registro", style = MaterialTheme.typography.titleSmall)
        LogList(vm, Modifier.fillMaxWidth().height(220.dp))
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(vm.model, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::disconnect) { Text("Disconnetti") }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Scatto", "Galleria", "Registro").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> ShootTab(vm)
            1 -> GalleryTab(vm)
            else -> LogList(vm, Modifier.fillMaxSize().padding(8.dp))
        }
    }
}

@Composable
private fun ShootTab(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val frame = vm.liveFrame
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "Live view",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(if (vm.liveOn) "Avvio live view…" else "Live view spento", color = Color.Gray)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm::toggleLive) {
                Text(if (vm.liveOn) "Ferma live view" else "Avvia live view")
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.autofocus, onCheckedChange = { vm.autofocus = it })
                Text("Autofocus")
            }
        }
        Button(
            onClick = vm::shoot,
            enabled = !vm.intervalRunning,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("SCATTA", style = MaterialTheme.typography.titleLarge)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Intervallometro", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Scatti", vm.intCount, Modifier.weight(1f)) { vm.intCount = it }
                    NumField("Pausa (s)", vm.intPause, Modifier.weight(1f)) { vm.intPause = it }
                    NumField("Posa B (s)", vm.intBulb, Modifier.weight(1f)) { vm.intBulb = it }
                }
                Text(
                    "Posa B = 0: usa il tempo impostato sulla fotocamera. Per pose oltre 30 s metti la ghiera su B e indica i secondi qui.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (vm.intervalRunning) {
                    Button(
                        onClick = vm::stopInterval,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Interrompi sequenza") }
                } else {
                    Button(onClick = vm::startInterval, modifier = Modifier.fillMaxWidth()) { Text("Avvia sequenza") }
                }
                if (vm.intervalStatus.isNotEmpty()) Text(vm.intervalStatus)
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun LogList(vm: MainViewModel, modifier: Modifier) {
    val lines = vm.logs.asReversed()
    LazyColumn(modifier) {
        items(lines) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
