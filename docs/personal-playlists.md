# Compatibilità delle liste personali

Questo documento descrive le liste personali di **Lume TV 0.10.2**, con biblioteca Video e scorrimento continuo. Sono consentite fino a 500.000 voci lette per sorgente, un milione di voci importate complessive e 256 MiB per documento. Gli archivi precedenti vengono conservati con ID, preferiti, impostazioni e avanzamenti.

La versione 0.10.2 include il recupero delle righe anomale, la compatibilità delle configurazioni riconoscibili e le sorgenti localplay descritte sotto, con formato e protezione determinati dal resolver.

## Gestione tramite pagine

La gestione riprende lo stile di **Opzioni**: su smartphone usa la stessa intestazione, le sezioni, le descrizioni e le frecce delle righe; su TV le pagine di scelta mostrano elenco e dettaglio affiancati, con lo stesso focus del telecomando. I moduli restano in una colonna per lasciare spazio all’inserimento.

**Opzioni → Le mie liste → [lista]** apre una pagina dedicata. **Sorgente** contiene nome, attivazione, consenso HTTP e sostituzione; **Lettura e contenuti** raccoglie riconoscimento VOD, regole dei gruppi, formato, codifica e aggiornamento; **Accesso ai server** contiene gli header separati per funzione. **Guida e archivio** e **Stato e ultima importazione** completano la gestione.

**Indietro** torna alla pagina precedente conservando campi, posizione e selezione. Le modifiche si applicano con **Salva modifiche**; se esci prima del salvataggio, **Continua a modificare** e **Scarta modifiche** compaiono nella pagina. Anche verifica, anteprima, errori ed eliminazione sono gestiti all’interno della finestra dell’app. Solo la selezione di un file usa il gestore documenti di Android.

Nell’importazione, **Lettura e contenuti** e **Accesso ai server** preparano le opzioni della nuova lista. La verifica non salva la sorgente: controlla l’anteprima e premi **Importa lista** o **Sostituisci lista**. **Interrompi verifica** conserva il modulo; cambiare indirizzo o opzioni richiede una nuova verifica. Salva eventuali modifiche in **Sorgente** prima di cambiare URL o file.

## Consultazione e filtri

Su smartphone **Filtri** apre i controlli secondari all'interno del catalogo. Il riepilogo mantiene visibili lista, gruppo e ricerca attivi; **Azzera filtri** ripristina la vista della sorgente selezionata. In orizzontale i comandi si compattano e la navigazione conserva icone con descrizioni accessibili. I controlli touch mantengono un'area di almeno 48 dp.

Su TV la ricerca si apre sotto i controlli del catalogo e la query attiva rimane riconoscibile. Dalla prima colonna della griglia, **Sinistra** raggiunge i filtri; **Giù** torna alla riga precedente. Canali e Radio conservano separatamente lista, query e posizione. La cache delle posizioni per query è limitata a dodici contesti; i dati dei canali mantengono i limiti dei blocchi descritti sotto.

Gruppi e scelte della guida si aprono nella stessa finestra dell'app; **Indietro** ripristina il controllo di provenienza. Durante una diretta personale, l'elenco canali usa lo stesso pannello delle emittenti integrate, con scorrimento sull'intera sorgente e preferiti filtrati sul database.

Il salvataggio distingue l'organizzazione dei canali dalla scrittura finale. Gli stati vuoti della guida distinguono aggiornamento, errore, guida disattivata, associazioni mancanti e assenza di programmi nella fascia. Una guida vuota non viene automaticamente interpretata come assenza di supporto EPG. Le righe in caricamento conservano il posto nell'elenco; **Riprova** diventa disponibile dopo un errore.

## Importazione e HTTP

In **Opzioni → Le mie liste**, **Aggiungi URL** accetta un indirizzo HTTPS oppure HTTP. HTTP richiede la casella **Consenti HTTP per questa lista**, inizialmente disattivata. Lo stesso controllo è disponibile importando un file locale e vale per download della lista, manifest, segmenti e licenze. Il consenso viene memorizzato per sorgente, compreso quando l’indirizzo viene sostituito.

