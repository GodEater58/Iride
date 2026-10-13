package it.goletto.eoslink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import it.goletto.eoslink.LogLine
import it.goletto.eoslink.MainViewModel
import it.goletto.eoslink.ui.theme.LocalEos
import it.goletto.eoslink.ui.theme.readout

@Composable
fun LogScreen(vm: MainViewModel) {
    val p = LocalEos.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Registro", style = MaterialTheme.typography.titleLarge, color = p.text)
                Text(
                    "${vm.logs.size} eventi, ${vm.logs.count { it.error }} con problemi",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
            }
            GhostButton(
                if (copied) "Copiato" else "Copia",
                onClick = {
                    clipboard.setText(AnnotatedString(vm.logText()))
                    copied = true
                },
                icon = if (copied) EosIcons.Check else EosIcons.Copy,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(p.raised),
        ) {
            if (vm.logs.isEmpty()) {
                Text(
                    "Gli eventi della connessione compaiono qui.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.muted,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        reverseLayout = true,
                    ) {
                        items(vm.logs.asReversed()) { LogRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun LogRow(line: LogLine) {
    val p = LocalEos.current
    Row {
        Text(line.time, style = readout(14, androidx.compose.ui.text.font.FontWeight.Medium), color = p.muted)
        Spacer(Modifier.width(10.dp))
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (line.error) p.exposure else p.text,
        )
    }
}
