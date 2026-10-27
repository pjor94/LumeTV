# Collegamento locale con AURA · versione 1

Lume TV 0.3.0-dev offre l’integrazione sullo stesso dispositivo Android, senza server o bridge. In **Opzioni → Collegamento con AURA**, **Consenti collegamento** permette ad AURA di leggere catalogo, preferiti, guida e ultimo canale. **Revoca collegamento** interrompe la condivisione. I dati appartengono alla TV e sono comuni ai profili del launcher. La riproduzione resta in Lume TV.

## Apertura delle schermate

Le URI pubbliche accettate da MainActivity sono:

- `lumetv://channel/<id>`: ID del catalogo, esattamente 24 caratteri esadecimali minuscoli.
- `lumetv://last`: ultimo canale effettivamente riprodotto, oppure catalogo se assente.
- `lumetv://catalog`, `lumetv://favorites`, `lumetv://guide`: schermate senza riproduzione automatica.
- `lumetv://integration`: dialogo di consenso sopra il catalogo.

Usare `ACTION_VIEW` con package esplicito `it.lumetv.app`. Non sono ammessi query string, frammenti, credenziali, porte, URL di stream o altri segmenti. Una richiesta invalida o un ID rimosso apre il catalogo con un messaggio. Gli extras non selezionano stream o resolver.

Le richieste cold/warm usano la stessa coda; la più recente sostituisce la precedente, precede il ripristino dell’ultimo canale e attende catalogo locale e player. Le risoluzioni annullate non possono avviare un vecchio canale. L’apertura dell’Activity non equivale alla conferma della riproduzione.

## Contratto del provider

Autorità `it.lumetv.app.integration`, sola lettura. Ogni query restituisce una riga, colonna `payload`, contenente JSON:

| Endpoint | Dati |
| --- | --- |
| `/v1/capabilities` | `protocolVersion: 1`, `appVersion`, `status`, `capabilities` |
| `/v1/snapshot` | Metadati precedenti più catalogo e guida autorizzati |

`status` è `ready` oppure `permission_required`. Le capabilities sono `open_channel`, `open_last`, `open_catalog`, `open_favorites`, `open_guide`, `integration_settings`.

Lo snapshot contiene `catalogUpdatedAt`, `guideUpdatedAt`, `lastChannelId`, `lastPlayedAt` (nullabili; date epoch ms) e `channels`. Ogni canale espone esclusivamente `id`, `name` (massimo 160 caratteri), `number` intero da 0 a 999999 oppure null, `family`, `logo` HTTPS validato o null, `favorite` booleano e `now`/`next` nullabili con `{title,startMs,endMs}`. I controlli nei testi diventano spazi, gli estremi vengono rimossi e i titoli EPG sono limitati a 300 caratteri. Nomi vuoti escludono il canale; titoli vuoti non producono un programma. Nessun URL di stream, chiave, header, token, configurazione DRM o EPG ID viene esportato. I loghi con credenziali, query o indirizzi locali sono omessi.

Lo snapshot è limitato a 500 canali e 512 KiB UTF-8, dando precedenza all’ultimo canale e ai preferiti. In assenza di consenso, canali vuoti e tutti i timestamp e dati dell’ultimo canale nulli. L’endpoint capabilities non espone dati personali. `lastPlayedAt` si aggiorna soltanto quando il player è realmente in riproduzione; i dati precedenti all’aggiornamento possono avere il canale ma nessun timestamp.

Le query leggono soltanto cache. File e snapshot condividono un lock nel processo; il provider non crea repository di sync né effettua rete. Lume chiama `notifyChange` sugli endpoint dopo consenso, catalogo, guida, preferiti e playback confermato. Un client può osservare l’autorità con `notifyForDescendants=true`. Catalogo e guida mantengono la normale frequenza di aggiornamento durante l’uso di Lume; il provider non forza aggiornamenti.

## Identità del chiamante

Il provider controlla il vero UID Binder, l’unico package associato `it.aura.tv`, l’UID del package e **tutti** i certificati correnti dell’APK. Un certificato storico o un UID condiviso con altri package non basta. Scritture, `call`, file, batch e altre operazioni non previste sono rifiutati.

La proprietà Gradle `auraTrustedCertificateSha256` configura i digest SHA-256 esadecimali minuscoli autorizzati, separati da virgola. Il debug locale preconfigura soltanto il certificato AURA di sviluppo del progetto. La release usa un valore vuoto e rifiuta ogni client finché il distributore non configura esplicitamente il certificato. Non cambiare il keystore di Lume per far coincidere le firme: le due app possono restare firmate separatamente. Anche AURA verifica la firma di Lume.

```sh
./gradlew :app:assembleRelease -PauraTrustedCertificateSha256=<sha256-del-certificato-AURA>
```

## Verifica

`IntegrationContractTest` copre URI rigorose, sostituzione delle richieste, UID condivisi e multi-firma, revoca, campi ammessi, intervalli EPG, normalizzazione Unicode, limiti UTF-8 e loghi. Il 26 ottobre 2026 le verifiche JVM sono terminate con **65 test passati e 2 test live saltati**; i 24 test Python degli strumenti e il controllo del catalogo incluso (155 voci) sono passati. Lint debug: 0 errori e 68 avvisi. Compilazioni debug e release completate; la release senza pin configurato mantiene il provider chiuso ai client.

Il collaudo di coppia AURA/Lume sull’emulatore è terminato con **12 verifiche passate**. I video sono MP4 sintetici serviti localmente: il player ha confermato primo frame e stato READY nelle aperture a freddo/a caldo, ultimo canale e richieste rapide. Verificati anche consenso/revoca, rifiuto degli UID non autorizzati, aggiornamento della guida al cambio programma senza notifica del provider, ricerca e ripristino del focus al ritorno. Versione precedente/assente, ID invalido, catalogo, preferiti e guida hanno rispettato i comportamenti di fallback e assenza di autoplay previsti.

La policy di rete debug ammette `10.0.2.2` per quelle fixture; la release conserva la policy originale. **Questi test non verificano le emittenti reali, codec o DRM della Sony Bravia. La Bravia non è stata aggiornata né usata nel collaudo dell’integrazione.**
