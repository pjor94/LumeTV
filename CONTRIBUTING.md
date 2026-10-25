# Contribuire

Segnalazioni e contributi sono benvenuti. Per un problema di riproduzione, indica il canale, il modello della TV, la versione Android e la versione di Lume TV. Evita di pubblicare URL di sessione o token nei log allegati.

## Ambiente di sviluppo

- JDK 17.
- Android SDK 36 e Build Tools 36.0.0.
- Python 3 per gli strumenti del catalogo e delle release.

Apri il progetto in Android Studio oppure configura `sdk.dir` nel file locale `local.properties`.

## Verifiche

```sh
python3 tools/update_catalog.py --check
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

I test ordinari non contattano le emittenti. I file in `test-fixtures/` sono campioni fissi; il catalogo in `app/src/main/assets/` viene aggiornato separatamente.

Per verificare i resolver con le emittenti:

```sh
./gradlew :app:testDebugUnitTest -PliveSources=true --rerun-tasks
```

Il report viene scritto in `app/build/reports/live-resolvers.json`. Il test legge manifest limitati e non richiede licenze DRM. Eseguilo con moderazione: disponibilità e limiti dei servizi possono variare.

Per provare il parser su un file XMLTV/XZ già scaricato, aggiungi `-PguideFile=/percorso/guida.xz`. Non aggiungere l’intero feed al repository. La build debug include uno strumento di replay nativo; questo componente è escluso dalla release.

## Catalogo e risorse

`python3 tools/update_catalog.py --refresh` aggiorna e valida la copia iniziale della playlist. Le risposte incomplete non sostituiscono quella salvata.

`python3 tools/generate_brand.py` rigenera i vettori Android e gli SVG in `docs/brand/`. Le PNG per la documentazione possono essere esportate con CairoSVG oppure con `sharp` e `node tools/export_brand_png.cjs`.

## Modifiche

Mantieni utilizzabili tutte le schermate con il telecomando. La riproduzione deve restare a tutto schermo e usare un solo player. Per una nuova integrazione, aggiungi test sul formato delle risposte e descrivi la prova eseguita sul dispositivo.

Gli adattamenti da altri progetti devono conservarne licenza e attribuzioni. I contributi a Lume TV vengono distribuiti con la licenza GPL-3.0-or-later del progetto.

Per pubblicare una versione, segui [la procedura di release](docs/RELEASE.md).
