<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/logo-light.png">
    <img src="docs/brand/logo-dark.png" width="320" alt="Lume TV">
  </picture>
</p>

<h1 align="center">La TV, fatta semplice.</h1>
<p align="center">Le dirette italiane su Android TV, con un’interfaccia essenziale e un solo telecomando.</p>
<p align="center">
  <a href="https://github.com/pjor94/LumeTV/releases">Tutte le release</a> ·
  <a href="docs/GUIDA-UTENTE.md">Guida utente</a> ·
  <a href="#compilazione">Compila dai sorgenti</a> ·
  <a href="CHANGELOG.md">Novità</a> ·
  <a href="CONTRIBUTING.md">Contribuisci</a>
</p>

> [!TIP]
> ### [Scarica l’APK di Lume TV](https://github.com/pjor94/LumeTV/releases/download/v0.4.0/LumeTV-0.4.0.apk)
>
> APK firmata pronta da installare su **Android TV e Google TV con Android 6.0 o successivo**.
>
> [Apri la release: novità, sorgenti e checksum](https://github.com/pjor94/LumeTV/releases/tag/v0.4.0) · [Istruzioni di installazione](#distribuzione-e-installazione)

![Catalogo di Lume TV](docs/screenshots/catalog.png)

Lume TV è un’app per Android TV e Google TV dedicata alla televisione italiana in diretta. Il catalogo ha una schermata propria; quando scegli un canale, il video occupa tutto lo schermo. L’interfaccia è nativa, scritta in Kotlin, e la riproduzione usa AndroidX Media3.

## Grazie a WorldLiveTV

**Questo progetto esiste grazie al lavoro di [WorldLiveTV](https://github.com/WorldLiveTV/WorldLiveTV.github.io) e di WLTV Group.** Il catalogo delle sorgenti e le integrazioni delle emittenti derivano dal loro add-on per Kodi, `plugin.video.wltvhelper`.

Lume TV ne adatta la logica di risoluzione in Kotlin e propone un’interfaccia dedicata ad Android TV. Il merito della raccolta e della manutenzione delle sorgenti resta al progetto originale e ai suoi collaboratori.

Lume TV è un progetto indipendente, distribuito con licenza **GPL-3.0-or-later**. Non è un’app ufficiale WorldLiveTV né delle emittenti. Versioni di riferimento, file adattati e altre attribuzioni sono raccolti in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Cosa offre

- Canali Rai, Rai/TGR e Mediaset presenti nella lista WLTV, insieme alle altre sorgenti supportate.
- Riproduzione a tutto schermo di flussi HLS e DASH, con supporto Widevine quando richiesto dall’emittente.
- Navigazione con telecomando, selezione numerica e cambio canale con CH+/CH−.
- Elenco canali semitrasparente sulla sinistra della diretta, con loghi, programma in onda e filtro preferiti, senza interrompere la visione.
- Preferiti e apertura dell’ultimo canale guardato.
- Cambio canale con CH+/CH− su tutti i canali o solo sui preferiti, configurabile in **Opzioni**.
- Guida TV a griglia con programmazione delle prossime ore, dettagli e filtro preferiti, conservata sul dispositivo.
- Pannello della diretta con descrizione, orari, avanzamento, minuti rimanenti e programma successivo, quando presenti nella guida.
- Recupero automatico delle interruzioni temporanee, con attesa del ritorno della rete.
- Catalogo aggiornato automaticamente, con una copia locale disponibile al primo avvio.
- Loghi dei canali nel catalogo, nella guida e nel pannello della diretta, scaricati dalle sorgenti originali e conservati sul dispositivo.
- Controllo automatico delle nuove versioni su GitHub e aggiornamento guidato dal telecomando, da **Opzioni → Aggiornamenti**.
- Collegamento locale facoltativo con AURA: preferiti, guida e apertura dei canali dal launcher, revocabile in **Opzioni → Collegamento con AURA**. [Contratto e configurazione delle firme](docs/integration-aura.md).

Le integrazioni attuali comprendono Rai, Mediaset, Sky, La7, WBD/Discovery e URL diretti. La disponibilità dei singoli canali dipende dalle emittenti, dalla rete utilizzata e dagli eventuali eventi in corso.

## La nuova Guida TV

La griglia mostra circa due ore e mezza alla volta, scorrendo fino alla fine del palinsesto disponibile. ↑/↓ cambia canale mantenendo l’orario, ←/→ passa tra i programmi; **Adesso** e **Stasera** raggiungono le rispettive fasce. OK sul programma in onda apre la diretta; sui programmi futuri mostra orari, descrizione quando disponibile e un’azione esplicita per guardare il canale ora.

Nella Guida, CH+/CH− scorre i canali per pagina e i numeri raggiungono il canale senza avviare la riproduzione. OK prolungato gestisce i preferiti. Tornando dalla diretta si ritrovano canale e fascia oraria selezionati. I canali privi di palinsesto restano accessibili; disponibilità e copertura dipendono dalla sorgente.

![Guida programmi](docs/screenshots/guide.png)

<details>
  <summary>Altre schermate: dettagli, informazioni e canali durante la diretta</summary>

**I dettagli del programma** — titolo completo, orari e descrizione, quando fornita dal palinsesto.

![Dettagli di un programma nella Guida TV](docs/screenshots/guide-details.png)

**Le informazioni della diretta** — descrizione del programma attuale, avanzamento e titolo successivo.

![Informazioni del programma durante la diretta](docs/screenshots/live-info.png)

**I canali, senza lasciare la visione** — elenco laterale con filtro preferiti e programma in onda.

![Elenco canali durante la diretta](docs/screenshots/live-channels.png)

</details>

Per iniziare, consulta la **[guida utente illustrata](docs/GUIDA-UTENTE.md)**: installazione, telecomando, preferiti, palinsesto e aggiornamenti.

## Distribuzione e installazione

Le [release su GitHub](https://github.com/pjor94/LumeTV/releases) contengono l’APK firmata `LumeTV-<versione>.apk`, i sorgenti completi della stessa versione, l’impronta del certificato e i checksum SHA-256. GitHub Actions esegue test, Lint e compilazione prima della pubblicazione. Le nuove versioni usano numeri come `0.3.1` e sono pubblicate come normali release.

Richiede **Android TV o Google TV con Android 6.0 o successivo**. L’app è stata provata anche su Sony Bravia KD-55X81J con Android 12; codec e DRM possono comportarsi diversamente su altri dispositivi.

1. Scarica `LumeTV-<versione>.apk` dagli allegati della [release desiderata](https://github.com/pjor94/LumeTV/releases), oppure compila un’APK di sviluppo seguendo [Compilazione](#compilazione).
2. Copiala sulla TV e aprila con un gestore file.
3. Se Android lo richiede, autorizza l’installazione per il gestore file utilizzato.

Puoi installarla anche con ADB, dopo aver collegato e autorizzato la TV:

```sh
adb install -r LumeTV-VERSIONE.apk
```

Sostituisci `VERSIONE` con la versione scaricata. Gli aggiornamenti delle release usano la stessa chiave di firma. Se hai già installato una build debug o firmata con un’altra chiave, Android può richiedere di disinstallarla prima, cancellando preferiti e impostazioni locali.

### Aggiornamenti dall’app

Le versioni che includono l’aggiornatore controllano le release pubbliche di `pjor94/LumeTV` all’avvio e ogni sei ore mentre l’app è in uso. Un pulsante nel catalogo segnala una nuova versione compatibile; **Opzioni → Aggiornamenti** permette anche il controllo manuale. Il controllo non apre finestre durante la diretta. Non viene eseguito a TV spenta o con l’app chiusa.

Scegli **Scarica e aggiorna** per scaricare l’APK, verificarne dimensione, SHA-256, package, versione e certificato di firma e aprire il programma di installazione Android. Se richiesto, consenti a **Lume TV** di installare app, poi conferma l’aggiornamento con il telecomando. La sostituzione dell’app può chiudere Lume: riaprila dal launcher. Preferiti e impostazioni vengono conservati negli aggiornamenti con la stessa firma. Puoi annullare un download o rinviare l’installazione; il controllo e il download non richiedono un account GitHub.

Nella stessa schermata puoi disattivare i controlli automatici. Vengono proposte soltanto release pubblicate con `versionCode` maggiore e compatibili con Android sulla TV. Le vecchie release prive di `update.json` non vengono proposte. Il controllo considera fino a 100 release e verifica i metadati delle 20 più recenti ammesse agli aggiornamenti.

La prima APK contenente l’aggiornatore va installata con la procedura manuale indicata sopra. Le build debug o con firma personalizzata non possono installare gli aggiornamenti ufficiali sopra la propria firma.

## Telecomando

| Tasto | Azione |
| --- | --- |
| Frecce e OK | Sfoglia il catalogo e apri un canale |
| Indietro durante la diretta | Torna al catalogo |
| Indietro dal catalogo | Riprende la diretta, se aperta |
| CH+/CH− | Passa al canale successivo o precedente |
| Numeri | Seleziona il numero del canale |
| OK durante la diretta | Mostra le informazioni e riprende la riproduzione se in pausa |
| ← durante la diretta | Apre l’elenco canali sulla sinistra, mantenendo la diretta in riproduzione |
| ↑/↓ e OK nell’elenco laterale | Scorre i canali e apre quello scelto |
| → da un canale, oppure Indietro | Chiude l’elenco laterale e continua la visione |
| OK prolungato su una scheda | Aggiunge o rimuove un preferito |
| ↻ | Aggiorna catalogo e guida |

I tasti GUIDE e MENU aprono guida e catalogo quando il sistema della TV li rende disponibili all’app.

In **Opzioni → CH+/CH−** puoi scegliere **Solo preferiti**. La scelta resta salvata sul dispositivo; i numeri e la selezione dal catalogo continuano ad accedere a tutti i canali. Se non hai preferiti, l’app ti invita ad aggiungerli con OK prolungato su una scheda.

Durante la diretta, OK o INFO mostra gli orari del programma, l’avanzamento, i minuti rimanenti, la descrizione e il titolo successivo, quando disponibili. Le descrizioni lunghe si leggono con **↑/↓**, mantenendo il video in riproduzione e fino a tre righe visibili. Il pannello resta per 15 secondi quando viene aperto con OK o INFO e contiene una descrizione; ogni scorrimento rinnova il tempo di lettura. Al cambio canale, o senza descrizione, resta per sei secondi. Si aggiorna anche al cambio di programma.

Premi **←** per aprire l’elenco laterale: il focus parte dal canale in visione, contrassegnato da un punto azzurro. **↑/↓** scorre i canali senza cambiare la diretta; **OK** conferma la scelta e chiude l’elenco. I loghi, il programma in onda e la barra di avanzamento aiutano a scegliere. Una seconda pressione di **←** porta ai filtri **Tutti** e **Preferiti** in cima all’elenco; il filtro non modifica la modalità CH+/CH− delle Opzioni. **→** da un canale o **Indietro** richiude il pannello senza interrompere il video. L’apertura è immediata e la navigazione rispetta le animazioni disabilitate nelle impostazioni della TV.

Per le interruzioni recuperabili, Lume prova a riaprire la diretta fino a tre volte, dopo 2, 5 e 10 secondi, richiedendo nuovamente l’indirizzo del flusso. Se manca la connessione, attende che torni. I tentativi si annullano aprendo il catalogo, cambiando canale o mettendo l’app in standby. Gli errori DRM, i formati non supportati e le richieste di pausa del servizio restano gestiti con un messaggio e il pulsante **Riprova**.

## Catalogo e guida

Il catalogo viene aggiornato ogni 24 ore mentre usi l’app. La lista inclusa nell’APK serve per il primo avvio: nomi, numerazioni e nuovi canali delle integrazioni supportate arrivano direttamente dal servizio WLTV.

RSI La1 e RSI La2 sono escluse dal catalogo incluso, dalla cache e dagli aggiornamenti, anche quando presenti nella lista sorgente.

La guida usa il servizio [EPG-Guide](https://epg-guide.com/) e si aggiorna ogni sei ore. Non tutti i canali hanno un palinsesto disponibile. In caso di errore o di pausa richiesta dal servizio, Lume conserva i programmi già scaricati e riprova in seguito.

I loghi del catalogo, della guida e del pannello della diretta vengono scaricati direttamente dagli indirizzi della playlist e della guida, poi conservati sul dispositivo. Le immagini salvate vengono ricontrollate dopo sette giorni e rimangono disponibili quando la sorgente non risponde. Le edizioni TGR possono condividere il marchio Rai 3, mantenendo separati i programmi regionali. Quando manca un logo, il suo spazio resta vuoto e il nome del canale rimane visibile, mantenendo gli allineamenti delle schede. Alcune sorgenti di immagini usano HTTP: le eccezioni sono limitate alle destinazioni previste e il download controlla anche i redirect.

Il pulsante ↻ permette di richiedere un aggiornamento. Le modifiche alle API delle emittenti possono invece richiedere una nuova versione dell’app.

## Compilazione

Servono JDK 17 e Android SDK 36. Configura l’SDK in Android Studio oppure nel file locale `local.properties`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

L’APK di sviluppo si trova in `app/build/outputs/apk/debug/`. Per contribuire, eseguire i test di rete o rigenerare le risorse grafiche, consulta [CONTRIBUTING.md](CONTRIBUTING.md).

La compilazione e l’installazione locali restano disponibili. La configurazione della firma e la pubblicazione automatica dell’APK e dei sorgenti tramite tag sono descritte in [docs/RELEASE.md](docs/RELEASE.md).

## Privacy

Lume TV non richiede un account e non contiene telemetria. Catalogo, guida e preferiti vengono conservati sul dispositivo; le sessioni anonime Mediaset restano in memoria. Per funzionare, l’app contatta i servizi di WLTV, EPG-Guide e delle emittenti. Il controllo aggiornamenti e il download delle APK contattano GitHub; puoi disattivare il controllo automatico nelle Opzioni.

## Sorgenti e diritti

Lume TV è un client indipendente per la riproduzione di dirette televisive. Il catalogo e parte della logica delle integrazioni derivano dal progetto WorldLiveTV, come documentato nelle attribuzioni.

L’app contatta endpoint esposti su Internet dai servizi sorgente e riproduce i flussi direttamente sul dispositivo, senza ospitare o ritrasmettere le trasmissioni attraverso server del progetto. Alcune integrazioni richiedono sessioni anonime, token temporanei o il normale processo DRM previsto dal fornitore. La raggiungibilità tecnica di un endpoint **non costituisce un’autorizzazione** a utilizzarlo o a distribuirne i contenuti tramite un client di terzi. Non garantiamo che tutti gli endpoint siano sempre accessibili o utilizzabili in ogni territorio.

Disponibilità e accesso dipendono dai fornitori, dai diritti territoriali e dalle condizioni applicabili. Gli utenti devono rispettare tali condizioni e utilizzare il software nei limiti dei diritti di cui dispongono. Il progetto non concede diritti su trasmissioni, palinsesti, marchi o loghi delle emittenti e non è affiliato alle emittenti né a WorldLiveTV.

### Garanzie e responsabilità

Il software è fornito «così com’è», senza garanzie, nella misura consentita dalla legge e secondo le sezioni 15–17 della GNU GPL. Nella stessa misura, autori e contributori escludono la responsabilità per danni derivanti dall’uso del software. Questa dichiarazione non esclude responsabilità inderogabili, non autorizza utilizzi illeciti e non impedisce ai titolari dei diritti di farli valere. Non costituisce un esonero assoluto da ogni responsabilità.

### Segnalazioni e richieste di rimozione

Per chiedere la rimozione di un canale o segnalare un problema relativo ai diritti, [apri una issue nel repository](https://github.com/pjor94/LumeTV/issues/new?template=rights.yml). Indica il canale, la sorgente interessata, il motivo della richiesta e, se agisci per un titolare, elementi utili a verificarne la titolarità o la delega. Non pubblicare credenziali, token o dati personali riservati nelle issue pubbliche.

Le segnalazioni saranno esaminate e, quando necessario, il canale o l’integrazione saranno rimossi o disabilitati, adottando misure per impedirne la reintroduzione dagli aggiornamenti. La rimozione può richiedere una nuova versione dell’app e non modifica copie già scaricate. Non è necessario avviare una contestazione formale su GitHub per contattare il progetto.

## Licenza

Il codice e le risorse originali di Lume TV sono distribuiti sotto [GNU GPL versione 3 o successiva](LICENSE). Le integrazioni derivate da WorldLiveTV mantengono la stessa licenza. Crediti, avvisi di modifica e licenze delle dipendenze sono in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e nell’app in **Opzioni → Licenze e avvisi**. Gli archivi sorgente completi comprendono anche le dipendenze descritte in [third_party/README.md](third_party/README.md). Chi ridistribuisce una propria APK deve rispettare gli obblighi di disponibilità dei sorgenti previsti dalle licenze applicabili. Nomi, loghi e trasmissioni delle emittenti appartengono ai rispettivi titolari.