In **Sorgente**, la casella **Consenti HTTP per questa lista** permette di concedere o revocare il consenso con **Salva modifiche**. Alla revoca, il player interrompe la riproduzione della sorgente che era stata autorizzata a usare HTTP; le nuove richieste HTTP e i redirect verso HTTP vengono bloccati. I dati salvati e i preferiti restano disponibili. HTTP invia senza cifratura gli indirizzi e gli eventuali dati di accesso; HTTPS continua a verificare i certificati normalmente. Il consenso non aggiunge eccezioni HTTP ai servizi integrati o all’aggiornatore APK.

Una lista HTTPS può contenere canali HTTP: con il consenso disattivato questi vengono esclusi, e l’anteprima ne riporta il motivo. Se non rimangono canali importabili, la verifica si interrompe senza salvare la sorgente. Un URL relativo viene risolto rispetto all’indirizzo finale della lista scaricata; un file locale deve contenere URL assoluti.

### Recupero delle righe anomale

La lettura prosegue dopo testo isolato e separatori decorativi riconoscibili: le righe vengono ignorate e conteggiate, conservando i canali validi prima e dopo. In M3U estesa, un URL HTTP/HTTPS assoluto senza `EXTINF` viene recuperato con il nome provvisorio **Canale N**; i percorsi relativi senza metadati non vengono trasformati in canali. Un `EXTINF` senza URL prima del successivo `EXTINF` o della fine del documento viene escluso singolarmente. Metadati con virgolette incomplete o senza nome recuperabile vengono esclusi insieme al relativo indirizzo.

L'anteprima e **Stato e ultima importazione** distinguono voci recuperate, righe ignorate, escluse e duplicate. I conteggi comprendono l'intera lettura; gli esempi restano limitati a 20 e indicano canale, riga, motivo e, quando necessario, provenienza da una lista inclusa. Gli scarti hanno precedenza sui separatori, anche quando questi compaiono più avanti o in altri documenti. Gli esempi informativi uguali vengono raggruppati; le altre segnalazioni non bloccanti conservano fino a tre esempi per categoria, proprietà e documento. URL, token, valori degli header e chiavi non vengono mostrati nei messaggi diagnostici. I log tecnici riportano soltanto conteggi, tipo di errore e numero di riga.

Direttive rimaste dopo l'ultimo canale vengono indicate come **direttive finali senza canale ignorate**, senza aggiungere una voce al conteggio degli scarti. Un vero `EXTINF` senza indirizzo continua invece a essere escluso singolarmente. Modelli e segnaposto non vengono completati inventando URL o dati mancanti.

Il filtro RSI del catalogo integrato non si applica alle liste personali. `group-logo` viene conservato e segnalato come immagine del gruppo; la conservazione non implica la visualizzazione di copertine dei gruppi.

Gli snapshot importati con il lettore precedente restano utilizzabili. **Aggiorna lista** richiede nuovamente il documento completo, senza riutilizzare ETag o Last-Modified finché la rilettura non è riuscita; per i file locali usa **Sostituisci file**. La riconciliazione degli ID conserva le corrispondenze univoche, comprese quelle dei vecchi percorsi `localplay` ricostruiti come URL relativi. Un errore mantiene la copia precedente.

Un aggiornamento senza anteprima conserva la copia precedente se incontra metadati irrecuperabili o voci senza indirizzo, oppure se il catalogo si riduce in presenza di righe estranee. Per accettare consapevolmente il risultato, usa **Sorgente → Modifica URL** o **Sostituisci file**, verifica l'anteprima e salva. Non viene applicata una sostituzione parziale dopo errori di rete, codifica, HTML, manifest HLS, cicli o superamento dei limiti; rimane necessario almeno un canale valido.

## Limiti e salvataggio

| Elemento | Limite |
| --- | --- |
| Documenti M3U/M3U8, dopo decompressione HTTP, comprese le liste incluse | 256 MiB complessivi |
| Voci lette per sorgente, comprese escluse, duplicate e riferimenti a liste incluse | 500.000 |
| Documenti / profondità delle liste incluse | 16 / 3 livelli oltre la radice |
| Voci importate complessive, comprese quelle nelle sorgenti disattivate | 1.000.000 |
| Sorgenti registrate | 10 |
| Singola riga | 64 KiB di caratteri |
| Header per configurazione risultante | 32, fino a 32 KiB complessivi |
| Singolo valore di header | 4.096 caratteri |
| Proprietà aggiuntive per canale | 32, fino a 128 KiB di caratteri complessivi e 32 KiB per valore |
| Chiavi ClearKey per canale | 32 coppie da 128 bit |
| Snapshot SQLite per sorgente / complessivi | 1 GiB / 2 GiB |

