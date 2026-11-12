# Compatibilità delle liste personali

Questo documento descrive la compatibilità delle liste personali di **Lume TV 0.9.0**. La 0.8.0 conserva i limiti e le funzioni del proprio changelog; l’aggiornamento migra gli archivi precedenti mantenendo ID, preferiti e impostazioni.

## Gestione tramite pagine

La gestione riprende lo stile di **Opzioni**: su smartphone usa la stessa intestazione, le sezioni, le descrizioni e le frecce delle righe; su TV le pagine di scelta mostrano elenco e dettaglio affiancati, con lo stesso focus del telecomando. I moduli restano in una colonna per lasciare spazio all’inserimento.

**Opzioni → Le mie liste → [lista]** apre una pagina dedicata. **Sorgente** contiene nome, attivazione, consenso HTTP e sostituzione; **Lettura e contenuti** raccoglie formato, codifica, gruppi e aggiornamento; **Accesso ai server** contiene gli header separati per funzione. **Guida e archivio** e **Stato e ultima importazione** completano la gestione.

**Indietro** torna alla pagina precedente conservando campi, posizione e selezione. Le modifiche si applicano con **Salva modifiche**; se esci prima del salvataggio, **Continua a modificare** e **Scarta modifiche** compaiono nella pagina. Anche verifica, anteprima, errori ed eliminazione sono gestiti all’interno della finestra dell’app. Solo la selezione di un file usa il gestore documenti di Android.

Nell’importazione, **Lettura e contenuti** e **Accesso ai server** preparano le opzioni della nuova lista. La verifica non salva la sorgente: controlla l’anteprima e premi **Importa lista** o **Sostituisci lista**. **Interrompi verifica** conserva il modulo; cambiare indirizzo o opzioni richiede una nuova verifica. Salva eventuali modifiche in **Sorgente** prima di cambiare URL o file.

## Importazione e HTTP

In **Opzioni → Le mie liste**, **Aggiungi URL** accetta un indirizzo HTTPS oppure HTTP. HTTP richiede la casella **Consenti HTTP per questa lista**, inizialmente disattivata. Lo stesso controllo è disponibile importando un file locale e vale per download della lista, manifest, segmenti e licenze. Il consenso viene memorizzato per sorgente, compreso quando l’indirizzo viene sostituito.

In **Sorgente**, la casella **Consenti HTTP per questa lista** permette di concedere o revocare il consenso con **Salva modifiche**. Alla revoca, il player interrompe la riproduzione della sorgente che era stata autorizzata a usare HTTP; le nuove richieste HTTP e i redirect verso HTTP vengono bloccati. I dati salvati e i preferiti restano disponibili. HTTP invia senza cifratura gli indirizzi e gli eventuali dati di accesso; HTTPS continua a verificare i certificati normalmente. Il consenso non aggiunge eccezioni HTTP ai servizi integrati o all’aggiornatore APK.

Una lista HTTPS può contenere canali HTTP: con il consenso disattivato questi vengono esclusi, e l’anteprima ne riporta il motivo. Se non rimangono canali importabili, la verifica si interrompe senza salvare la sorgente. Un URL relativo viene risolto rispetto all’indirizzo finale della lista scaricata; un file locale deve contenere URL assoluti.

## Limiti e salvataggio

| Elemento | Limite |
| --- | --- |
| Documenti M3U/M3U8, dopo decompressione HTTP, comprese le liste incluse | 64 MiB complessivi |
| Voci lette per sorgente, comprese escluse, duplicate e riferimenti a liste incluse | 50.000 |
| Documenti / profondità delle liste incluse | 16 / 3 livelli oltre la radice |
| Canali attivi complessivi | 100.000 |
| Sorgenti registrate | 10 |
| Singola riga | 64 KiB di caratteri |
| Header per configurazione risultante | 32, fino a 32 KiB complessivi |
| Singolo valore di header | 4.096 caratteri |
| Proprietà aggiuntive per canale | 32, fino a 128 KiB di caratteri complessivi e 32 KiB per valore |
| Chiavi ClearKey per canale | 32 coppie da 128 bit |
| Snapshot SQLite per sorgente / complessivi | 256 MiB / 512 MiB |

