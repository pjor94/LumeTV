<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/logo-light.png">
    <img src="docs/brand/logo-dark.png" width="320" alt="Lume TV">
  </picture>
</p>

<h1 align="center">La TV, fatta semplice.</h1>
<p align="center">Le dirette italiane su Android TV, con un’interfaccia essenziale e un solo telecomando.</p>
<p align="center">
  <a href="#compilazione">Compila dai sorgenti</a> ·
  <a href="CHANGELOG.md">Novità</a> ·
  <a href="CONTRIBUTING.md">Contribuisci</a>
</p>

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
- Preferiti e apertura dell’ultimo canale guardato.
- Guida con programma attuale e successivo, conservata sul dispositivo.
- Catalogo aggiornato automaticamente, con una copia locale disponibile al primo avvio.

Le integrazioni attuali comprendono Rai, Mediaset, Sky, La7, WBD/Discovery e URL diretti. La disponibilità dei singoli canali dipende dalle emittenti, dalla rete utilizzata e dagli eventuali eventi in corso.

<details>
  <summary>Guida TV</summary>

![Guida programmi](docs/screenshots/guide.png)

</details>

## Distribuzione e installazione

Il progetto viene pubblicato **solo come codice sorgente**. GitHub Actions esegue test, Lint e compilazione, conservando soltanto gli archivi sorgente e i report di verifica. Le APK si compilano localmente; non sono pubblicate dal progetto e non vengono caricate come artefatti CI.

Richiede **Android TV o Google TV con Android 6.0 o successivo**. L’app è stata provata anche su Sony Bravia KD-55X81J con Android 12; codec e DRM possono comportarsi diversamente su altri dispositivi.

1. Compila un’APK di sviluppo seguendo [Compilazione](#compilazione).
2. Copiala sulla TV e aprila con un gestore file.
3. Se Android lo richiede, autorizza l’installazione per il gestore file utilizzato.

Puoi installarla anche con ADB, dopo aver collegato e autorizzato la TV:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Telecomando

| Tasto | Azione |
| --- | --- |
| Frecce e OK | Sfoglia il catalogo e apri un canale |
| Indietro durante la diretta | Torna al catalogo |
| Indietro dal catalogo | Riprende la diretta, se aperta |
| CH+/CH− | Passa al canale successivo o precedente |
| Numeri | Seleziona il numero del canale |
| OK durante la diretta | Mostra le informazioni e riprende la riproduzione se in pausa |
| OK prolungato su una scheda | Aggiunge o rimuove un preferito |
| ↻ | Aggiorna catalogo e guida |

I tasti GUIDE e MENU aprono guida e catalogo quando il sistema della TV li rende disponibili all’app.

## Catalogo e guida

Il catalogo viene aggiornato ogni 24 ore mentre usi l’app. La lista inclusa nell’APK serve per il primo avvio: nomi, numerazioni e nuovi canali delle integrazioni supportate arrivano direttamente dal servizio WLTV.

RSI La1 e RSI La2 sono escluse dal catalogo incluso, dalla cache e dagli aggiornamenti, anche quando presenti nella lista sorgente.

La guida usa il servizio [EPG-Guide](https://epg-guide.com/) e si aggiorna ogni sei ore. Non tutti i canali hanno un palinsesto disponibile. In caso di errore o di pausa richiesta dal servizio, Lume conserva i programmi già scaricati e riprova in seguito.

Il pulsante ↻ permette di richiedere un aggiornamento. Le modifiche alle API delle emittenti possono invece richiedere una nuova versione dell’app.

## Compilazione

Servono JDK 17 e Android SDK 36. Configura l’SDK in Android Studio oppure nel file locale `local.properties`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

L’APK di sviluppo si trova in `app/build/outputs/apk/debug/`. Per contribuire, eseguire i test di rete o rigenerare le risorse grafiche, consulta [CONTRIBUTING.md](CONTRIBUTING.md).

La compilazione e l’installazione locali restano disponibili. La pubblicazione del repository e degli archivi di soli sorgenti è descritta in [docs/RELEASE.md](docs/RELEASE.md); i tag non avviano workflow di firma o pubblicazione delle APK.

## Privacy

Lume TV non richiede un account e non contiene telemetria. Catalogo, guida e preferiti vengono conservati sul dispositivo; le sessioni anonime Mediaset restano in memoria. Per funzionare, l’app contatta i servizi di WLTV, EPG-Guide e delle emittenti.

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

Il codice e le risorse originali di Lume TV sono distribuiti sotto [GNU GPL versione 3 o successiva](LICENSE). Le integrazioni derivate da WorldLiveTV mantengono la stessa licenza. Crediti, avvisi di modifica e licenze delle dipendenze sono in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e nell’app in **Info → Licenze e avvisi**. Gli archivi sorgente completi comprendono anche le dipendenze descritte in [third_party/README.md](third_party/README.md). Chi ridistribuisce una propria APK deve rispettare gli obblighi di disponibilità dei sorgenti previsti dalle licenze applicabili. Nomi, loghi e trasmissioni delle emittenti appartengono ai rispettivi titolari.