Il documento viene letto progressivamente e scritto in uno snapshot SQLite temporaneo, senza conservarne una copia completa in memoria. L’interfaccia mostra MiB e voci lette; **Annulla** chiude il download e rimuove la bozza. L’anteprima conserva soltanto cinque nomi di esempio. La sostituzione della copia attiva avviene dopo la verifica e la riconciliazione degli ID; un errore di rete, formato o spazio conserva la copia precedente. Durante il salvataggio finale i comandi vengono temporaneamente disabilitati.

Anche le firme usate per riconoscere i duplicati vengono conservate in una tabella temporanea su disco. Gli aggiornamenti richiedono spazio per la copia precedente, la bozza e gli indici temporanei; l'importazione si interrompe se lo spazio disponibile non basta.

I file selezionati dal gestore documenti vengono prima copiati nella cache privata con lo stesso limite di byte. L’app non mantiene un collegamento al file originale. La navigazione usa uno scorrimento continuo con righe riciclate: i blocchi da 200 voci si caricano automaticamente vicino alla parte visibile dell’elenco, senza pulsanti di pagina. Video e selettori usano lo stesso comportamento con blocchi da 50. Ogni elenco conserva al massimo tre blocchi vicini in memoria; quelli distanti vengono riletti dal database tornando indietro. Il contatore indica tutti i risultati della selezione e la ricerca considera l'intero database. In memoria restano una breve anteprima per sorgente, i recenti necessari al ripristino e le finestre visualizzate; URL, header e configurazione DRM del singolo canale vengono letti dal disco quando serve riprodurlo.

Gli ID vengono riconciliati, nell’ordine, tramite `tipo + tvg-id`, `tipo + URL`, `tipo + nome + gruppi`, usando solo corrispondenze univoche. Gli ultimi 5.000 canali rimossi possono conservare il loro ID per 30 giorni. Se più qualità condividono lo stesso `tvg-id`, l’app passa alle strategie successive. Senza alcuna corrispondenza univoca viene assegnato un nuovo ID.

Al primo avvio, gli archivi JSON della 0.8.0 vengono convertiti una voce alla volta. Il JSON viene rimosso soltanto dopo il salvataggio riuscito dello snapshot SQLite. ID, preferiti e impostazioni vengono conservati; un archivio non leggibile resta sul disco e viene indicato nella gestione. Il consenso HTTP dei nuovi archivi parte disattivato, anche se un vecchio archivio debug usava HTTP. Liste con stream HTTP precedenti vengono migrate disattivate, per richiedere una scelta esplicita.

## Header e direttive KODIPROP

Ogni configurazione si applica alla singola voce EXTINF e termina al suo URL. Le direttive non vengono eseguite come istruzioni Kodi: Lume ne traduce i valori supportati nella configurazione del proprio player Media3.

Nella lettura delle liste, `KODIPROP`, `EXTVLCOPT` ed `EXTHTTP` possono precedere o seguire `EXTINF`. La configurazione termina sempre all'indirizzo della voce. Header, tipo manifest, durata, opzioni e DRM vengono azzerati per la successiva; restano valide le impostazioni esplicitamente globali e il profilo dei gruppi selezionato. Per User-Agent e Referer prevalgono, nell'ordine, i valori globali, le direttive precedenti a `EXTINF`, gli attributi della voce e le direttive successive. Le altre precedenze e gli override manuali descritti sotto restano applicabili.

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
| `inputstream.adaptive.file_type=mpd/dash` oppure `m3u8/hls` | Indicazione di formato, subordinata a MIME e manifest espliciti |
| `inputstream.adaptive.license_type` / `license_key` | DRM nelle forme descritte sotto |
| `inputstream.adaptive.drm_legacy` | Forma compatta DRM, dati e header separati da `\|` |
| `inputstream.adaptive.drm` | Sottoinsieme JSON con un solo sistema e una licenza |

Per gli header comuni, dal valore meno prioritario al più prioritario: attributi/EXTVLCOPT, `common_headers`, EXTHTTP, suffisso dell’URL. Si applicano poi gli header specifici per manifest o stream. Per compatibilità con le liste Kodi precedenti, se `manifest_headers` manca, anche `stream_headers` viene usato per i manifest; per separarli occorre dichiarare esplicitamente `manifest_headers`.