Il documento viene letto progressivamente e scritto in uno snapshot SQLite temporaneo, senza conservarne una copia completa in memoria. L’interfaccia mostra MiB e voci lette; **Annulla** chiude il download e rimuove la bozza. L’anteprima conserva soltanto cinque nomi di esempio. La sostituzione della copia attiva avviene dopo la verifica e la riconciliazione degli ID; un errore di rete, formato o spazio conserva la copia precedente. Durante il salvataggio finale i comandi vengono temporaneamente disabilitati.

I file selezionati dal gestore documenti vengono prima copiati nella cache privata con lo stesso limite di byte. L’app non mantiene un collegamento al file originale. Il catalogo in memoria contiene solo i dati di visualizzazione; URL, header e configurazione DRM del singolo canale vengono letti dal disco quando serve riprodurlo.

Gli ID vengono riconciliati, nell’ordine, tramite `tipo + tvg-id`, `tipo + URL`, `tipo + nome + gruppi`, usando solo corrispondenze univoche. Gli ultimi 5.000 canali rimossi possono conservare il loro ID per 30 giorni. Se più qualità condividono lo stesso `tvg-id`, l’app passa alle strategie successive. Senza alcuna corrispondenza univoca viene assegnato un nuovo ID.

Al primo avvio, gli archivi JSON della 0.8.0 vengono convertiti una voce alla volta. Il JSON viene rimosso soltanto dopo il salvataggio riuscito dello snapshot SQLite. ID, preferiti e impostazioni vengono conservati; un archivio non leggibile resta sul disco e viene indicato nella gestione. Il consenso HTTP dei nuovi archivi parte disattivato, anche se un vecchio archivio debug usava HTTP. Liste con stream HTTP precedenti vengono migrate disattivate, per richiedere una scelta esplicita.

## Header e direttive KODIPROP

Ogni configurazione si applica alla singola voce EXTINF e termina al suo URL. Le direttive non vengono eseguite come istruzioni Kodi: Lume ne traduce i valori supportati nella configurazione del proprio player Media3.

| Sintassi | Comportamento |
| --- | --- |
| Attributi EXTINF `http-user-agent`, `http-referrer` | Header comuni |
| `#EXTVLCOPT:http-user-agent=...`, `http-referrer`, `http-referer`, `http-origin`, `http-cookie` | Header comuni |
| `#EXTHTTP:{"User-Agent":"...","Referer":"...","X-Token":"..."}` | Oggetto JSON di header comuni con valori testuali |
| `URL\|User-Agent=...&Referer=...` | Header comuni; nomi e valori con codifica URL form |
| `inputstream.adaptive.common_headers` | Header comuni in forma `Nome=valore&Nome=valore` |
| `inputstream.adaptive.manifest_headers` | Aggiunte o sostituzioni per i soli manifest HLS/DASH |
| `inputstream.adaptive.stream_headers` | Aggiunte o sostituzioni per segmenti, chiavi HLS e flussi progressivi |
| `inputstream.adaptive.manifest_type=mpd` o `dash` | DASH |
| `inputstream.adaptive.manifest_type=hls` | HLS |
| `inputstream.adaptive.license_type` / `license_key` | DRM nelle forme descritte sotto |
| `inputstream.adaptive.drm_legacy` | Forma compatta DRM, dati e header separati da `\|` |
| `inputstream.adaptive.drm` | Sottoinsieme JSON con un solo sistema e una licenza |

Per gli header comuni, dal valore meno prioritario al più prioritario: attributi/EXTVLCOPT, `common_headers`, EXTHTTP, suffisso dell’URL. Si applicano poi gli header specifici per manifest o stream. Per compatibilità con le liste Kodi precedenti, se `manifest_headers` manca, anche `stream_headers` viene usato per i manifest; per separarli occorre dichiarare esplicitamente `manifest_headers`.

