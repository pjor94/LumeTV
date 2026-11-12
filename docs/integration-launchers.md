# Integrazione locale con i launcher TV

Disponibile da Lume TV 0.8.0. Lume supporta due interfacce pubbliche: gli Intent per aprire schermate o canali e la pubblicazione standard di una riga preview Android TV. Il provider JSON AURA rimane riservato ad AURA, con il consenso e le verifiche di identità già documentate in [integration-aura.md](integration-aura.md).

## Collegamenti per tutti i client compatibili

Un launcher può avviare questi collegamenti con `ACTION_VIEW` e package esplicito `it.lumetv.app`:

| URI | Azione |
| --- | --- |
| `lumetv://channel/<id>` | Apre il canale e avvia la riproduzione in Lume |
| `lumetv://last` | Apre l’ultimo canale TV del catalogo integrato effettivamente riprodotto |
| `lumetv://catalog` | Apre il catalogo senza autoplay |
| `lumetv://favorites` | Apre i preferiti senza autoplay |
| `lumetv://guide` | Apre la guida senza autoplay |
| `lumetv://discover` | Apre Cosa guardare senza autoplay |
| `lumetv://home` | Apre le impostazioni Canali nella Home senza autoplay |

`<id>` è esattamente una stringa di 24 caratteri esadecimali minuscoli. Query, frammenti, credenziali, porte e segmenti aggiuntivi sono rifiutati. Il chiamante non passa URL, header, resolver o DRM. Un ID rimosso apre il catalogo con un messaggio. L’apertura dell’Activity non certifica l’avvio dello stream; richieste a freddo, a caldo e consecutive condividono la coda già usata da AURA.

```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lumetv://channel/$channelId"))
    .setPackage("it.lumetv.app")
startActivity(intent)
```

## Contratto Android TV Home · versione 1

Richiede Android TV API 26 o successivo e la presenza del provider `android.media.tv`. Il resto dell’app conserva il requisito Android 6/API 23. Il consenso iniziale è disattivato: il broadcast `INITIALIZE_PROGRAMS` non pubblica nulla prima della scelta dell’utente.

**Opzioni → Canali nella Home** permette di pubblicare una riga `TYPE_PREVIEW`, nome `Lume TV`, identificatore interno `lume.home.v1`. Il selettore offre preferiti del catalogo integrato oppure selezione manuale, con massimo 24 tessere ordinate per numero e nome. Le radio e tutte le sorgenti M3U personali sono escluse. La selezione non modifica i preferiti dell’app.

Ogni `PreviewProgram` usa:

- `TYPE_CHANNEL`, ID interno uguale all’ID del canale Lume, flag live e peso per l’ordinamento;
- nome del canale, descrizione con il programma corrente oppure `TV in diretta`;
- Intent esplicito verso l’entry point di Lume, con URI `lumetv://channel/<id>`;
- poster locale 16:9, con titolo e identità Lume; il logo originale viene riutilizzato se già presente nella cache locale.

La riga apre `lumetv://catalog` quando viene selezionato il suo logo. Il contratto non include indirizzi degli stream, chiavi, header, token, EPG ID, configurazione DRM o dati delle liste personali. Non vengono pubblicati video di anteprima, elementi Watch Next o ingressi TIF. Un client che legge TvProvider può individuare le righe Lume dal package proprietario e usare gli Intent pubblicati: non deve ricostruire URL di riproduzione o identificatori dal nome.

## Aggiornamenti e scelte nella Home

Catalogo, guida e preferiti richiedono una riconciliazione attraverso JobScheduler. Un lavoro successivo è previsto alla prossima transizione di programma, con almeno un minuto di intervallo e comunque entro la successiva finestra di sei ore. I lavori sono persistenti e si riallineano all’avvio, dopo aggiornamento dell’app e cambio dell’orologio; Android può ritardarne l’esecuzione. I lavori leggono soltanto cache e non avviano rete, resolver o player.

Gli aggiornamenti riusano gli ID delle righe e delle tessere e scrivono soltanto i metadati cambiati. Duplicati appartenenti alla nostra riga vengono rimossi; righe di altre app o di altri tipi non vengono toccate. Una tessera disabilitata o eliminata dalla Home rimane esclusa durante le sincronizzazioni successive. Una riga eliminata non viene ricreata automaticamente. Il semplice occultamento della riga non forza una nuova richiesta di visibilità.

`Mostra nella Home` è una richiesta esplicita per ripresentare la riga. `Ripristina tessere rimosse` cancella le esclusioni; salvare una selezione manuale ripristina le tessere selezionate esplicitamente. La revoca disabilita prima il consenso, quindi elimina le righe preview e le immagini; un lavoro già in coda legge il consenso aggiornato. Se il provider rifiuta la rimozione, le Opzioni mostrano una rimozione da completare e consentono di riprovare.

## Immagini condivise

L’autorità `it.lumetv.app.home.artwork` permette la sola lettura dei poster PNG generati e inclusi nella pubblicazione attuale. I nomi sono vincolati a ID e hash, senza query, frammenti o percorsi arbitrari. Alla revoca, anche i vecchi URI non sono più leggibili. Il provider non espone la cartella privata dei loghi, file M3U o altri archivi dell’app.

## Compatibilità e limiti

La pubblicazione in TvProvider e la visibilità nella Home sono condizioni diverse. Android TV e i launcher alternativi possono offrire controlli diversi per approvare, mostrare e ordinare le righe. Un launcher che espone soltanto icone può aprire Lume, ma deve implementare la lettura delle righe per visualizzare il catalogo selezionato.

La funzione non abilita automaticamente la scheda Live, la ricerca vocale o le raccomandazioni generali di Google TV. Engage SDK e gli eventuali programmi Google richiedono una valutazione distinta. Nessun servizio cloud, server locale, permesso di accessibilità o accesso generale ai package è richiesto da questa implementazione.

Riferimenti: [Home channels Android TV](https://developer.android.com/training/tv/discovery/recommendations-channel), [Engage SDK TV](https://developer.android.com/guide/playcore/engage/tv/getting-started).

## Collaudo locale dell’11 novembre 2026

Su emulatore Android TV API 34 sono verificati consenso, selezione manuale, preferiti aggiornati da un’azione nel catalogo attraverso JobScheduler, ID persistenti, programma scaduto, rimozioni e revoca. Projectivy 4.73 mostra la riga con poster e apre il canale scelto in Lume, con primo frame confermato su video sintetico locale. Su emulatore telefono API 35 il collegamento alle impostazioni spiega l’indisponibilità senza pubblicare canali; i test JVM includono il fallback API 25. Il collaudo non usa TV fisiche e non certifica altri launcher o la Home For You di Google TV. Le istruzioni riproducibili sono in [CONTRIBUTING.md](../CONTRIBUTING.md#collaudare-i-launcher-su-emulatori).
