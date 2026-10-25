# Pubblicare soltanto i sorgenti

Lume TV viene pubblicata come codice sorgente. Il repository mantiene codice, risorse, test, licenze, avvisi di modifica e strumenti per la compilazione locale.

## GitHub Actions

Il workflow [Android](../.github/workflows/android.yml) esegue test, Lint e compilazioni debug/release per verificare il progetto. Le APK restano temporanee nel runner e non vengono caricate tra gli artefatti.

Gli unici artefatti conservati sono:

- `LumeTV-source-<commit>`: archivio `LumeTV-<versione>-source.zip` con il progetto e i sorgenti verificati delle dipendenze.
- `verification-<commit>`: report dei test e di Lint.

La conservazione è di 14 giorni. Il workflow Android ha permessi di sola lettura sui contenuti del repository. Il precedente workflow di firma e pubblicazione delle release è stato rimosso: push di tag e avvio manuale non possono attivarlo. Non servono Secrets di firma Android per questa configurazione.

Il workflow [Refresh bundled catalogue](../.github/workflows/catalog.yml) continua ad aggiornare la sola copia iniziale della playlist. Non crea tag, release o APK. Gli aggiornamenti del catalogo ricevuti dall’app compilata localmente restano disponibili.

## Preparare il repository

1. Conserva `LICENSE`, `THIRD_PARTY_NOTICES.md`, la copia degli avvisi nell’app e i testi in `app/src/main/assets/licenses/`.
2. Aggiungi a Git tutti i nuovi file sorgente e documenti. Verifica anche la cancellazione di `.github/workflows/release.yml`, per non reintrodurre il vecchio workflow.
3. Escludi APK, chiavi, password, cache, build e file locali: `.gitignore` esclude già `dist/`, le cartelle di build e le chiavi di firma.
4. Esegui le verifiche indicate in [CONTRIBUTING.md](../CONTRIBUTING.md) e pubblica il commit del repository. Non è necessario creare una release.
5. Mantieni attive le Issues per ricevere le richieste di rimozione attraverso il modulo dedicato. I collegamenti del progetto puntano a `pjor94/LumeTV`; se cambi repository, aggiorna anche quelli nell’app.

Gli strumenti manuali in `tools/release.py` restano nel codice, ma non sono richiamati da alcun workflow. Non usarne le operazioni di firma o pubblicazione per questa distribuzione di soli sorgenti.

## Preparare un archivio completo

Con JDK 17 e Android SDK 36 configurati:

```sh
./gradlew :app:writeReleaseDependencies --no-daemon
python3 tools/dependency_sources.py fetch --report app/build/reports/release-dependencies.json
python3 tools/package_source.py
```

L’archivio viene scritto in `dist/`. Contiene anche i sorgenti delle dipendenze in `third_party/sources/`, oltre a `SOURCE-MANIFEST.sha256` per verificare i file estratti. Revisioni, versioni e checksum delle dipendenze sono in `third_party/sources.lock.json`. La procedura blocca dipendenze diverse, sorgenti mancanti e checksum non corrispondenti; i dettagli sono in [third_party/README.md](../third_party/README.md).

Per preparare un’esportazione locale prima del commit, aggiungi `--include-untracked`: vengono inclusi i nuovi sorgenti e documenti ammessi, i file ignorati restano esclusi e le cancellazioni del workspace sono rispettate. L’esportazione non contiene cronologia Git, chiavi o APK.

## Compilazione locale

Chi desidera provare l’app può seguire [README → Compilazione](../README.md#compilazione). La build di sviluppo viene firmata localmente con il certificato Android Debug; non richiede una chiave del progetto. La compilazione ottimizzata `:app:assembleRelease` produce invece un’APK non firmata, che richiede una propria firma per essere installata.

Chi decidesse autonomamente di ridistribuire una propria APK deve rispettare le licenze e rendere disponibili i sorgenti corrispondenti alla compilazione, comprese le dipendenze richieste. Non è promessa una riproducibilità bit per bit dell’APK.