I nomi sono confrontati senza distinguere maiuscole e minuscole; `referrer` diventa `Referer`, `cookies` diventa `Cookie`, ed è riconosciuto il prefisso `!` usato da alcune liste. Un User-Agent personalizzato viene inviato una sola volta. Header di trasporto come `Host`, `Content-Length`, `Range`, `Connection`, `Accept-Encoding` e credenziali proxy non sono accettati. Caratteri di controllo o una configurazione oltre i limiti escludono la voce.

Le richieste a un’origine diversa da quella iniziale perdono gli header specificati dalla lista, comprese `Authorization` e `Cookie`, conservando lo User-Agent. L’origine comprende protocollo, host e porta. In **Accesso ai server** si possono autorizzare fino a 16 origini aggiuntive per funzione. Un’autorizzazione per il video non vale per le licenze, i loghi o il download. I redirect sono controllati fino a cinque passaggi; un redirect 307/308 che mantiene un corpo POST richiede un’origine autorizzata prima di inoltrarlo.

Per le licenze su un’origine diversa non vengono ereditati i dati di accesso dello stream: bisogna specificare gli header della licenza. Il contenitore di cookie è inizialmente disattivato. **Mantieni cookie di sessione** conserva in memoria cookie separati per lista e funzione, rispettando dominio, percorso, scadenza e Secure. Un header `Cookie` esplicito prevale sui cookie ricevuti dal server. Disattivazione, cambio di accesso ed eliminazione invalidano il contesto e cancellano le richieste ancora attive; rinominare una lista conserva la sessione.

## ClearKey e Widevine

ClearKey personale è supportato per **DASH con cifratura CENC**. Sono accettati `clearkey` e `org.w3.clearkey`; per Widevine, `widevine` e `com.widevine.alpha`. Sono riconosciuti anche i rispettivi UUID, eventualmente preceduti da `urn:uuid:`. Il comportamento dipende anche dai codec e dal modulo DRM disponibili nel dispositivo Android.

Esempio con valori sintetici pubblici: questi dati sono utili soltanto con un contenuto di prova cifrato usando la stessa coppia.

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="prova" group-title="Test",Canale sintetico
#EXTHTTP:{"User-Agent":"Player di prova"}
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
https://example.invalid/prova/manifest.mpd
```

Forme ammesse in `license_key` per ClearKey:

- `KID:KEY`, con 32 caratteri esadecimali per ciascun valore, oppure più coppie separate da virgole. Sono ammessi anche identificativi con trattini.
- Oggetto JSON `{"KID_esadecimale":"KEY_esadecimale"}`.
- JWK Set `{"keys":[{"kty":"oct","kid":"Base64URL","k":"Base64URL"}],"type":"temporary"}`, anche come `data:application/json;base64,...`.
- URL di un server licenze ClearKey che restituisce la risposta JWK prevista da Android/Media3. Eventuali header seguono `|`, per esempio `https://example.invalid/license|Authorization=Bearer%20esempio`.

Le chiavi locali vengono validate, convertite in una risposta JWK temporanea e passate al callback DRM di Media3. Non viene contattato alcun server licenze per questa forma. Più chiavi sono ammesse; chiavi diverse per lo stesso KID rendono la voce non valida. Widevine personale richiede un URL di licenza, con gli eventuali header nella stessa forma.

La forma compatta `drm_legacy` è `sistema|dati|header`. Il sottoinsieme JSON di `drm` accetta un solo sistema, una proprietà `license` e l’eventuale `priority`; in `license` sono ammessi `server_url`, `req_headers` (query o oggetto di stringhe) e `keyids` (mappa esadecimale per ClearKey). Esempio:

```m3u
#KODIPROP:inputstream.adaptive.drm={"org.w3.clearkey":{"license":{"keyids":{"00112233445566778899aabbccddeeff":"ffeeddccbbaa99887766554433221100"}}}}
```