I nomi sono confrontati senza distinguere maiuscole e minuscole; `referrer` diventa `Referer`, `cookies` diventa `Cookie`, ed è riconosciuto il prefisso `!` usato da alcune liste. Un User-Agent personalizzato viene inviato una sola volta. Header di trasporto come `Host`, `Content-Length`, `Range`, `Connection`, `Accept-Encoding` e credenziali proxy non sono accettati. Caratteri di controllo o una configurazione oltre i limiti escludono la voce.

Nelle proprietà `common_headers`, `manifest_headers` e `stream_headers` è accettato anche un User-Agent abbreviato riconoscibile, per esempio `HbbTV/1.6.1`, tradotto in un solo header `User-Agent`. La forma mantiene le precedenze e gli ambiti della proprietà. Stringhe arbitrarie senza nome header e combinazioni malformate restano errori; gli header delle licenze richiedono la sintassi esplicita.

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

Se manca `license_type`, una struttura locale `KID:KEY`, mappa JSON, JWK o data URI viene riconosciuta come ClearKey soltanto dopo la validazione completa. Valori ambigui, chiavi in conflitto o payload malformati restano esclusi. Una dichiarazione DRM esplicita prevale sempre; un semplice URL di licenza richiede il tipo del sistema. Anche ClearKey riconosciuto automaticamente mantiene il requisito DASH/CENC.

## Sorgenti localplay

Una riga `localplay=Nove` indica una sorgente da risolvere, distinta dagli URL HTTP/HTTPS. Il riconoscimento è comune a tutte le liste, anche ai file locali. Le route ammesse sono Nove, RealTime, DMAX, Giallo, Turbo/MotorTrend, HGTV, FoodNetwork, K2, Frisbee e Discovery, con gli alias espliciti per i nomi con spazi. I valori sconosciuti vengono esclusi con un motivo; non diventano percorsi relativi e non vengono eseguiti come comandi.

La sorgente resta tipizzata nell'archivio privato, con ID, gruppi e metadati della lista. All'apertura viene usato lo stesso resolver Discovery del catalogo integrato. Le richieste del resolver rispettano validità, cancellazione, cookie e consenso HTTP del contesto personale, senza inoltrare gli header privati della lista durante la ricerca iniziale del provider. Gli header e le impostazioni espliciti della lista si applicano allo stream risolto con le normali precedenze; DRM e header forniti dal resolver vengono mantenuti. Non viene contattato il provider durante l'importazione.

Per le sorgenti `localplay`, formato e protezione sono determinati dal resolver. Le indicazioni statiche `mimetype`, `manifest_type`, `file_type`, `license_type`, `license_key`, `drm_legacy` e `drm` restano conservate nella voce ma non sostituiscono quelle del provider. Uno stream HLS non protetto resta quindi senza DRM anche se la lista contiene vecchie chiavi ClearKey; uno stream protetto mantiene il sistema e la licenza restituiti dal resolver. Restano applicati gli header della lista e gli override manuali per risorsa. Questa precedenza vale anche per le route già salvate, alla successiva apertura del canale. Per le voci con URL HTTP/HTTPS diretto continuano a valere le configurazioni DRM e di formato della lista.

La forma compatta `drm_legacy` è `sistema|dati|header`. Il sottoinsieme JSON di `drm` accetta un solo sistema, una proprietà `license` e l’eventuale `priority`; in `license` sono ammessi `server_url`, `req_headers` (query o oggetto di stringhe) e `keyids` (mappa esadecimale per ClearKey). Esempio:

```m3u
#KODIPROP:inputstream.adaptive.drm={"org.w3.clearkey":{"license":{"keyids":{"00112233445566778899aabbccddeeff":"ffeeddccbbaa99887766554433221100"}}}}
```

## Esclusioni e dati privati

Non è una promessa di compatibilità con qualsiasi estensione M3U. Restano esclusi UDP/RTSP, PlayReady, SmoothStreaming, selezione tra più sistemi DRM, licenze implicite nel manifest e trasformazioni diverse dall’insieme dichiarato sotto. `license_data`, `license_flags`, `server_certificate`, `pre_init_data` e altre proprietà DRM non tradotte escludono la voce con un motivo nell’anteprima. Una chiave esadecimale priva di KID o una stringa KID/KEY concatenata non viene divisa automaticamente. Le preferenze di tracce, lingua/paese dei canali, restrizioni parentali e altre estensioni non dichiarate attive rimangono metadati; non implicano automaticamente un controllo del player.

