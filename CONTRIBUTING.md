# Contribuire

Segnalazioni e contributi sono benvenuti. Per un problema di riproduzione, indica il canale, il modello del dispositivo, l’interfaccia usata (TV o smartphone), la versione Android e la versione di Lume TV. Evita di pubblicare URL di sessione o token nei log allegati.

## Ambiente di sviluppo

- JDK 17.
- Android SDK 36 e Build Tools 36.0.0.
- Python 3 per gli strumenti del catalogo e degli archivi sorgente.

Apri il progetto in Android Studio oppure configura `sdk.dir` nel file locale `local.properties`.

## Verifiche

Per le liste personali usa `tools/smoke_personal_playlists.py --adb /percorso/adb --serial emulator-5570 --mode phone --video /percorso/video.mp4`, e ripeti su un emulatore Android TV con `--mode tv` e il suo seriale esplicito. Il comando rifiuta dispositivi fisici, usa M3U e video sintetici locali, verifica importazione, isolamento del catalogo, zapping dalla UI e dal servizio, aggiornamento senza riavvio, conservazione degli ID, disattivazione, riavvio del processo ed eliminazione. Ripristina i dati precedenti e salva il report in `analysis/personal-playlists/`. Soltanto le build debug ammettono `http://10.0.2.2` per questi campioni; le sorgenti personali di release richiedono HTTPS.