## Esclusioni e dati privati

Non è una promessa di compatibilità con qualsiasi estensione M3U. Restano esclusi UDP/RTSP, PlayReady, SmoothStreaming, selezione tra più sistemi DRM, licenze implicite nel manifest e trasformazioni diverse dall’insieme dichiarato sotto. `license_data`, `license_flags`, `server_certificate`, `pre_init_data` e altre proprietà DRM non tradotte escludono la voce con un motivo nell’anteprima. Una chiave esadecimale priva di KID o una stringa KID/KEY concatenata non viene divisa automaticamente. Le preferenze di tracce, lingua/paese dei canali, restrizioni parentali e altre estensioni non dichiarate attive rimangono metadati; non implicano automaticamente un controllo del player.

Gli indirizzi della lista e degli stream, gli header, i token e le chiavi vengono salvati nel sandbox privato dell’app; i cookie di sessione restano in memoria. Il backup Android è disabilitato. Questi dati non vengono inclusi nei metadati del catalogo, nei messaggi di importazione, nel provider AURA o nelle tessere dei launcher. Anche gli URL dei loghi personali vengono risolti dall’archivio privato quando servono. Per le segnalazioni usa nomi, errori e campioni sintetici, evitando di pubblicare credenziali o liste private.

## Metadati e migrazione del formato 4

Il formato persistito 4 conserva attributi del canale, durata, nome EPG distinto dal nome visualizzato, opzioni di riproduzione e riferimenti al documento di provenienza. Attributi globali e indirizzi finali dei documenti vengono salvati una sola volta per documento. La classificazione live/VOD è indicizzata nello snapshot. Le configurazioni EPG e catch-up alimentano le funzioni descritte sotto; lingua, paese, restrizioni e tracce restano conservati senza essere pubblicati nel catalogo condiviso.

Gli snapshot dei formati 2 e 3 vengono migrati in transazione. Il formato 3 recupera la classificazione dai metadati già conservati. Gli ID e i preferiti non cambiano. I metadati scartati dalla versione precedente richiedono un download completo, senza ETag, oppure **Sostituisci file** sulla stessa sorgente. La scelta HTTP dei loghi parte disattivata. Un errore di migrazione conserva lo snapshot sul disco e viene segnalato nella gestione.

**Ultima importazione** mostra conteggi, motivi degli scarti e proprietà conservate ma non attive. Gli esempi sono limitati a 20, con precedenza agli scarti; i conteggi comprendono tutte le voci. Lo stato mostra anche eventuali messaggi, scadenza e numero di connessioni comunicati dal servizio. Questi ultimi sono informativi e non avviano player aggiuntivi.

## Lettura e liste incluse

In **Lettura e contenuti** sono disponibili M3U estesa o semplice e codifiche UTF-8, Windows-1252 e Latin-1. La modalità semplice ricava il nome dal percorso dell’URL, escludendone la query. HTML e manifest HLS a segmenti rimangono input non validi.

`tvg-name` resta distinto dal nome dopo la virgola; `ch-number` è riconosciuto insieme a `tvg-chno`. Il numero 9999 è un numero valido per un canale personale, distinto dal numero assente. La scelta dei gruppi mantiene il comportamento attuale come predefinito; il profilo Kodi resetta l’eredità dopo un `group-title`, mentre OTT Navigator applica `EXTGRP` alla sola voce successiva. Il profilo Kodi accetta il marcatore VOD isolato, continuando a rifiutare le strutture HLS di segmenti.

`type="playlist"` e `content-type="playlist"` caricano documenti HTTP/HTTPS inclusi, in ordine, con limiti condivisi di byte, voci, profondità e documenti. I cicli vengono rifiutati. Ogni figlia usa il proprio indirizzo finale per risolvere gli URL relativi; una base loghi o un indirizzo EPG ereditato conserva l’origine del documento che l’ha dichiarata. Un errore strutturale o di download conserva l’intero snapshot precedente. Un file locale deve fornire indirizzi assoluti per gli stream e i documenti inclusi.