Gli indirizzi della lista e degli stream, gli header, i token e le chiavi vengono salvati nel sandbox privato dell’app; i cookie di sessione restano in memoria. Il backup Android è disabilitato. Questi dati non vengono inclusi nei metadati del catalogo, nei messaggi di importazione, nel provider AURA o nelle tessere dei launcher. Anche gli URL dei loghi personali vengono risolti dall’archivio privato quando servono. Per le segnalazioni usa nomi, errori e campioni sintetici, evitando di pubblicare credenziali o liste private.

## Metadati e migrazione del formato 4

Il formato persistito 4 conserva attributi del canale, durata, nome EPG distinto dal nome visualizzato, opzioni di riproduzione e riferimenti al documento di provenienza. Attributi globali e indirizzi finali dei documenti vengono salvati una sola volta per documento. La classificazione live/VOD è indicizzata nello snapshot; i nuovi archivi conservano anche versione del riconoscimento, motivo e ambito del marcatore Kodi. Le configurazioni EPG e catch-up alimentano le funzioni descritte sotto; lingua, paese, restrizioni e tracce restano conservati senza essere pubblicati nel catalogo condiviso.

Gli snapshot dei formati 2 e 3 vengono migrati in transazione. Il formato 3 recupera la classificazione dai metadati già conservati. Gli ID e i preferiti non cambiano. I metadati scartati dalla versione precedente richiedono un download completo, senza ETag, oppure **Sostituisci file** sulla stessa sorgente. La scelta HTTP dei loghi parte disattivata. Un errore di migrazione conserva lo snapshot sul disco e viene segnalato nella gestione.

**Ultima importazione** mostra conteggi, motivi degli scarti e proprietà conservate ma non attive. Gli esempi sono limitati a 20, con precedenza agli scarti; i conteggi comprendono tutte le voci. Lo stato mostra anche eventuali messaggi, scadenza e numero di connessioni comunicati dal servizio. Questi ultimi sono informativi e non avviano player aggiuntivi.

## Lettura e liste incluse

In **Lettura e contenuti** sono disponibili M3U estesa o semplice e codifiche UTF-8, Windows-1252 e Latin-1. La modalità semplice ricava il nome dal percorso dell’URL, escludendone la query. HTML e manifest HLS a segmenti rimangono input non validi.

`tvg-name` resta distinto dal nome dopo la virgola; `ch-number` è riconosciuto insieme a `tvg-chno`. Il numero 9999 è un numero valido per un canale personale, distinto dal numero assente. La scelta dei gruppi mantiene il comportamento attuale come predefinito; il profilo Kodi resetta l’eredità dopo un `group-title`, mentre OTT Navigator applica `EXTGRP` alla sola voce successiva. Il profilo Kodi accetta il marcatore VOD isolato, continuando a rifiutare le strutture HLS di segmenti.

`type="playlist"` e `content-type="playlist"` caricano documenti HTTP/HTTPS inclusi, in ordine, con limiti condivisi di byte, voci, profondità e documenti. I cicli vengono rifiutati. Ogni figlia usa il proprio indirizzo finale per risolvere gli URL relativi; una base loghi o un indirizzo EPG ereditato conserva l’origine del documento che l’ha dichiarata. Un errore bloccante del documento o di download conserva l’intero snapshot precedente; il recupero delle singole voci segue le regole descritte sopra. Un file locale deve fornire indirizzi assoluti per gli stream e i documenti inclusi.

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

L’associazione usa prima `tvg-id` esatto, poi `tvg-name` o nome del canale solo se univoco. Non viene applicata una ricerca approssimativa. In **Guida e archivio → Associa canali** puoi scegliere guida e ID XMLTV, ripristinare la scelta automatica o escludere un canale. La ricerca e la scelta dei canali XMLTV usano lo scorrimento continuo con caricamento automatico. Un feed senza corrispondenze mantiene le definizioni per poterle associare manualmente; la copia valida precedente rimane disponibile. Sostituire o riordinare gli URL fa ricalcolare le associazioni degli indirizzi cambiati dopo un’importazione riuscita; lo storico del nuovo feed non viene mescolato a quello del precedente. Un errore conserva ancora la copia precedente.

