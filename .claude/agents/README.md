# Team di 5 agenti per sviluppare app

| Agente | Ruolo | Modello |
|---|---|---|
| `android-architect` | Analizza il repo e scrive il piano | opus |
| `android-ui` | Schermate Jetpack Compose / Material 3 | sonnet |
| `backend-api` | Room, rete, server, protocolli dispositivi | sonnet |
| `android-tester` | Test, review, bug | sonnet |
| `release-manager` | Build firmate, CI, GitHub Release, Play Store | sonnet |

## Installazione
Copia i 5 file `.md` (non questo README) in una di queste cartelle:
- `<tuo-repo>/.claude/agents/` → agenti solo per quel progetto (es. Iride, Inquadra, Ubicora)
- `~/.claude/agents/` → agenti disponibili in tutti i progetti

Poi in Claude Code esegui `/agents` per verificare che siano caricati.

## Flusso consigliato
1. "Usa android-architect per pianificare [funzionalità]"
2. "Usa android-ui e backend-api per implementare il piano"
3. "Usa android-tester per testare e revisionare"
4. "Usa release-manager per preparare la versione X.Y.Z"