`refresh` accetta durate ISO-8601 o valori con suffisso `s`, `m`, `h`, `d`; un numero senza unità viene segnalato. L’intervallo è limitato fra 15 minuti e 7 giorni. `refresh_at` accetta un istante ISO-8601; l’attesa automatica non supera sette giorni. Un intervallo manuale prevale su `refresh` e `refresh_at`. I controlli avvengono durante l’uso dell’app e rispettano i limiti del server; non è incluso un servizio di aggiornamento durante standby.

## Accesso avanzato e loghi personali

Sono configurabili header distinti per download, manifest, video/audio, licenze DRM, chiavi HLS, loghi e guida EPG. I valori manuali prevalgono su quelli della singola voce per la funzione selezionata. Gli header sono limitati a caratteri ASCII stampabili, coerentemente con il trasporto HTTP usato dall’app. `seekable=0/1` dopo `|` viene trattato come opzione del player e non inviato al server come header.

`url-logo` e i loghi relativi vengono risolti preservando le query già codificate. L’app non aggiunge estensioni ai nomi dei loghi. **Consenti HTTP per i loghi** è una scelta distinta dal consenso HTTP di download e video. I loghi personali ammettono porte del provider e mantengono i controlli su indirizzi pubblici e DNS: indirizzi locali e riservati restano esclusi.

Cache, richieste e attese dei loghi personali sono separate per sorgente e contesto di accesso. Un cambio di credenziali invalida il relativo contesto; l’eliminazione rimuove anche i file della cache appartenenti alla sorgente. Le immagini sono scaricate quando visibili, con tre download concorrenti, cache bitmap da 6 MiB e cache disco fino a 24 MiB/1.024 immagini, ripulita verso 20 MiB/768 immagini.

## Richieste di licenza e chiavi HLS

Il formato legacy `URL|header|R{SSM}|R` usa un POST con challenge e risposta binari. Nelle forme con campo POST esplicito vuoto viene usato GET; le forme precedenti a uno/due campi mantengono POST. Sono supportati challenge `R{SSM}`, `b{SSM}`, `B{SSM}` e risposte `R`, `B`, `Jpercorso`, `JBpercorso`, `BJpercorso`, con percorso JSON esplicito separato da `/`.

Il sottoinsieme JSON moderno comprende inoltre `use_http_get_request`, `req_params`, `req_data`, `wrapper`, `unwrapper` e `unwrapper_params.path_data`. `req_data` è Base64 e può usare `{CHA-RAW}` come intero corpo oppure `{CHA-B64}`/`{CHA-B64U}` all’interno di un modello testuale. I wrapper ammessi sono `base64` e `urlenc`; gli unwrapper sono `base64` e un’estrazione `json` con percorso assoluto. Sono ammesse fino a tre operazioni ordinate, una sola estrazione JSON, profondità 16 e risposte fino a 1 MiB. Segnaposto SID/PSSH/KID, XML, ricerca ricorsiva, opzioni HDCP e script non vengono interpretati. Le proprietà avanzate non supportate sono errori espliciti.

Per HLS AES-128 senza un sistema DRM dichiarato, la forma Kodi `parametri|header||` aggiunge i parametri alle sole richieste delle chiavi HLS e applica loro header dedicati. Manifest e segmenti non ricevono questi header. La normale cifratura HLS gestita dal motore rimane distinta dal percorso ClearKey DASH/CENC.


## Guida XMLTV personale

**Opzioni → Le mie liste → [lista] → Guida e archivio** configura la guida della singola sorgente. Gli indirizzi provengono da `url-tvg`/`x-tvg-url`, dai documenti inclusi o dalla singola voce; puoi sostituirli con fino a 16 URL manuali. Gli URL relativi conservano la base del documento che li dichiara. Sono supportati XML, gzip e xz, con testo UTF-8/UTF-16. UTF-32 richiede conversione.