XMLTV viene analizzato in streaming: titoli fino a 300 caratteri, descrizioni fino a 1.600, profondità 32, dichiarazioni/attributi limitati. DTD esterne non vengono scaricate; entità interne e sottoinsiemi DTD vengono rifiutati. Le definizioni dei canali devono precedere i programmi. Lo `stop` mancante viene ricavato dal programma successivo dello stesso canale; l’ultimo evento privo di fine non viene inventato. La lingua preferita precede i testi senza lingua e quelli in altre lingue. Sono gestiti timestamp XMLTV parziali, offset espliciti e un fuso configurabile per quelli senza offset.

| Risorsa EPG | Limite |
| --- | --- |
| Download compresso o XML non compresso | 32 MiB |
| XML dopo decompressione | 256 MiB |
| Definizioni di canale per feed | 100.000 |
| Dirette candidate all'associazione della guida per lista | 100.000 |
| Programmi memorizzati per sorgente | 1.000.000 |
| Database per sorgente / complessivi | 128 MiB / 256 MiB |
| Conservazione configurabile | 1–7 giorni precedenti, 1–48 ore successive |
| Risultato di una finestra UI | fino a 200 canali e 5.000 programmi |
| Ricerca programmi | blocchi da 50 risultati con scorrimento continuo |

Si salvano soltanto programmi dei canali associati, nella finestra di conservazione dopo l’applicazione dello shift. `tvg-shift` può essere sostituito da una correzione EPG manuale, senza sommare entrambe. I dati hanno identità separate per lista: due sorgenti con lo stesso `tvg-id` non condividono programmi. Il database personale non entra in `guide.json`, AURA o tessere Home; anche i loghi XMLTV vengono risolti privatamente e rispettano la politica dei loghi personali.

Stato, ultimo aggiornamento, canali associati, programmi e problemi sono distinti dallo stato della playlist. Aggiornamenti ogni sei ore durante l’uso dell’app, ETag/Last-Modified, HTTP 304 e pause HTTP 429 sono gestiti per feed. Un errore non cancella la guida valida né la riproduzione. **Svuota guida scaricata** rimuove la cache EPG della sorgente; eliminare la lista rimuove anche guida e avanzamenti video.

Nella **Guida TV** puoi scegliere un giorno precedente o successivo e cercare nell’intera guida personale. Le finestre visibili e il programma corrente vengono interrogati su disco, senza caricare tutto lo storico in memoria. Lo storico dipende dai dati effettivamente forniti dal servizio e da quelli già conservati.

## Video on demand

La voce **Video** compare nella barra inferiore dello smartphone e nella barra superiore della TV, tra **Canali** e **Radio**, solo se almeno una lista personale abilitata contiene VOD. Il controllo considera l'intero database, anche quando i video sono oltre l'anteprima iniziale, e rispetta il tipo di contenuti forzato nelle impostazioni. Disattivando, eliminando o riclassificando come diretta l'ultima sorgente VOD la voce scompare; se Video è aperto, l'app torna a **Canali**. La pagina Canali non contiene più un collegamento Video separato.

**Video** è una sezione della navigazione principale: logo e barra dell'app restano visibili e la voce Video risulta selezionata anche nelle raccolte e nelle schede dei titoli. Passando a un'altra sezione e riaprendo Video si ritrovano la pagina e i filtri precedenti. La home mostra **Continua a guardare**, **La mia lista**, **Film**, **Serie**, **Dalle tue liste** e un'anteprima delle categorie disponibili. Le raccolte vuote vengono omesse. **Mostra tutto** apre una griglia con scorrimento continuo, ricerca e blocchi da 50 titoli; il selettore **Categorie** permette di raggiungere tutti i gruppi, anche quelli fuori dalle anteprime della home.

Selezionare una copertina apre la scheda del titolo. **Guarda** o **Riprendi** avvia il player esistente; **Dall’inizio** azzera la ripresa di quel video. Titolo, trama, anno, durata e genere vengono mostrati quando disponibili. Il ritorno dal player conserva la scheda e la griglia di provenienza. Le posizioni delle raccolte vengono ricordate anche riaprendo Video e ricreando l'attività. Sul telefono la home Video non mostra la freccia **Indietro**: la barra principale permette di cambiare sezione; nelle raccolte e nelle schede la freccia resta disponibile per tornare al livello precedente. Le colonne si adattano all'orientamento e alla dimensione del testo; in orizzontale i controlli secondari sono raggiungibili da **Filtri**.

