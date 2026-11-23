# Informazioni VOD da TMDB

Lume TV recupera trama, generi, anno, cast e immagini per i film e le serie delle liste personali. Il servizio usa un token del progetto: gli utenti non devono creare un account TMDB o inserire una chiave.

## Configurazione della build

Registra l’applicazione nelle impostazioni API dell’account TMDB e genera un **API Read Access Token**. È il token Bearer, distinto dalla chiave API v3.

Per lo sviluppo aggiungi al file locale `local.properties`, insieme a `sdk.dir`:

```properties
tmdb.readAccessToken=TOKEN_DI_LETTURA_DEL_PROGETTO
```

In alternativa fornisci la variabile d’ambiente `TMDB_READ_ACCESS_TOKEN` o la proprietà Gradle `tmdbReadAccessToken`. La precedenza è ambiente, proprietà Gradle, file locale. Non aggiungere il valore reale ai sorgenti, ai comandi documentati o ai log. `local.properties` e i file generati della build sono esclusi dagli archivi sorgente.

Per le release GitHub configura il repository secret **`TMDB_READ_ACCESS_TOKEN`**: il workflow lo passa al solo passo di compilazione dell’APK. Un’app compilata senza token continua a funzionare con i dati locali; nelle Opzioni indica che le informazioni online non sono disponibili in quella versione.

Il token incluso nell’APK è estraibile. Un eventuale proxy del progetto consentirebbe gestione centralizzata e rotazione senza distribuire una nuova APK, richiedendo un servizio ospitato e mantenuto.

## Funzionamento

- La ricerca online parte aprendo la scheda di un film o di una serie. Importazione, ricerca nel catalogo e semplice scorrimento delle copertine non avviano ricerche TMDB. Le anteprime possono riusare dati già scaricati.
- Vengono inviati titolo, tipo e anno quando affidabile. URL delle playlist e dei video, header, cookie e credenziali personali non entrano nel client TMDB.
- Si richiedono i testi in italiano; per una trama mancante si prova l’inglese, segnalato nella scheda. I campi espliciti della playlist hanno precedenza. Le locandine TMDB possono sostituire il ripiego generico `tvg-logo`.
- Un’associazione automatica richiede un titolo esatto normalizzato e un risultato non ambiguo, valutando l’anno. **Associa il titolo** e **Correggi abbinamento** permettono ricerca manuale, selezione per titolo/anno e **Non associare questo titolo**.
- `tmdb-id` / `tmdb_id` e `imdb-id` / `imdb_id` dichiarano identificativi esterni. `tmdb-type="movie"` o `"tv"` può specificare il tipo dei video generici; `series-year` indica l’anno iniziale della serie. `series-id` e `tvg-id` mantengono il significato della playlist.
- La prima versione arricchisce film e serie. Gli episodi riconoscibili possono riusare la scheda della serie; trama e crediti specifici dell’episodio non vengono ancora richiesti.
- Durata editoriale e metadati TMDB vengono usati nella presentazione. Ripresa, durata del flusso, preferiti e disponibilità degli episodi usano i dati locali del player e della playlist.

## Trailer nel player Lume TV

Le schede di film e serie possono mostrare **Trailer in italiano**, oppure **Trailer in inglese** quando manca un candidato italiano. A parità di lingua si preferiscono i trailer ufficiali; clip, teaser e video con identificativi non validi sono esclusi. I riferimenti `site`/`key` sono richiesti insieme ai dettagli tramite `videos`, con le lingue `it,en`, e conservati nella cache. Le schede delle versioni precedenti vengono aggiornate alla prima apertura, mantenendo le associazioni manuali. Nessun pulsante viene mostrato se non ci sono candidati supportati.

La versione attuale risolve i video YouTube con **NewPipe Extractor 0.27.1** e li riproduce con Media3 e i controlli VOD nativi. La risoluzione parte soltanto premendo il pulsante. Il trasporto è separato da TMDB e dalle playlist: token, cookie e header personali non vengono condivisi. La selezione video usa MP4/AVC fino a 1080p e, quando disponibili, audio e video separati con manifest DASH generati in memoria. Il trasporto applica richieste POST, intervalli di byte e header del client corrispondente; i collegamenti temporanei restano nella sessione, senza finire nel database, nei backup o nei log.

Se un video non è disponibile si provano gli altri candidati italiani prima di passare a quelli inglesi, mostrando nel player la lingua effettiva. Sono consentiti fino a tre tentativi per lingua, con 20 secondi per risoluzione, 60 secondi complessivi dedicati alle risoluzioni e un massimo di 20 secondi consecutivi di buffering durante la riproduzione. Gli errori mostrano **Riprova** dentro Lume TV e non aprono app esterne. Vimeo può essere conservato nei metadati ma non ha ancora un resolver e non viene proposto come riproducibile.

