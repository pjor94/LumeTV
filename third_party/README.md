# Sorgenti delle dipendenze

Il progetto pubblica soltanto sorgenti. Il repository contiene codice, risorse, licenze e manifest delle dipendenze; gli archivi completi `LumeTV-<versione>-source.zip`, disponibili tra gli artefatti di GitHub Actions, contengono anche gli archivi upstream in `third_party/sources/`. Non vengono pubblicate APK.

Chi distribuisce una propria APK deve rendere disponibili i sorgenti corrispondenti secondo le licenze applicabili. Il pacchetto completo può essere usato a tale scopo soltanto se corrisponde alla versione effettivamente compilata; un collegamento al ramo principale non identifica necessariamente quei sorgenti.

`sources.lock.json` registra le 59 dipendenze risolte per la compilazione, con versione, SHA-256 del JAR/AAR usato nella compilazione, licenza, URL e SHA-256 dei sorgenti. Alcuni archivi coprono più artefatti. Le dipendenze dei soli test e gli strumenti di compilazione si scaricano tramite Gradle; non sono incorporati nell’APK. Non sono inclusi Android SDK e JDK, da installare separatamente.

- **desugar_jdk_libs 2.1.5**: sorgenti OpenJDK, addon e script Bazel dalla revisione `73170c345e6a762fc6a1f0301bb15218850023ef`, che prepara questa versione. GPLv2 con Classpath Exception; nessuna modifica manuale ai sorgenti. D8/R8 ne seleziona, trasforma e riduce le classi per Android secondo la configurazione del progetto. Per ricostruire la libreria, il repository upstream contiene il target Bazel `//:maven_release_jdk11` e i prerequisiti in `WORKSPACE`/`repos.bzl`; la compilazione dell’app usa il JAR Maven identificato dal checksum.
- **desugar_jdk_libs_configuration 2.1.5**: sorgenti R8 dalla revisione `c331a820ddae7dea41f41a04375b784486d0f705`, che prepara la configurazione 2.1.5, inclusi wrapper/conversioni Java e script. Licenza BSD a tre clausole.
- **OkHttp 4.12.0**: archivio del tag `parent-4.12.0`, che include la Public Suffix List in forma modificabile (`okhttp/src/test/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat`) e il generatore. La lista è MPL 2.0; OkHttp è Apache 2.0. Nessuna modifica manuale alla lista.
- Le altre librerie sono fornite come `sources.jar` delle versioni Maven risolte. Il segnaposto vuoto `listenablefuture` non contiene classi; l’implementazione è nelle sorgenti Guava.

I testi integrali sono in `app/src/main/assets/licenses/`; la GPLv3 dell’app è in `LICENSE`. Gli avvisi originali distribuiti dalle dipendenze sono conservati in `app/src/main/assets/DEPENDENCY_NOTICES.txt`. I singoli sorgenti upstream mantengono i propri copyright e avvisi.

## Preparazione

Con JDK 17 e Android SDK 36 configurati:

```sh
./gradlew :app:writeReleaseDependencies --no-daemon
python3 tools/dependency_sources.py fetch --report app/build/reports/release-dependencies.json
python3 tools/package_source.py
```

Gitiles assegna timestamp nuovi agli archivi R8 a ogni richiesta. La modalità `gitiles-tar-v1` riordina le voci e normalizza timestamp e proprietari in un TAR deterministico, preservando nomi, contenuti, permessi e link. Il checksum nel lock identifica questo TAR, non la compressione temporanea del server. Due download indipendenti sono stati verificati con gli stessi contenuti e lo stesso checksum normalizzato.

Il download usa esclusivamente gli URL HTTPS bloccati nel manifest e verifica ogni checksum. Se cambiano le dipendenze, la procedura si interrompe: prima di aggiornare il lock occorre riesaminare licenze, sorgenti e avvisi. La cache locale `build/dependency-sources/` non va aggiunta a Git. Negli archivi già estratti sono utilizzate le copie in `third_party/sources/`.

Il confezionamento ordinario richiede tutti i file sorgente sotto controllo versione. Per preparare una copia locale prima del commit, `--include-untracked` include anche nuovi sorgenti/documenti nelle directory del progetto ammesse; non include file ignorati, chiavi, build, appunti locali o la cronologia Git. Il workflow Android confeziona lo stesso checkout usato per le verifiche; i tag non attivano una pubblicazione di APK.

## Ricompilazione e installazione

Dopo aver estratto l’archivio sorgente, dalla cartella `LumeTV-<versione>`:

```sh
python3 tools/dependency_sources.py verify
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:writeReleaseDependencies --no-daemon
python3 tools/dependency_sources.py verify --report app/build/reports/release-dependencies.json
```

Il risultato è `app/build/outputs/apk/release/app-release-unsigned.apk`. Puoi firmarlo con una tua chiave tramite gli strumenti Android, oppure compilare `:app:assembleDebug` per un’APK di prova installabile. Non serve la chiave privata del manutentore per modificare, compilare o installare una propria versione; per passare a una firma diversa Android può richiedere di disinstallare prima quella esistente. Nessun server di attivazione del progetto impedisce le versioni modificate. La firma, il percorso di build e gli strumenti possono cambiare i byte dell’APK: non è promessa una riproducibilità bit per bit.

`SOURCE-MANIFEST.sha256` nell’archivio permette di verificare i file estratti con `shasum -a 256 -c SOURCE-MANIFEST.sha256` (oppure `sha256sum -c` su Linux).