La guida ha un consenso **Consenti HTTP per questa guida** e un accesso dedicato in **Guida e archivio → Accesso alla guida**. Header, cookie e origini autorizzate restano separati da quelli video. Cambiare lingua, URL, fuso, correzione o consenso EPG annulla le richieste della guida senza sostituire la sessione del video. Il consenso HTTP della lista continua a coprire il video dell’archivio.

L’associazione usa prima `tvg-id` esatto, poi `tvg-name` o nome del canale solo se univoco. Non viene applicata una ricerca approssimativa. In **Guida e archivio → Associa canali** puoi scegliere guida e ID XMLTV, ripristinare la scelta automatica o escludere un canale. La ricerca e la scelta dei canali XMLTV sono paginate. Un feed senza corrispondenze mantiene le definizioni per poterle associare manualmente; la copia valida precedente rimane disponibile. Sostituire o riordinare gli URL fa ricalcolare le associazioni degli indirizzi cambiati dopo un’importazione riuscita; lo storico del nuovo feed non viene mescolato a quello del precedente. Un errore conserva ancora la copia precedente.

XMLTV viene analizzato in streaming: titoli fino a 300 caratteri, descrizioni fino a 1.600, profondità 32, dichiarazioni/attributi limitati. DTD esterne non vengono scaricate; entità interne e sottoinsiemi DTD vengono rifiutati. Le definizioni dei canali devono precedere i programmi. Lo `stop` mancante viene ricavato dal programma successivo dello stesso canale; l’ultimo evento privo di fine non viene inventato. La lingua preferita precede i testi senza lingua e quelli in altre lingue. Sono gestiti timestamp XMLTV parziali, offset espliciti e un fuso configurabile per quelli senza offset.

| Risorsa EPG | Limite |
| --- | --- |
| Download compresso o XML non compresso | 32 MiB |
| XML dopo decompressione | 256 MiB |
| Definizioni di canale per feed | 100.000 |
| Programmi memorizzati per sorgente | 1.000.000 |
| Database per sorgente / complessivi | 128 MiB / 256 MiB |
| Conservazione configurabile | 1–7 giorni precedenti, 1–48 ore successive |
| Risultato di una finestra UI | fino a 200 canali e 5.000 programmi |
| Ricerca programmi | 50 risultati per pagina |

Si salvano soltanto programmi dei canali associati, nella finestra di conservazione dopo l’applicazione dello shift. `tvg-shift` può essere sostituito da una correzione EPG manuale, senza sommare entrambe. I dati hanno identità separate per lista: due sorgenti con lo stesso `tvg-id` non condividono programmi. Il database personale non entra in `guide.json`, AURA o tessere Home; anche i loghi XMLTV vengono risolti privatamente e rispettano la politica dei loghi personali.

Stato, ultimo aggiornamento, canali associati, programmi e problemi sono distinti dallo stato della playlist. Aggiornamenti ogni sei ore durante l’uso dell’app, ETag/Last-Modified, HTTP 304 e pause HTTP 429 sono gestiti per feed. Un errore non cancella la guida valida né la riproduzione. **Svuota guida scaricata** rimuove la cache EPG della sorgente; eliminare la lista rimuove anche guida e avanzamenti video.

Nella **Guida TV** puoi scegliere un giorno precedente o successivo e cercare nell’intera guida personale. Le finestre visibili e il programma corrente vengono interrogati su disco, senza caricare tutto lo storico in memoria. Lo storico dipende dai dati effettivamente forniti dal servizio e da quelli già conservati.

## Video on demand

Il pulsante **Video** accanto alla scelta della lista apre **Video delle mie liste**, con ricerca per nome, scelta di lista/gruppo e pagine da 50 elementi lette da SQLite. Le voci VOD vengono riconosciute dalle indicazioni di contenuto e dalla durata positiva. **Lettura e contenuti → Tipo dei contenuti** permette di mantenere la scelta automatica o forzare una lista come dirette o video. La modifica conserva gli ID; può richiedere di riaprire il contenuto.