**Continua a guardare** usa lo storico privato già salvato: soltanto video non completati, iniziati da almeno cinque secondi e ancora disponibili in sorgenti abilitate. Gli episodi mantengono la propria identità e posizione. **La mia lista** riutilizza i preferiti dei video; le serie possono essere salvate come raccolte, separate per sorgente. Sono consentite fino a 5.000 serie salvate. La disattivazione della sorgente ne nasconde i contenuti; l'eliminazione rimuove anche storico, riferimenti della serie e immagini memorizzate per quella lista.

La presentazione distingue film, episodi e video generici dai dati dichiarati, dai percorsi e dai gruppi, senza cambiare la classificazione diretta/VOD descritta sotto. `series-title`, `series-name`, `show-title`, `tvg-series` e nomi come `Titolo S01E02` o `Titolo 1x02` possono identificare una serie. `season` / `season-number` ed `episode` / `episode-number` specificano l'ordine. `series-id` / `show-id` ancorano il raggruppamento quando dichiarati. Le serie restano separate per sorgente. Una voce indicata come episodio ma senza il nome della serie resta consultabile singolarmente; i VOD generici restano disponibili in Tutti e Dalle tue liste.

Le schede leggono `plot` / `description` / `tvg-description`, `genre` / `genres`, `year` / `release-year`. Per le copertine sono riconosciuti `poster`, `poster-url`, `tvg-poster`, `cover`, `cover-url`, con ripiego su `tvg-logo`; per gli sfondi `backdrop`, `backdrop-url`, `fanart`, `fanart-url`, `tvg-background`. Gli indirizzi relativi vengono risolti sul documento originale. Immagini assenti o non accessibili hanno un segnaposto leggibile, senza impedire la riproduzione. Le anteprime non aprono i flussi video.

Le immagini VOD hanno una cache privata dedicata, condivisa tra le schermate: fino a 24 MiB in memoria e 64 MiB su disco, con decodifica alla dimensione in pixel del riquadro e limite di 1280 px sul lato maggiore. Cache disco e decodifica non aspettano i tre posti disponibili per i download. Un'immagine scaduta viene mostrata mentre si tenta l'aggiornamento; richieste contemporanee della stessa immagine condividono il download, anche per dimensioni diverse. La chiusura dell'ultimo osservatore annulla la richiesta di rete, mantenendo la cache limitata. Mantengono il consenso HTTP delle immagini e gli header/server autorizzati per i loghi, distinti da quelli di video e licenze. Trame e immagini dipendono dai dati forniti dalla lista; non viene interrogato un catalogo cinematografico esterno.

Il primo accesso prepara un indice derivato a blocchi, in transazione e con avanzamento visibile. La preparazione è condivisa per sorgente: cambiare pagina non la fa ripartire; modificare o eliminare la sorgente interrompe quella superata. Titoli, rappresentanti delle serie, conteggi degli episodi e ordinamenti vengono preparati una volta. Metadati e gruppi sono conservati senza copie superflue; l'indice non cambia gli ID di riproduzione. Errori o limiti di spazio conservano la lista originale e i limiti di 1 GiB per database e 2 GiB complessivi restano applicati.

Le griglie conservano al massimo tre blocchi da 50 metadati vicini. Il repository condivide una cache aggiuntiva limitata a 600 metadati, 40 pagine e 64 conteggi; modifica della sorgente e dei filtri invalida i dati interessati. Le anteprime della home sono limitate a 16 titoli per raccolta, non richiedono il conteggio completo e compaiono progressivamente; le categorie fuori schermo vengono lette quando servono. Il dettaglio mostra subito i dati selezionati e completa trama e informazioni estese in background. Tornare a una pagina valida riusa dati, immagini, focus e scorrimento; l'avanzamento non invalida le raccolte statiche. Query abbandonate vengono interrotte tramite `CancellationSignal`, con protezione delle letture per sorgente.

