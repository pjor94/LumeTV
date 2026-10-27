# Pubblicare una release Android

Il workflow [Release](../.github/workflows/release.yml) compila e firma l’APK, raccoglie i sorgenti corrispondenti e pubblica gli allegati nelle [release di GitHub](https://github.com/pjor94/LumeTV/releases).

## Configurazione iniziale della firma

In **Settings → Secrets and variables → Actions → Secrets** del repository configura:

| Secret | Contenuto |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore di release codificato in Base64, completo e senza intestazioni |
| `ANDROID_KEYSTORE_PASSWORD` | Password del keystore |
| `ANDROID_KEY_ALIAS` | Alias della chiave di firma |
| `ANDROID_KEY_PASSWORD` | Password della chiave |

Usa sempre la stessa chiave per consentire gli aggiornamenti delle APK già installate. Conserva una copia sicura del keystore e delle password fuori dal repository. La pipeline rifiuta il certificato Android Debug e si ferma prima della compilazione se manca un Secret.

Se non hai ancora una chiave di release, puoi crearla localmente con il `keytool` del JDK:

```sh
mkdir -p .signing
keytool -genkeypair -keystore .signing/lumetv-release.jks -storetype JKS \
  -alias lumetv -keyalg RSA -keysize 4096 -validity 10000 \
  -dname 'CN=Lume TV Release,O=Lume TV'
```

Il comando richiede le password in modo interattivo. Il certificato pubblico identifica soltanto il progetto con `CN=Lume TV Release, O=Lume TV`, senza nomi personali. `.signing/` e i keystore sono esclusi da Git. Per caricare il keystore senza stamparne il contenuto, con GitHub CLI autenticata:

```sh
base64 < .signing/lumetv-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --repo pjor94/LumeTV
gh secret set ANDROID_KEYSTORE_PASSWORD --repo pjor94/LumeTV
gh secret set ANDROID_KEY_ALIAS --repo pjor94/LumeTV
gh secret set ANDROID_KEY_PASSWORD --repo pjor94/LumeTV
```

Gli ultimi tre comandi chiedono il valore da salvare. Il token `GITHUB_TOKEN` usato per la pubblicazione è fornito automaticamente da GitHub Actions; non serve un PAT aggiuntivo.

Per abilitare il collegamento con AURA nelle release, imposta anche la variabile repository **Variables → `AURA_TRUSTED_CERTIFICATE_SHA256`** con il digest SHA-256 del certificato AURA, in esadecimale minuscolo senza separatori. Sono ammessi più digest separati da virgola. Non è una chiave privata. Senza questa variabile, la release mantiene il provider chiuso ai client, come descritto nel [contratto AURA](integration-aura.md).

## Preparare e avviare una versione

1. Imposta `versionName` in `app/build.gradle.kts` e incrementa `versionCode` per ogni nuova versione da installare come aggiornamento.
2. Aggiungi in `CHANGELOG.md` una sezione `## <versionName>` con le novità da includere nella release. Per la versione attuale, la sezione `0.3.0-dev` è già presente.
3. Esegui le verifiche indicate in [CONTRIBUTING.md](../CONTRIBUTING.md). Aggiungi a Git tutti i nuovi sorgenti e documenti, poi committa e pubblica le modifiche.
4. Crea e pubblica un tag esattamente uguale a `v<versionName>`. Con la versione attuale:

```sh
git tag -a v0.3.0-dev -m 'Lume TV 0.3.0-dev'
git push origin v0.3.0-dev
```

Il push avvia la pipeline. In alternativa, dopo che il workflow è presente sul ramo predefinito, apri **Actions → Release → Run workflow** e inserisci il nome di un tag esistente. Il checkout usa quel tag; non viene pubblicato lo stato corrente di un altro ramo. Per pubblicare le modifiche nuove, il tag deve includerle.

Tag, `versionName`, commit e changelog devono corrispondere. Le versioni `0.x` e quelle con un suffisso come `-dev` sono pubblicate come **pre-release**, scaricabili dalla pagina Releases; le versioni stabili da `1.0.0` vengono indicate come Latest.

## Verifiche e allegati

Prima della pubblicazione la pipeline:

1. Controlla tag, versione, changelog, checkout pulito e presenza dei Secrets.
2. Esegue i controlli del catalogo, i test Python/JVM e Android Lint.
3. Compila l’APK ottimizzata non debuggabile e registra le dipendenze effettivamente usate.
4. Verifica licenze, avvisi e checksum delle dipendenze rispetto a `third_party/sources.lock.json`, poi scarica i sorgenti corrispondenti.
5. Firma l’APK con il keystore temporaneo, verifica firma, package e versione e rimuove la copia temporanea della chiave.
6. Verifica che gli avvisi nell’APK coincidano con i sorgenti e prepara il pacchetto completo.

Il job di compilazione ha permessi di sola lettura. Solo il job di pubblicazione riceve `contents: write`; crea prima una bozza, carica tutti gli allegati e infine la rende pubblica:

- `LumeTV-<versione>.apk`: APK firmata, installabile su Android TV e Google TV.
- `LumeTV-<versione>-source.zip`: sorgenti dell’app e delle dipendenze verificate, con licenze e manifest dei checksum.
- `SIGNING-CERTIFICATE.txt`: impronta SHA-256 del certificato pubblico di firma.
- `SHA256SUMS`: checksum dei tre allegati precedenti.

Le note provengono dalla sezione della versione in `CHANGELOG.md` e mantengono il credito WorldLiveTV. Una release già pubblicata non viene sovrascritta: per modificarne l’APK crea una nuova versione. In caso di errore durante il caricamento, il workflow può riprendere una bozza rimasta incompleta. Gli allegati intermedi sono conservati come artefatti per 7 giorni; i report di verifica per 14 giorni. Gli allegati delle release restano disponibili senza quella scadenza.

## CI ordinaria e catalogo

Il workflow [Android](../.github/workflows/android.yml), eseguito su push, pull request e avvio manuale, continua a verificare test, Lint e compilazioni debug/release senza usare le chiavi. Conserva gli archivi sorgente `LumeTV-source-<commit>` e i report `verification-<commit>` per 14 giorni.

Il workflow [Refresh bundled catalogue](../.github/workflows/catalog.yml) aggiorna la sola copia iniziale della playlist e non crea release. Mantieni attive le Issues per ricevere le richieste di rimozione attraverso il modulo dedicato.

## Preparare un archivio completo localmente

Con JDK 17 e Android SDK 36 configurati:

```sh
./gradlew :app:writeReleaseDependencies --no-daemon
python3 tools/dependency_sources.py fetch --report app/build/reports/release-dependencies.json
python3 tools/package_source.py
```

L’archivio viene scritto in `dist/`. Contiene i sorgenti delle dipendenze in `third_party/sources/` e `SOURCE-MANIFEST.sha256` per verificare i file estratti. Revisioni, versioni e checksum sono in `third_party/sources.lock.json`; i dettagli sono in [third_party/README.md](../third_party/README.md).

Per preparare un’esportazione locale prima del commit, aggiungi `--include-untracked`: vengono inclusi i nuovi sorgenti e documenti ammessi, i file ignorati restano esclusi e le cancellazioni del workspace sono rispettate. L’esportazione non contiene cronologia Git, chiavi o APK.

La compilazione locale `:app:assembleRelease` continua a produrre un’APK non firmata. Puoi firmarla con una tua chiave o compilare `:app:assembleDebug` per una build di sviluppo installabile; passando a un certificato diverso, Android può richiedere la disinstallazione della versione precedente. Chi ridistribuisce una propria APK deve fornire i sorgenti corrispondenti, comprese le dipendenze richieste dalle licenze. Non è promessa una riproducibilità bit per bit dell’APK.