Il trailer ha una sessione audio/video separata. La riproduzione precedente viene messa in pausa; riprese, durata e stato visto del film o dell’episodio non cambiano. Fine trailer e Indietro riportano alla scheda; sulla TV torna il focus al pulsante. In background o alla chiusura vengono annullate le richieste e rilasciati player e sessione multimediale.

L’estrazione YouTube è un’integrazione non ufficiale, può smettere di funzionare quando il servizio cambia e richiedere l’aggiornamento della libreria. La licenza GPL del codice non concede diritti sui trailer e non sostituisce le condizioni dei servizi e dei titolari dei contenuti.

## Cache e impostazioni

**Opzioni → Informazioni sui video** permette di disattivare l’arricchimento e svuotare schede e immagini online. La cancellazione conserva le associazioni scelte manualmente.

Le associazioni e le schede sono archiviate in `video-metadata.sqlite`, separato dagli indici ricostruibili delle liste. I metadati validi si riusano offline e dopo un aggiornamento della playlist. Le scadenze iniziali sono 30 giorni per film e serie concluse, 7 giorni per serie in produzione e 24 ore per ricerche senza corrispondenza. Gli errori temporanei mantengono i dati salvati e non vengono registrati come titoli inesistenti.

La cache conserva fino a 5.000 schede e 5.000 associazioni automatiche, oltre a un massimo di 5.000 scelte manuali; il database è limitato a 64 MiB. Le immagini condividono i limiti della cache VOD già esistente. Eliminare una sorgente rimuove le sue associazioni e i metadati non più referenziati. Il backup personale comprende le scelte manuali e resta compatibile con gli archivi precedenti; token e schede scaricate non vengono esportati.

Il client limita concorrenza e frequenza delle richieste sul singolo dispositivo, rispetta le pause HTTP 429 e annulla i trasferimenti quando non restano osservatori. Le soglie TMDB sono variabili: la documentazione le colloca nell’ordine di 40 richieste al secondo e non specifica una quota giornaliera o mensile.

## Verifiche

I test JVM coprono normalizzazione, omonimi, traduzioni, parsing di film e serie, cache persistente, aggiornamenti delle liste, esclusioni manuali, backup/rollback, errori API e annullamento. I test di rete usano risposte sintetiche tramite MockWebServer.

Il collaudo nativo usa due emulatori temporanei e un servizio metadati sintetico, senza token o chiamate TMDB reali:

```sh
python3 tools/smoke_video_library.py --adb /percorso/adb --phone emulator-5584 --tv emulator-5586 --video /percorso/video.mp4 --operation playlist-video-metadata --output analysis/tmdb/native
```

Ripetere con `--font 1.3` verifica il testo ingrandito. Il test controlla scheda, cast, selezione manuale, riapertura senza ulteriori richieste, disattivazione, focus TV e orientamento dello smartphone. La verifica su un campione reale richiede un token del progetto.

### Verifica reale del servizio

Dopo aver configurato il token, il controllo reale del client usato dall’APK si avvia esplicitamente con:

```sh
./gradlew :app:testDebugUnitTest --tests '*TmdbLiveTest' -PliveSources=true
```

Verifica ricerca di film e serie, trame italiane, cast, regia o ideazione e download delle locandine. Il report `app/build/reports/tmdb-live.json` contiene soltanto metadati pubblici e conteggi. Senza il flag il test viene saltato e non contatta TMDB.

Il controllo facoltativo del resolver trailer usa il client dell’app su un film e una serie e legge brevi segmenti del video e dell’audio, senza salvare i collegamenti temporanei:

```sh
./gradlew :app:testDebugUnitTest --tests '*TrailerLiveTest' -PliveSources=true
```

Il report è `app/build/reports/trailer-live.json`. Per il collaudo nativo, `tools/smoke_video_library.py --operation playlist-video-trailer` usa due emulatori temporanei, metadati sintetici e media locali, senza chiamate TMDB/YouTube reali. `--audio /percorso/audio.m4a` verifica l’unione di tracce separate; `--live-trailer` aggiunge esplicitamente riproduzione e ricerca di posizione su un trailer YouTube pubblico. I parametri `--apk` e `--test-apk` permettono di usare output di build isolati.

## Attribuzione

Il logo originale è conservato in [brand/tmdb.svg](brand/tmdb.svg), scaricato dal [file ufficiale TMDB](https://www.themoviedb.org/assets/2/v4/logos/v2/blue_square_2-d537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b.svg). L’app usa la variante bianca consentita nelle linee guida, mantenendo geometria e proporzioni. Logo e avviso sono accessibili anche da **Licenze e avvisi → TMDB · Dati dei video**.

This product uses the TMDB API but is not endorsed or certified by TMDB.

L’API è gratuita per uso non commerciale con attribuzione; per uso commerciale occorre concordare una licenza. Riferimenti: [autenticazione](https://developer.themoviedb.org/docs/authentication-application), [lingue](https://developer.themoviedb.org/docs/languages), [limiti](https://developer.themoviedb.org/docs/rate-limiting), [condizioni e attribuzione](https://developer.themoviedb.org/docs/faq).