Gli ordinamenti **Titolo A–Z** e **Aggiunti di recente** si applicano anche a tutte le liste insieme. La data di aggiunta viene conservata durante gli aggiornamenti; per gli archivi precedenti senza quella informazione non viene inventata una data recente. Le misure interne registrano soltanto nomi delle fasi, contatori e durate, senza testo delle query, URL, header o credenziali.

**Lettura e contenuti → Tipo dei contenuti** permette di mantenere **Automatico** o forzare tutta la lista come dirette o video. Il tipo forzato ha precedenza sul riconoscimento e sulle regole dei gruppi; i riferimenti a liste incluse rimangono strutturali.

Con **Automatico**, la modalità **Prudente** è predefinita. La classificazione segue questo ordine:

| Indicazione | Comportamento |
| --- | --- |
| Regola manuale per un gruppo | Imposta Dirette o Video per le voci di quel gruppo, prima delle indicazioni del file |
| Attributi `type` / `content-type` | VOD per `vod`, `movie`, `movies`, `film`, `films`, `video`, `series`, `episode`, `episodes`, `tvshow`; diretta per `live` e `linear` |
| `media` | `true`, `1`, `yes` indicano VOD; `false`, `0`, `no` indicano diretta |
| Direttiva Kodi `#EXT-X-PLAYLIST-TYPE:VOD` | VOD nel profilo gruppi **Kodi**; prima delle voci vale per il documento, dentro o tra le voci vale soltanto per la voce successiva al marcatore |
| `media-dir` / `media-size` | VOD con cartella non vuota o dimensione positiva in byte |
| Durata EXTINF positiva | VOD anche senza altri attributi |
| Percorso strutturato del provider | VOD per `/movie/utente/password/123[.estensione]` o `/series/utente/password/123[.estensione]`, anche con un prefisso prima del percorso |

Nomi e valori riconosciuti non distinguono maiuscole e minuscole. Un attributo della voce prevale sul valore predefinito del documento; tra due dichiarazioni riconosciute della voce prevale `type`. Un valore MIME come `content-type="video/mp4"` non dichiara VOD. Le dichiarazioni esplicite di diretta bloccano le deduzioni successive; conflitti tra dichiarazioni vengono segnalati nello stato della lista senza includere URL o credenziali.

**Esteso** aggiunge una deduzione quando sono presenti **entrambi** un gruppo con parole come Film, Movies, Serie o VOD e un file con estensione `.mp4`, `.mkv`, `.avi`, `.mov`, `.m4v`, `.webm`, `.mpg` o `.mpeg`. Il solo gruppo o la sola estensione non bastano. Gruppi con Live, Diretta, Dirette o 24h, attributi di catch-up e percorsi live/timeshift/catchup/archive escludono questa deduzione. Per i percorsi dei provider si richiedono componenti distinti e un ID numerico finale: host, query e semplici sottostringhe non vengono usati. Il riconoscimento non contatta i flussi e non modifica gli indirizzi firmati.

**Regole per i gruppi** consente fino a 100 regole per lista. Puoi scrivere il nome esatto del gruppo oppure sceglierlo dall'intera lista, con ricerca e scorrimento continuo. I nomi sono confrontati senza distinguere maiuscole e minuscole; non sono ammessi duplicati. Se una voce appartiene a più gruppi con regole diverse, prevale **Dirette** e viene segnalato il conflitto. Le regole restano in bozza finché salvi **Lettura e contenuti**.

Riconoscimento e regole si applicano subito alle voci già salvate, senza un nuovo download quando i metadati conservati bastano. Il primo avvio aggiorna anche gli archivi precedenti: lettura a blocchi su disco, avanzamento visibile e salvataggio atomico di classificazione e impostazioni. ID, preferiti, guida associata e avanzamento dei video restano invariati. La riproduzione in corso conserva il proprio contesto; il nuovo tipo vale quando riapri il contenuto. Se manca spazio, l'aggiornamento automatico viene rinviato mantenendo consultabile la copia salvata.

Gli archivi precedenti non conservavano l'ambito originale del marcatore Kodi. Se una vecchia voce VOD non ha altre indicazioni sufficienti, viene conservata come VOD e **Stato e ultima importazione** indica che serve rileggere l'originale. Per una lista URL, **Aggiorna ora** richiede il documento completo senza riutilizzare ETag/Last-Modified; per un file occorre **Sostituisci file**. La rilettura applica il corretto ambito delle direttive mantenendo gli ID riconciliabili.

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
