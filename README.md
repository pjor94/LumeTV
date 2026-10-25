<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/logo-light.png">
    <img src="docs/brand/logo-dark.png" width="320" alt="Lume TV">
  </picture>
</p>

<h1 align="center">La TV, fatta semplice.</h1>
<p align="center">Le dirette italiane su Android TV, con un’interfaccia essenziale e un solo telecomando.</p>
<p align="center">
  <a href="https://github.com/pjor94/LumeTV/releases">Scarica l’APK</a> ·
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

## Installazione

Richiede **Android TV o Google TV con Android 6.0 o successivo**. L’app è stata provata anche su Sony Bravia KD-55X81J con Android 12; codec e DRM possono comportarsi diversamente su altri dispositivi.

1. Scarica l’APK dalla pagina [Releases](https://github.com/pjor94/LumeTV/releases).
2. Copiala sulla TV e aprila con un gestore file.
3. Se Android lo richiede, autorizza l’installazione per il gestore file utilizzato.

Puoi installarla anche con ADB, dopo aver collegato e autorizzato la TV:

```sh
adb install -r LumeTV-0.2.1.apk
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

La guida usa il servizio [EPG-Guide](https://epg-guide.com/) e si aggiorna ogni sei ore. Non tutti i canali hanno un palinsesto disponibile. In caso di errore o di pausa richiesta dal servizio, Lume conserva i programmi già scaricati e riprova in seguito.

Il pulsante ↻ permette di richiedere un aggiornamento. Le modifiche alle API delle emittenti possono invece richiedere una nuova versione dell’app.

## Compilazione

Servono JDK 17 e Android SDK 36. Configura l’SDK in Android Studio oppure nel file locale `local.properties`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

L’APK di sviluppo si trova in `app/build/outputs/apk/debug/`. Per contribuire, eseguire i test di rete o rigenerare le risorse grafiche, consulta [CONTRIBUTING.md](CONTRIBUTING.md).

Le release vengono compilate e firmate da GitHub Actions al push di un tag di versione. La configurazione della firma e la procedura di pubblicazione sono descritte in [docs/RELEASE.md](docs/RELEASE.md).

## Privacy

Lume TV non richiede un account e non contiene telemetria. Catalogo, guida e preferiti vengono conservati sul dispositivo; le sessioni anonime Mediaset restano in memoria. Per funzionare, l’app contatta i servizi di WLTV, EPG-Guide e delle emittenti.

## Licenza

Il codice e le risorse originali di Lume TV sono distribuiti sotto [GNU GPL versione 3 o successiva](LICENSE). Le integrazioni derivate da WorldLiveTV mantengono la stessa licenza. Nomi, loghi e trasmissioni delle emittenti appartengono ai rispettivi titolari.