Live, video e archivio usano lo stesso player del servizio. I video offrono posizione, durata, barra di ricerca quando consentita, −10 secondi, +30 secondi e **Dall’inizio**. Su TV le frecce laterali cercano nel video quando il pannello non ha il focus; freccia giù porta ai comandi. I tasti media precedente/successivo cercano nel video e non cambiano canale. `seekable=0` impedisce la ricerca anche dai controlli di sistema.

La posizione viene salvata privatamente ogni cinque secondi e durante pausa, cambio contenuto, errore o arresto. Le posizioni inferiori a cinque secondi ripartono dall’inizio; un video completato riparte da zero. Lo storico conserva fino a 5.000 elementi e distingue video ed eventi di archivio. I video non sostituiscono l’ultima diretta e non entrano nello zapping. La fine naturale completa il contenuto, senza attivare i tentativi di riconnessione della diretta. Pausa/ripresa e recupero da errore mantengono la posizione, nei limiti di ricerca del flusso.

## Archivio e dall’inizio

Un programma concluso della guida offre **Rivedi dall’archivio** solo quando il canale dichiara un catch-up supportato e l’evento rientra nella sua disponibilità. **Guarda dall’inizio** per programmi ancora in onda richiede l’opzione esplicita della sorgente e il supporto del servizio. **Torna alla diretta** riapre il canale live. I dati forniti dal servizio restano necessari: questa funzione non crea registrazioni locali.

| Convenzione | Traduzione |
| --- | --- |
| `default` | `catchup-source` assoluto o relativo; senza modello, query `utc`/`lutc` |
| `append` | aggiunta del modello alla query live |
| `shift` / `timeshift` | query UTC; modello esplicito se presente |
| `flussonic` / `flussonic-hls` | HLS `timeshift_rel` |
| `flussonic-ts` / `fs` | TS `timeshift_abs` |
| `flussonic-dash` | DASH `archive` |
| `xc` | percorso Xtream `timeshift`, durata arrotondata a minuti e data nel fuso del provider |
| `vod` | modello oppure URL completo da `catchup-id` XMLTV |

Sono tradotti `{utc}`/`{start}`, `{utcend}`/`{end}`, `{lutc}`/`{now}`/`{timestamp}`, `{duration}`, `{offset}`, `{catchup-id}` e i componenti `{Y}`, `{m}`, `{d}`, `{H}`, `{M}`, `{S}` e `start-year/mon/day/hour/min/sec`. Sono accettati anche i segnaposto con prefisso `$`, formati data espliciti come `${start:Y-m-dTH:M:S}` e divisori temporali per durata/offset, per esempio `{duration:60}`. Segnaposto e modalità sconosciuti producono un messaggio esplicito; non vengono eseguiti script o espressioni del provider.

`catchup-days`, `catchup-time` in secondi, `timeshift` e `tvg-rec` forniscono la finestra dichiarata; puoi sostituirla nelle impostazioni. La conservazione EPG resta al massimo di sette giorni anche se il servizio dichiara un archivio più lungo. Sono configurabili fuso del provider, `catchup-correction` e margini prima/dopo fino a 300 secondi. I tempi non possono oltrepassare la disponibilità o il momento corrente; le query firmate esistenti conservano la propria codifica.

La ricerca usa il seek nativo quando disponibile. Se il modello supporta un tempo iniziale variabile, può essere rigenerato l’URL per la nuova posizione; viene mantenuta l’identità dell’evento e la sua posizione assoluta. Un archivio su un’altra origine non eredita gli header privati della diretta senza autorizzazione per quella funzione. DRM e licenze restano gestiti dalle regole già dichiarate; un archivio HLS non viene trattato come ClearKey DASH/CENC.

Non è garantita la compatibilità con convenzioni proprietarie non descritte qui. La verifica locale usa contenuti sintetici di proprietà del progetto su emulatori TV e smartphone; non certifica disponibilità, autorizzazioni, codec o comportamento dei servizi reali.