```sh
python3 tools/update_catalog.py --check
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

I test ordinari non contattano le emittenti. I file in `test-fixtures/` sono campioni fissi; il catalogo in `app/src/main/assets/` viene aggiornato separatamente.

`UpdateInstallerTest` usa Robolectric e il parser APK di Android su API 27, 28, 29 e 35 per verificare gli aggiornamenti con la stessa firma e il rifiuto di una firma diversa. I piccoli campioni firmati sono in `test-fixtures/updates/`, insieme al manifest e alle istruzioni per rigenerarli. Alla prima esecuzione Robolectric scarica da Maven i framework Android necessari.

Per verificare i resolver con le emittenti:

```sh
./gradlew :app:testDebugUnitTest -PliveSources=true --rerun-tasks
```

I report vengono scritti in `app/build/reports/live-resolvers.json` (Rai e Mediaset) e `app/build/reports/live-la7-resolvers.json` (La7 e La7 Cinema). I test leggono manifest limitati e non richiedono licenze DRM. Eseguili con moderazione: disponibilità e limiti dei servizi possono variare.

Per controllare soltanto La7 e La7 Cinema:

```sh
./gradlew :app:testDebugUnitTest -PliveSources=true --tests 'it.lumetv.app.data.La7LiveResolverTest' --rerun-tasks
```

Per provare il parser su un file XMLTV/XZ già scaricato, aggiungi `-PguideFile=/percorso/guida.xz`. Non aggiungere l’intero feed al repository. La build debug include uno strumento di replay nativo; questo componente è escluso dalla release.

Per il collaudo della Guida su un emulatore Android TV usa `python3 tools/smoke_guide.py --adb /percorso/adb --serial emulator-5560 --video /percorso/video-sintetico.mp4`, con APK debug già installata. Il test prepara un catalogo e un palinsesto locali, riproduce soltanto il video sintetico e ripristina cache, preferenze e dimensione del testo al termine. Non usarlo su una TV fisica. Report e schermate vengono salvati in `analysis/guide-smoke/`.

Per Cosa guardare usa `python3 tools/smoke_discovery.py --adb /percorso/adb --serial emulator-5560 --video /percorso/video-sintetico.mp4`. Verifica la navigazione Focus, ricerca, preferiti, promemoria, riproduzione sintetica e ripristino con testo ingrandito. Anche questo test è riservato a un emulatore temporaneo e ripristina i dati al termine; i risultati sono in `analysis/discovery-smoke/`.

Per verificare aggiornamento dei programmi nel catalogo, stati accessibili, pressione di OK, animazioni disabilitate e testo ingrandito usa `python3 tools/smoke_ui.py --adb /percorso/adb --serial emulator-5560`. Richiede ImageMagick per confrontare il colore del pulsante durante la pressione. Non avvia dirette e ripristina dati e impostazioni al termine; report e schermate sono in `analysis/ui-smoke/`. Il test della Guida controlla anche la navigazione tra le righe dei comandi e il riutilizzo delle celle durante gli spostamenti.

Per l’uscita dall’app usa `python3 tools/smoke_exit.py --adb /percorso/adb --serial emulator-5560 --video /percorso/video-sintetico.mp4`. Verifica pulsante Esci, Back dalla Home, conferma, annullamento e ritorno dalla diretta sintetica. Usa soltanto un emulatore temporaneo con APK debug già installata; il test ripristina cache e preferenze al termine e salva i risultati in `analysis/exit-smoke/`.

Per l’interfaccia smartphone usa `python3 tools/smoke_mobile.py --adb /percorso/adb --serial emulator-5564 --video /percorso/video-sintetico.mp4`, con APK debug installata su un emulatore telefono temporaneo. Verifica riconoscimento automatico, navigazione touch, ricerca, preferiti, guida, avvisi, pausa, rotazione senza ricreare il player, elenco canali durante la diretta, collegamenti a caldo e scelta manuale dell’interfaccia. Ripristina cache, preferenze, rotazione e testo al termine; report e schermate sono in `analysis/mobile-smoke/`.

Per i popup degli aggiornamenti usa `python3 tools/smoke_updates.py --adb /percorso/adb --serial emulator-5560 --mode tv --video /percorso/video-sintetico.mp4 --output analysis/update-popup-tv`, con APK debug installata su un emulatore temporaneo. Su un emulatore telefono usa `--mode phone` e un percorso di output distinto. Il test usa metadati di aggiornamento fittizi salvati nella cache, verifica rinvio, riavvio, standby, cambio di interfaccia, diretta e finestre sovrapposte, e acquisisce il popup con testo normale e al 130%. Non scarica APK e ripristina dati e impostazioni al termine. I test JVM coprono anche l’intervallo di 30 minuti, l’attesa dopo gli errori e le pause di GitHub.

`InterfacePolicyTest` verifica la precedenza dei dispositivi TV, la selezione smartphone, il comportamento di tablet/box e l’override manuale. Il launcher pubblico resta `MainActivity`; inoltra gli stessi collegamenti a `TvActivity` o `MobileActivity`. Le Activity delle due UI non sono esportate.

## Catalogo e risorse

`python3 tools/update_catalog.py --refresh` aggiorna e valida la copia iniziale della playlist. Le risposte incomplete non sostituiscono quella salvata.

RSI La1 e RSI La2 sono escluse sia dal generatore del catalogo sia dall’app, anche alla lettura delle cache precedenti. Mantieni allineate le regole in `tools/update_catalog.py` e `core/src/main/kotlin/it/lumetv/core/CatalogPolicy.kt`. Il campione originale in `test-fixtures/` conserva le quattro voci per verificare che gli aggiornamenti le filtrino.

`python3 tools/generate_brand.py` rigenera i vettori Android e gli SVG in `docs/brand/`. Le PNG per la documentazione possono essere esportate con CairoSVG oppure con `sharp` e `node tools/export_brand_png.cjs`.

## Modifiche

Mantieni utilizzabili tutte le schermate con il telecomando. La diretta principale resta a tutto schermo; il catalogo e la guida possono mostrarne l’anteprima. Tutte le schermate e i controlli di sistema devono usare l’unico player del servizio Media3. Per una nuova integrazione, aggiungi test sul formato delle risposte e descrivi la prova eseguita sul dispositivo.

Gli adattamenti da altri progetti devono conservarne licenza e attribuzioni. I contributi a Lume TV vengono distribuiti con la licenza GPL-3.0-or-later del progetto.

Le release pubblicano l’APK firmata insieme ai sorgenti corrispondenti. Per configurare la firma e preparare una versione, segui [la procedura di pubblicazione](docs/RELEASE.md). Il workflow Android verifica i contributi senza usare le chiavi; il workflow Release firma e pubblica soltanto tag di versione, dopo il superamento delle verifiche.

## Licenze e archivi sorgente

Mantieni sincronizzati `THIRD_PARTY_NOTICES.md` e la copia in `app/src/main/assets/`; registra una data rilevante negli adattamenti GPL e nel changelog. Le licenze integrali sono in `app/src/main/assets/licenses/` e gli avvisi upstream in `DEPENDENCY_NOTICES.txt` nella stessa cartella. Non sostituire la GPLv2 con Classpath Exception di desugar con la sola GPLv3 dell’app.

Quando modifichi una dipendenza, genera `:app:writeReleaseDependencies` e riesamina licenza, avvisi e sorgenti prima di aggiornare `third_party/sources.lock.json`. Segui [third_party/README.md](third_party/README.md) per raccogliere i sorgenti e verificare il pacchetto completo. La verifica si interrompe se vengono omessi nuovi file non ancora aggiunti a Git.


### Collaudo radio, background e Picture in Picture

`tools/smoke_playback.py` usa due emulatori usa e getta, un video MP4 e un campione MP3 locali. Verifica catalogo radio, ricerca per città, preferiti, recenti, metadati ICY, ascolto a schermo spento oltre 30 secondi, controlli multimediali, PiP, modalità TV solo audio e arresto della riproduzione uscendo dalla UI TV. Ripristina cataloghi e preferenze alla fine e rifiuta dispositivi fisici. Il report e le schermate vengono salvati in `analysis/playback-smoke/`.

```sh
python3 tools/smoke_playback.py --adb /percorso/adb --phone emulator-5554 --tv emulator-5556 --video /percorso/video.mp4 --audio /percorso/audio.mp3
```

Il catalogo radio incluso si rigenera con `python3 tools/update_radio_catalog.py`. Il comando aggiorna anche la provenienza in `catalog/radio-source.json`; la cache scaricata sui dispositivi rimane separata dalla playlist TV. Il catalogo radio non viene esportato nel contratto AURA TV esistente.

`tools/smoke_mobile_options.py` verifica la pagina Opzioni su un emulatore smartphone: ritorno alla ricerca precedente, aggiornamento e persistenza dei valori, consenso AURA, accesso ad aggiornamenti e licenze, ricreazione del processo, testo al 130%, rotazione e mini player radio/TV. Usa gli stessi campioni locali e ripristina i dati dell’emulatore alla fine.

```sh
python3 tools/smoke_mobile_options.py --adb /percorso/adb --serial emulator-5554 --video /percorso/video.mp4 --audio /percorso/audio.mp3
```

`tools/smoke_tv_options.py` verifica la pagina Opzioni su un emulatore Android TV: navigazione con il telecomando e il menu superiore condiviso, ripristino del focus e della selezione, scelte CH+/CH− e AURA, testo al 130%, ricreazione del processo, ritorno alla guida e anteprima TV/radio. Controlla anche che la radio si interrompa uscendo dall’app TV. Ripristina i dati dell’emulatore e rifiuta dispositivi fisici.

```sh
python3 tools/smoke_tv_options.py --adb /percorso/adb --serial emulator-5556 --video /percorso/video.mp4 --audio /percorso/audio.mp3
```

`tools/smoke_tv_channel_switch.py` verifica la sequenza diretta → Indietro → altro canale → OK, l’anteprima del nuovo canale, la riapertura dello stesso canale e l’arresto uscendo dalla UI TV. Usa un video locale, ripristina i dati e accetta soltanto emulatori usa e getta.

```sh
python3 tools/smoke_tv_channel_switch.py --adb /percorso/adb --serial emulator-5554 --video /percorso/video.mp4
```

Per riprodurre anche lo Stop sintetico osservato sulla Bravia, compila il probe con il JDK e gli Android SDK Build Tools del progetto:

```sh
mkdir -p analysis/media-button-probe
javac --release 8 -classpath /percorso/sdk/platforms/android-36/android.jar -d analysis/media-button-probe tools/android/MediaButtonProbe.java
/percorso/sdk/build-tools/36.0.0/d8 --lib /percorso/sdk/platforms/android-36/android.jar --min-api 23 --output analysis/media-button-probe analysis/media-button-probe/MediaButtonProbe.class
python3 tools/smoke_tv_channel_switch.py --adb /percorso/adb --serial emulator-5554 --video /percorso/video.mp4 --media-button-dex analysis/media-button-probe/classes.dex --output analysis/tv-channel-switch-synthetic
```

Il probe viene eseguito come shell sull’emulatore e non entra nell’APK. Il test invia uno Stop senza timestamp e sorgente durante l’apertura lenta del secondo canale; verifica anche che Stop dopo l’apertura e Stop con un timestamp reale continuino a funzionare.

## Collaudare i launcher su emulatori

`tools/smoke_home_channels.py` rifiuta seriali diversi da `emulator-*`. Compila e installa sull’emulatore sia `:app:assembleDebug` sia `:app:assembleDebugAndroidTest`; il runner `HomeInspection` è presente solo nell’APK di test e controlla anche `Build.HARDWARE`. Ispeziona esclusivamente le righe preview appartenenti a Lume attraverso il provider reale, senza privilegi di root o modifiche alle autorizzazioni del dispositivo.

```sh
python3 tools/smoke_home_channels.py --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5572 --video analysis/guide-fixture.mp4
python3 tools/smoke_home_channels.py --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5570 --video analysis/guide-fixture.mp4 --phone
```

Il collaudo usa video e palinsesti sintetici locali, ripristina cache, preferenze e font scale, e verifica consenso, selezione, riavvio, ID persistenti, guida, rimozioni, ripristino e revoca. Il telefono verifica il fallback senza API Home. La verifica visuale di un launcher esterno, come Projectivy, richiede l’APK del relativo autore installata esclusivamente sull’emulatore. Il fatto che TvProvider accetti una pubblicazione non certifica la visibilità in ogni launcher.

Per il controllo manuale di Projectivy su emulatore, aggiungi `--projectivy-only --output analysis/home-launchers/projectivy-validation`: il server locale resta attivo mentre abiliti la riga e apri **TV Test 2** dal launcher. Invio verifica la sessione e ripristina i dati. Non abilitare accessibilità, root o accesso alle notifiche per questo collaudo; è sufficiente il permesso del launcher per leggere gli elenchi TV.
