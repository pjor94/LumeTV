# Changelog

## 0.10.2 · Correzione riproduzione localplay — 2026-11-14

- Le sorgenti `localplay` usano formato e protezione restituiti dal resolver. Corretto il blocco «ClearKey personale richiede uno stream DASH con cifratura CENC» quando il provider restituisce HLS non protetto e la lista contiene una vecchia configurazione ClearKey. La precedenza è comune a tutte le route supportate, compresa Food Network.
- Le indicazioni statiche di formato, le licenze e le opzioni delle chiavi HLS restano conservate nella lista ma non sostituiscono la configurazione risolta dal provider. Restano applicati gli header per risorsa e i consensi HTTP; gli stream protetti mantengono il DRM e la licenza del resolver. Le voci con URL diretto continuano a usare la configurazione della lista, con le normali validazioni ClearKey/DASH.

Per i canali `localplay` già salvati basta installare l'aggiornamento e riaprire il canale; non occorre reimportare la lista. Interfaccia invariata.

Verifiche delle funzionalità: 418 test JVM superati, con tre controlli live facoltativi esclusi; 32 test Python e 104 controlli sui simulatori TV e smartphone. Verificate importazione, conservazione dei dati e creazione del media source HLS con liste e risposte sintetiche dei provider. Compilazioni Debug/Release e AndroidTest; Android Lint senza errori. I controlli automatici non avviano stream personali reali.

## 0.10.1 · Importazione M3U più tollerante — 2026-11-14

- Recupero delle configurazioni ClearKey locali senza `license_type` quando le chiavi sono valide e riconoscibili; le dichiarazioni DRM esplicite mantengono la precedenza. Header User-Agent abbreviati riconosciuti nelle proprietà comuni, manifest e stream; `file_type=mpd/m3u8` usato come indicazione di formato subordinata a MIME e manifest espliciti.
- Sorgenti `localplay` tipizzate per i canali Discovery supportati, con resolver condiviso con il catalogo integrato e alias MotorTrend/Turbo. Nessuna conversione del comando in un percorso HTTP; i comandi sconosciuti vengono esclusi con un motivo. Impostazioni e contesto di accesso restano separati per lista.
- Il filtro RSI LA1/LA2 si applica soltanto al catalogo integrato: le liste personali possono importarle normalmente.
- Direttive isolate a fine documento segnalate come residui, senza creare una falsa voce incompleta. Gli scarti mantengono precedenza sui separatori, con canale, riga e motivo conservati nel rapporto. Avvisi ripetuti raggruppati; `group-logo` identificato come metadato grafico conservato.
- Le copie importate con il lettore precedente restano disponibili e richiedono una rilettura completa per applicare la compatibilità estesa, anche con ETag invariato. Gli ID vengono riconciliati come nelle altre sostituzioni.
- Le righe estranee o decorative non interrompono più la lettura dell'intera lista. Gli URL HTTP/HTTPS assoluti senza `EXTINF` vengono recuperati con un nome provvisorio; le voci con metadati irrecuperabili o indirizzo mancante vengono escluse singolarmente.
- Direttive `KODIPROP`, `EXTVLCOPT` ed `EXTHTTP` riconosciute anche prima di `EXTINF`. Header, MIME, opzioni e configurazione DRM restano associati alla singola voce, senza passare alla successiva.
- Anteprima e ultima importazione riportano voci recuperate, righe ignorate e motivi con numero di riga, nelle pagine esistenti. Diagnostica e log non includono URL, credenziali o chiavi.
- Gli aggiornamenti del progresso già in coda non sovrascrivono più l'esito finale della verifica o il relativo errore.
- Gli aggiornamenti con voci strutturalmente incomplete, oppure con meno canali e righe estranee, conservano la copia precedente. La sostituzione resta possibile dopo verifica dell'anteprima dalla pagina **Sorgente**. Errori di trasporto, HTML/HLS e limiti delle risorse restano bloccanti.

Per le liste già salvate, usa **Aggiorna lista** oppure **Sostituisci file** per applicare il nuovo lettore e recuperare anche le voci prima escluse.

Verifiche delle funzionalità: 408 test JVM superati, con tre controlli live facoltativi esclusi; 32 test Python e 188 controlli sui simulatori TV e smartphone, con testo normale e al 130%. Compilazioni Debug/Release e AndroidTest; Android Lint senza errori. Liste sintetiche e risposte simulate dei provider, senza collaudi degli stream personali reali.

## 0.10.0 · Biblioteca Video e liste estese — 2026-11-13

### Video nella navigazione principale

- Voce **Video** nella barra inferiore dello smartphone e nella barra superiore della TV, tra Canali e Radio, solo quando almeno una lista abilitata contiene VOD. La disponibilità considera tutta la lista e le regole di classificazione, anche oltre l'anteprima iniziale.
- Biblioteca con copertine, raccolte **Film**, **Serie**, **Continua a guardare**, **La mia lista** e categorie delle sorgenti. Schede dei titoli con informazioni disponibili, stagioni ed episodi, ripresa e riavvio nello stesso player dell'app.
- Raccolte complete con griglie a scorrimento continuo, ricerca e ordinamenti globali. Pagina, filtri e posizione conservati tornando dal player, cambiando sezione, ruotando il telefono e ricreando l'attività.
- Home Video smartphone senza freccia Indietro; il comando rimane nelle raccolte e nelle schede. Se scompare l'ultima sorgente VOD abilitata mentre Video è aperto, ritorno a Canali e ripristino del focus TV.

### Prestazioni e riconoscimento VOD

- Anteprime della home progressive, categorie caricate quando diventano visibili, indice video compatto e riutilizzo di pagine e conteggi. Apertura delle schede dai dati selezionati e completamento dei dettagli in background, con focus e scorrimento conservati.
- Cache immagini condivisa tra schermate, download simultanei riutilizzati e decodifica adeguata ai riquadri. Copertine su disco disponibili anche con rete occupata e cancellazione delle richieste senza osservatori.
- Riconoscimento VOD anche con durata `-1`, tramite attributi `type` e `content-type`, convenzioni `media`, direttive Kodi e percorsi strutturati dei provider. Le dichiarazioni esplicite di diretta prevalgono sulle deduzioni automatiche.
- In **Lettura e contenuti**, modalità **Prudente** predefinita, modalità **Esteso** e regole manuali per i gruppi. Il tipo forzato dell'intera lista prevale sulle regole. Riclassificazione locale conservando ID, preferiti, avanzamento e accesso ai flussi.
- Corretto l'ambito delle direttive Kodi VOD per singola voce; gli archivi precedenti segnalano quando serve rileggere il file o aggiornare l'URL per recuperare le informazioni mancanti.

### Liste grandi e consultazione

- Fino a **500.000 voci lette per sorgente**, **un milione di voci importate complessive** e **256 MiB per documento**, con un massimo di 10 liste. Snapshot SQLite fino a 1 GiB per sorgente e 2 GiB complessivi.
- Catalogo con scorrimento continuo e righe riciclate su TV e smartphone, senza comandi di pagina. Ricerca, gruppi e selettori consultano il database completo mantenendo blocchi limitati in memoria; anche preferiti, zapping e ripristino evitano il caricamento di tutti i metadati.
- Filtri espandibili sul telefono, riepilogo di lista, gruppo e ricerca attivi, azzeramento dei filtri e memoria separata delle sezioni. Sulla TV, Sinistra dalla prima colonna raggiunge i controlli e Giù torna alla riga precedente.
- Selettori di gruppi e guida nella finestra dell'app, con ritorno al controllo di provenienza. Pannelli canali personali integrati con la navigazione esistente; salvataggio con fasi riconoscibili e messaggi della guida vuota legati allo stato disponibile.
- Copie valide conservate in caso di errori, interruzioni o limiti di spazio. Liste personali, guida e avanzamenti restano sul dispositivo, esclusi da AURA e dalla Home dei launcher.

Verifiche: 362 test JVM e 30 test Python superati, con tre controlli live facoltativi esclusi; compilazioni Debug/Release, Android Lint senza errori e controllo dei sorgenti delle dipendenze. Collaudi funzionali su simulatori TV e smartphone con dati sintetici locali, testo normale e al 130%, focus del telecomando, ricerca, preferiti, riproduzione, ricreazione e rotazione. Provati cataloghi fino a 500.000 voci per sorgente e un milione complessive. La compatibilità con i singoli servizi, codec e DRM dipende dal fornitore e dal dispositivo. Build di sviluppo delle stesse funzionalità installate anche sullo smartphone Android con la stessa firma, conservando i dati; i collaudi funzionali restano sui simulatori.

## 0.9.0 · Liste personali, guida e video — 2026-11-12

- Gestione delle liste tramite pagine interne nello stile di **Opzioni**: intestazione, sezioni, descrizioni e separatori su smartphone; elenco con dettaglio e focus su TV. Indietro conserva campi e posizione. Verifica, anteprima, errori, conferme ed eliminazione restano nella pagina; il selettore file usa quello di Android.
- Liste HTTP attivabili per singola sorgente, con consenso inizialmente disattivato e revocabile. Limiti portati a **64 MiB per documento, 50.000 voci per lista e 100.000 complessive**, fino a 10 liste. Importazione progressiva e interrompibile da URL e file.
- Archivi SQLite privati per sorgente, sostituzione atomica della copia valida e migrazione dai JSON della 0.8.0, conservando ID, preferiti e impostazioni. Errori di rete, formato o spazio conservano la copia precedente.
- Lettura M3U semplice o estesa, codifiche UTF-8, Windows-1252 e Latin-1, profili dei gruppi e intervallo di aggiornamento configurabile. Liste incluse con limiti condivisi e controllo dei cicli; metadati e indirizzi finali conservati per documento.
- Header da EXTVLCOPT, EXTHTTP, suffisso URL e KODIPROP, con accesso manuale e server autorizzati distinti per download, manifest, video, licenze DRM, chiavi HLS, loghi e guida. Cookie di sessione isolati per sorgente; credenziali rimosse nei passaggi verso origini diverse.
- **ClearKey DASH/CENC** con coppie KID:KEY, più chiavi, JSON/JWK o server licenze, e configurazioni Widevine personali. Traduzione delle direttive KODIPROP supportate in Media3, richieste di licenza Kodi e opzioni dedicate alle chiavi HLS AES-128. Le forme non supportate producono un motivo esplicito.
- Loghi relativi o autenticati, consenso HTTP delle immagini separato e cache isolata per sorgente e accesso. **Stato e ultima importazione** mostra conteggi, motivi degli scarti e informazioni del servizio.
- **Guida XMLTV personale** in XML, gzip e xz: lingua, fuso, correzione oraria, associazioni automatiche e manuali, accesso e consenso HTTP separati. Storico configurabile fino a sette giorni e 48 ore successive, ricerca paginata e database distinti per lista.
- **Catalogo Video** con filtri per lista e gruppo, ricerca e pagine da 50 elementi. Live, video on demand e archivio usano l’unico player del servizio; posizione e avanzamento restano privati, con seek, ripresa e riavvio. La fine di un video non avvia riconnessioni live.
- **Archivio e dall’inizio** per i servizi compatibili: catch-up default/append/shift, Flussonic, Xtream e URL da catchup-id. La guida propone replay e dall’inizio quando disponibili; **Torna alla diretta** lascia il replay. Fusi, disponibilità e margini sono configurabili; non vengono create registrazioni locali.
- Corretti l’apertura di un canale da collegamento mentre è visibile la gestione, il numero personale 9999 e la pulizia dei journal SQLite dopo la sostituzione degli snapshot. Avanzamento ed errori restano raggiungibili anche con tastiera e orientamento orizzontale.
- Liste, guida personale e avanzamenti restano sul dispositivo, esclusi da AURA e dalla Home dei launcher. I consensi Home/AURA del catalogo integrato e i controlli sulle firme restano separati.

Formati supportati, limiti e convenzioni escluse sono descritti in [Liste personali](docs/personal-playlists.md).

Verifiche: 276 test JVM superati e tre controlli di rete facoltativi esclusi; 30 test Python superati. Build Debug/Release e Android Lint completati senza errori; verificati i 58 archivi sorgente delle dipendenze.

Collaudo delle nuove pagine su simulatori Android TV 14 e smartphone Android 15: 190 controlli, con testo normale e al 130%, più 14 verifiche di selezione file. Verificata anche l'APK Release firmata: 48 controlli di aggiornamento dalla 0.8.0 ufficiale, senza disinstallazione, con conservazione dei preferiti, degli identificativi e delle impostazioni di accesso e migrazione dei JSON a SQLite. Le prove usano dati e servizi sintetici; la compatibilità con ogni fornitore di liste, DRM, VOD e archivio richiede il relativo servizio e non è garantita da questo collaudo.

Per aggiornare dalla 0.8.0: **Opzioni → Aggiornamenti → Controlla ora**, dopo la pubblicazione. Il codice Android passa da 19 a 20 e la stessa chiave di firma conserva dati e impostazioni. L’integrazione AURA di produzione continua a richiedere la variabile `AURA_TRUSTED_CERTIFICATE_SHA256`; senza configurazione il provider rimane chiuso.

## 0.8.0 · Liste personali e canali nella Home — 2026-11-12

- **Opzioni → Le mie liste** su smartphone e Android TV: importazione di liste M3U/M3U8 UTF-8 da URL HTTPS o file, verifica con anteprima, rinomina, sostituzione, aggiornamento, attivazione ed eliminazione.
- Filtri per lista e gruppo nel catalogo. I preferiti comprendono le diverse sorgenti, mantenendo la distinzione tra TV e radio; il cambio canale resta nella sorgente in riproduzione anche dai controlli di sistema.
- Identificativi personali persistenti, cache atomica per sorgente e controllo delle revisioni: gli aggiornamenti conservano la diretta e le risposte tardive non ripristinano liste eliminate o configurazioni precedenti.
- URL e header di riproduzione delle liste restano nell’archivio privato. I metadati personali sono esclusi dal provider AURA e dalla pubblicazione nella Home.
- Limiti delle liste: 10 sorgenti, 5.000 voci per lista, 20.000 voci complessive e 8 MiB per documento. Le sorgenti personali della release richiedono HTTPS; XMLTV personale, HTTP generico, VOD/catch-up e direttive DRM aggiuntive non sono inclusi.
- **Opzioni → Canali nella Home** pubblica una riga Lume TV nei launcher Android TV compatibili, tramite le API standard da Android 8. Si possono seguire i preferiti del catalogo integrato oppure scegliere manualmente fino a 24 canali.
- Tessere con poster locali, programma in onda quando disponibile e collegamenti diretti al player Lume. Aggiornamenti da cache tramite JobScheduler, senza avviare rete, resolver o video in background.
- Consenso Home separato da AURA, rispetto delle tessere e delle righe rimosse dall’utente, ripristino esplicito e rimozione alla revoca. Radio e liste personali sono escluse dalla Home; il contratto AURA v1 e le verifiche delle firme restano invariati.
- Corretta la selezione manuale dopo **Deseleziona tutti**: le caselle del selettore Android restano coerenti anche quando la lista viene ridisegnata.
- Contratto documentato per i launcher e strumenti di collaudo limitati agli emulatori. La visibilità dipende dal launcher; scheda Live, Watch Next e raccomandazioni generali di Google TV non vengono abilitate da questa funzione.

Verifiche: 203 test JVM superati e tre controlli di rete facoltativi esclusi; 30 test Python superati. Build Debug/Release e Android Lint completati senza errori. Compilazione e firma della release con incremento del codice versione da 18 a 19, per consentire l’aggiornamento dalla 0.7.0.

Collaudo funzionale su emulatori Android TV 14 e smartphone Android 15, con liste, palinsesti e video sintetici locali: importazione, aggiornamento, identificativi persistenti, preferiti, zapping per sorgente, revoca, ricreazione del processo e testo al 130%. Verificate anche la pubblicazione nel provider Android TV e l’apertura del canale dal launcher Projectivy 4.73, con primo frame confermato. I dispositivi fisici non sono inclusi nel collaudo funzionale di queste novità.

Per aggiornare dalla 0.7.0: **Opzioni → Aggiornamenti → Controlla ora**, dopo la pubblicazione. La stessa chiave di firma conserva dati e impostazioni. L’integrazione AURA di produzione richiede la variabile GitHub `AURA_TRUSTED_CERTIFICATE_SHA256`; in sua assenza il provider resta chiuso, come nella release precedente. La funzione Canali nella Home ha un consenso indipendente.

## 0.7.0 · Radio, riproduzione continua e nuove Opzioni — 2026-11-11

- Catalogo italiano WorldRadio di WLTV, con copia locale, aggiornamento separato, ricerca per nome e città, preferiti e recenti distinti dalla TV.
- Un solo player gestito da un servizio Media3 per entrambe le interfacce, con recupero della connessione e configurazione HTTP adatta anche a MP3/AAC continui.
- Smartphone: radio a schermo spento, controlli multimediali di sistema e Bluetooth, metadati ICY/ID3 quando disponibili, mini player nel catalogo, Picture in Picture per le dirette TV e modalità TV solo audio.
- Smartphone: scelta Automatica / Risparmio dati e audio offload sui dispositivi compatibili. I flussi a qualità singola mantengono il bitrate fornito dall’emittente.
- Smartphone: Opzioni diventa una pagina dedicata con valori correnti, ritorno al catalogo precedente e mini player. Pagina e posizione di scorrimento vengono conservate alla ricreazione.
- Android TV: pagina Opzioni con lo stesso menu superiore delle altre pagine, valori correnti, focus per telecomando, descrizione della voce selezionata e anteprima della riproduzione. Indietro torna al catalogo o alla guida precedente.
- Android TV: schermata radio utilizzabile con telecomando e anteprima interna della diretta durante la consultazione di catalogo e guida. Uscendo dall’app, TV e radio si interrompono.
- Android TV: corretto il ritorno alla lista senza anteprima aprendo un altro canale. Durante il cambio viene ignorato lo Stop sintetico senza timestamp ricevuto sulla Bravia; Stop del telecomando e Interrompi restano attivi.
- Nessun timer di spegnimento e nessuna integrazione Android Auto / Android Automotive.

Verifiche: 168 test JVM, di cui 165 superati e tre controlli di rete facoltativi esclusi; 30 test Python superati. Build Debug/Release e Android Lint completati senza errori.

Collaudo su emulatori Android TV 14 e smartphone Android 15: radio e riproduzione continua, pagine Opzioni con testo al 130%, rotazione e ricreazione del processo. Il cambio canale è coperto da 31 verifiche, inclusa la riproduzione del comando Stop anomalo prima della correzione. Pagina Opzioni e correzione del cambio canale verificate anche su Sony Bravia con Android 12. Consumi e audio offload non sono stati misurati su uno smartphone fisico.

## 0.6.2 · Avvisi per le nuove versioni — 2026-11-11

- Popup automatico per le nuove versioni compatibili, condiviso tra TV e smartphone: compare nel catalogo e attende il ritorno dalla diretta. **Aggiorna** apre la schermata esistente; **Più tardi** o Indietro rinviano l’avviso al prossimo avvio.
- Rinvio conservato durante standby, ricreazione della schermata e cambio di interfaccia; focus iniziale su **Più tardi** e ripristino della selezione alla chiusura.
- Controlli delle nuove versioni ogni 30 minuti durante l’uso, con attesa separata di un’ora dopo gli errori e rispetto delle pause richieste da GitHub, anche dopo la riapertura dell’app.

Verifiche: 145 test JVM e 29 test Python superati; tre controlli di rete facoltativi esclusi. Build Debug/Release e Android Lint completati senza errori.

Collaudo su emulatori Android TV 14 e smartphone Android 15: 48 controlli del popup e 54 di non regressione su uscita e UI mobile, con metadati di aggiornamento fittizi, video sintetico locale e testo al 130%. Nessuna APK scaricata dal collaudo dei popup; dispositivi fisici non provati.

Per aggiornare dalla 0.6.1: **Opzioni → Aggiornamenti → Controlla ora**. Preferiti e impostazioni vengono conservati negli aggiornamenti con la stessa firma. Su Android 9 e 10, le versioni fino alla 0.6.0 richiedono invece l’installazione manuale dell’APK ufficiale 0.6.2 sopra quella esistente, senza disinstallare l’app.

## 0.6.1 · Aggiornamenti su Android 9 e 10 — 2026-11-10

- Corretto il falso errore di firma durante gli aggiornamenti in-app su Android 9 e 10, inclusi i box Android TV: la lettura dei certificati dell’APK richiede entrambi i flag del PackageManager.
- Aggiunti otto test di regressione con il parser APK Android su API 27, 28, 29 e 35 tramite Robolectric: accettazione della stessa firma e rifiuto di un certificato diverso.

Verifiche: 137 test JVM e 29 test Python superati; tre controlli di rete facoltativi esclusi dalla suite ordinaria. Build Debug/Release, Android Lint e verifica dei sorgenti delle dipendenze completati. Il test con il codice precedente riproduce il falso errore sulle API 28 e 29.

**Android 9 e 10:** installa manualmente l’APK ufficiale 0.6.1 sopra la versione esistente, senza disinstallare l’app. Preferiti e impostazioni vengono conservati. Questa prima installazione manuale è necessaria perché il controllo difettoso è nella vecchia versione; gli aggiornamenti successivi potranno usare il controllo corretto.

## 0.6.0 · Smartphone Android e Android TV — 2026-11-09

- Una sola APK con UI smartphone e TV: riconoscimento automatico del dispositivo e scelta persistente Automatica / Smartphone / TV nelle Opzioni. Tablet e altri dispositivi mantengono la UI TV in automatico.
- UI smartphone con navigazione inferiore, ricerca touch, preferiti espliciti, guida per canale e programmi Ora / Tra poco / Stasera con dettagli e avvisi locali.
- Comandi touch durante la diretta: pausa/ripresa, cambio canale, preferiti, elenco canali e schermo intero. La rotazione conserva il player e lo stato di pausa.
- Catalogo, guida, loghi, preferiti, resolver, riproduzione DRM, recupero della connessione e aggiornamenti condivisi tra le due interfacce. Collegamenti esistenti conservati.
- Test della selezione dell’interfaccia e collaudo smartphone con palinsesto e video sintetici locali.
- README aggiornato per Android TV e smartphone Android; guide illustrate separate per telecomando e comandi touch, con un indice comune.
- Preparazione locale degli allegati di rilascio con inclusione esplicita dei nuovi sorgenti, senza commit, tag o pubblicazione. La pubblicazione resta vincolata a un tag corrispondente e a un checkout pulito.

Verifiche: 129 test JVM e 29 test Python superati; tre controlli facoltativi esclusi dalla suite ordinaria. Build Debug/Release, Android Lint e verifica dei 58 archivi sorgente delle dipendenze completati.

Collaudo funzionale del codice su emulatori Android: 30 controlli smartphone e 85 di non regressione TV, con palinsesto e video sintetici locali. Verificati selezione automatica dell’interfaccia, scelta manuale persistente, preferiti, pausa, rotazione, guida, promemoria e testo al 130%. Smartphone e TV fisici, dirette DRM su smartphone e sintesi vocale TalkBack non sono stati verificati in questo collaudo.

Verificata anche l’APK Release firmata: 16 controlli di aggiornamento dalla 0.5.3, senza disinstallazione, su emulatori Android TV 14 e smartphone Android 15. Confermati firma compatibile, apertura automatica dell’interfaccia corretta, conservazione dei preferiti e assenza di crash Java durante la prova.

Per aggiornare dalla versione 0.5.3: **Opzioni → Aggiornamenti → Controlla ora**, dopo la pubblicazione della release. Gli aggiornamenti con la stessa firma conservano preferiti e impostazioni; l’interfaccia viene scelta automaticamente in base al dispositivo.

## 0.5.3 · Telecomando e leggibilità — 2026-11-09

- Il catalogo aggiorna il programma in onda ogni 30 secondi, conservando focus e posizione senza ricaricare le schede.
- La pressione di OK ha una conferma visiva distinta dal focus su pulsanti, schede e programmi. Le animazioni del focus restano brevi, interrompibili e rispettano le animazioni disabilitate nelle impostazioni della TV.
- Nella Guida, le frecce orizzontali restano sulla riga dei comandi; le frecce verticali raggiungono l’azione Guarda. Le celle e i loghi vengono riutilizzati durante gli spostamenti nella stessa fascia.
- Orari, indicazioni del telecomando e altri testi secondari ingranditi; contrasto maggiore sulle schede focalizzate e spazio per i numeri canale con testo al 130%.
- Pulsanti con ruolo accessibile, selezione esposta ai servizi di accessibilità e preferiti inclusi nelle descrizioni delle schede. Focus visibile anche sul comando per chiudere la ricerca.
- Collaudo ampliato per aggiornamento dei programmi, pressione di OK, navigazione della Guida, continuità della diretta nell’elenco canali e ripristino dei promemoria.

Verifiche: 124 test JVM e 28 test Python superati; tre controlli facoltativi esclusi dalla suite ordinaria. Build Debug/Release, Android Lint e verifica dei sorgenti delle dipendenze completati.

Collaudo su emulatore Android TV 14 a 1080p: 85 controlli con palinsesto e video sintetici locali, inclusi testo al 130%, animazioni disabilitate, preferiti, promemoria e uscita dall’app. Questa versione non è stata provata su una TV fisica; la sintesi vocale TalkBack non è stata verificata.

Per aggiornare dalla versione 0.5.2: **Opzioni → Aggiornamenti → Controlla ora**. Preferiti e impostazioni vengono conservati.

## 0.5.2 · Ripristinata la diretta La7 — 2026-11-07

- Aggiornata la risoluzione di La7 e La7 Cinema al nuovo sito La7 Play: configurazione del player, endpoint del token e licenza Widevine letti dalla nuova pagina della diretta.
- Aggiunti test di regressione per il formato della pagina e un controllo di rete facoltativo dedicato ai due canali.

Verifiche: 124 test JVM e 28 test Python superati; tre controlli facoltativi esclusi dalla suite ordinaria. Controllo di rete La7 eseguito separatamente con token disponibili e manifest DASH HTTP 206 per entrambi i canali. Lint, build Debug/Release e verifica dei sorgenti delle dipendenze completati.

Collaudo su emulatore Android TV 14 con dirette reali: video, audio e autorizzazione Widevine per La7 e La7 Cinema, osservati per almeno 45 secondi ciascuno, cambio canale e riapertura dell’app. Brevi buffering recuperati automaticamente, senza errori di riproduzione. Questa versione non è stata provata su una TV fisica.

Per aggiornare dalla versione 0.5.1: **Opzioni → Aggiornamenti → Controlla ora**. Preferiti e impostazioni vengono conservati.

## 0.5.1 · Uscita dall’app — 2026-11-07

- Pulsante **Esci** nella navigazione in alto, dopo Opzioni, disponibile in Canali, Cosa guardare e Guida TV.
- Conferma di chiusura anche premendo Indietro dalla Home di Canali, con Annulla selezionato all’apertura. Indietro nella conferma annulla l’uscita; preferiti, impostazioni e ultimo canale guardato restano salvati.
- Da Guida TV e Cosa guardare, Indietro torna prima a Canali dopo aver chiuso eventuali dettagli o ricerca; dal catalogo non riprende più automaticamente la diretta.
- Player e sessione multimediale rilasciati prima della chiusura del task, evitando il conflitto alla riapertura immediata dell’app.

Verifiche: 117 test JVM e 28 test Python superati; due test di rete facoltativi esclusi. Collaudo su emulatore Android TV con 24 controlli, inclusi focus, annullamento, selezione numerica in attesa, ritorno dalla diretta sintetica, chiusura del task e riapertura immediata. Build Debug/Release e Android Lint completati. Questa versione non è stata provata su una TV fisica.

Per aggiornare dalla versione 0.5.0: **Opzioni → Aggiornamenti → Controlla ora**. Preferiti e impostazioni vengono conservati.

## 0.5.0 · Cosa guardare, vista Focus — 2026-11-04

- Nuova schermata nativa Focus: descrizione e azioni a sinistra, programmi selezionabili a destra con il telecomando.
- Viste Ora, Tra poco (90 minuti) e Stasera (20:30–24:00), ricerca in titoli, descrizioni e canali e filtro preferiti.
- Preferiti in evidenza e indicazione dei programmi che stanno terminando; selezione conservata dopo aggiornamenti, standby e ritorno dalla diretta.
- Promemoria locali per i programmi futuri, mostrati due minuti prima mentre Lume è aperta, con apertura del canale solo su conferma.
- Navigazione principale Canali / Cosa guardare / Guida TV; filtri Tutti, Rai, Mediaset e Preferiti all’interno di Canali.
- Collaudo della guida basato sulla fascia oraria accessibile, indipendente dal cambio di minuto dell’orologio.

Verifiche: 117 test JVM e 28 test Python superati; due test di rete facoltativi esclusi. Collaudo su emulatore Android TV con 21 controlli Focus e 17 controlli della Guida, inclusi ricerca, preferiti, promemoria, pressioni rapide, ritorno dalla diretta sintetica e caratteri ingranditi. Build Debug/Release e Android Lint completati. Questa versione non è stata provata su una TV fisica.

## 0.4.0 · guida TV delle prossime ore — 2026-10-27

- Guida TV nativa a griglia con scala temporale condivisa, cinque righe quando lo spazio lo consente, loghi e focus immediato nello stile Lume.
- Programmi delle prossime ore, scorciatoie Adesso/Stasera, filtro Tutti/Preferiti e scheda con descrizione, quando fornita dal palinsesto.
- Navigazione con riferimento orario stabile tra canali, ripristino dopo la diretta e aggiornamenti senza riposizionare la consultazione.
- Celle con durata reale, trattamento dei vuoti, rimozione dei duplicati e gestione delle sovrapposizioni del feed.
- In Guida, CH+/CH− sfoglia i canali e i numeri selezionano la riga; la riproduzione richiede una scelta esplicita.
- Cache precedente compatibile; descrizioni limitate e facoltative, senza nuove dipendenze.
- Descrizione del programma anche nelle informazioni del player, con tre righe visibili, scorrimento ↑/↓ per il testo completo e tempo di lettura esteso aprendo il pannello con OK o INFO.
- Nuova guida utente in italiano, schermate aggiornate e galleria di catalogo, guida, dettagli e informazioni durante la diretta.

Collaudo su emulatore Android TV: 17 verifiche superate, inclusi vuoti del palinsesto, programmi brevi, dettagli, preferiti, pressioni rapide, ritorno dalla riproduzione di un video sintetico e caratteri ingranditi. Verificati anche test JVM, Lint e build Debug/Release. Questa modifica non è stata provata su una TV fisica.

Informazioni del player: 19 verifiche su emulatore con video sintetico, inclusi scorrimento del testo, continuità della riproduzione, tempi di lettura, assenza di descrizione, cambio programma e caratteri ingranditi. Controllati anche OK, selezione numerica e ripristino della descrizione dopo un messaggio sulla build finale.

Per aggiornare dalla versione 0.3.3: **Opzioni → Aggiornamenti → Controlla ora**. Preferiti e impostazioni vengono conservati.

[Guida utente](https://github.com/pjor94/LumeTV/blob/v0.4.0/docs/GUIDA-UTENTE.md) · [Schermate](https://github.com/pjor94/LumeTV/tree/v0.4.0/docs/screenshots)

## 0.3.3 · canali durante la diretta — 2026-10-27

- Elenco canali semitrasparente sulla sinistra della diretta, aperto con freccia sinistra, con gli stessi colori, loghi e focus dell’interfaccia Lume TV.
- Scorrimento con il telecomando senza interrompere la riproduzione; OK cambia diretta e chiude l’elenco, freccia destra o Indietro torna alla visione.
- Apertura sul canale attivo, filtro Tutti/Preferiti, programma in onda e avanzamento aggiornati durante la consultazione; indicazione del nuovo comando nel pannello informazioni.
- Focus stabile con pressioni rapide, canali fuori schermo e preferiti vuoti; comandi leggibili anche sopra le scritte del video e rispetto delle animazioni disabilitate sulla TV.

Collaudo su emulatore Android TV: 15 verifiche superate, inclusi continuità della riproduzione, cambio canale, filtri, tasti numerici e CH+/CH−. Anteprima controllata anche su una diretta reale.

Per aggiornare dalla versione 0.3.2: **Opzioni → Aggiornamenti → Controlla ora**.

## 0.3.2 · loghi dei canali e aggiornamenti dall’app — 2026-10-27

- Loghi dei canali nel catalogo, nella guida TV e nel pannello della diretta, con superfici adattate al contrasto, proporzioni preservate e spazio vuoto quando il logo non è disponibile.
- Download diretto dalle sorgenti originali, cache locale con verifica settimanale, richieste condivise e limiti su immagini e redirect; riuso del marchio Rai 3 per le edizioni TGR senza condividere i palinsesti.
- Controllo automatico delle release GitHub all’avvio e ogni sei ore durante l’uso, con avviso nel catalogo e schermata Opzioni → Aggiornamenti.
- Download annullabile e installazione guidata con telecomando tramite il programma di installazione Android.
- Verifica di dimensione, checksum, package, versione e certificato dell’APK prima dell’installazione; rifiuto di downgrade, build incompatibili e firme diverse.
- Metadati `update.json` generati e verificati dalla pipeline di release, inclusi nei checksum e negli allegati pubblicati.
- Versioni numeriche senza suffisso di sviluppo, pubblicate come normali release GitHub anche nella serie `0.x`.

Per aggiornare dalle build che includono già l’aggiornatore: **Opzioni → Aggiornamenti → Controlla ora**. Chi usa ancora `0.3.0-dev` deve installare manualmente questa APK una prima volta.

## 0.3.0-dev · integrazione AURA e release APK — 2026-10-27

- Ripristinata la pipeline GitHub Actions per compilare, firmare e pubblicare l’APK sui tag di versione, anche con avvio manuale su un tag esistente.
- Ogni release include sorgenti corrispondenti e dipendenze verificate, impronta del certificato e checksum SHA-256. Test, Lint e verifica delle licenze precedono la pubblicazione.
- Certificato AURA autorizzato configurabile per le release tramite la variabile repository `AURA_TRUSTED_CERTIFICATE_SHA256`.

- Collegamento locale facoltativo e revocabile in Opzioni, con provider di soli metadati protetto da UID, package e certificati autorizzati.
- URI per canale, ultimo canale, catalogo, preferiti, guida e consenso. Catalogo e guida non avviano una diretta; richieste successive annullano quelle precedenti.
- Snapshot limitato a 500 canali e 512 KiB con preferiti, programma attuale/successivo e timestamp dell’ultimo playback confermato. Revocando il consenso, i dati personali non vengono più esposti.
- Collaudo AURA/Lume su emulatore completato: 12 verifiche passate, inclusi playback di video sintetici locali a freddo/a caldo, ultimo canale, richieste rapide, consenso/revoca, autorizzazioni, guida al cambio programma, ricerca e ritorno del focus. Emittenti reali e Sony Bravia non provate per questa integrazione.
- Contratto normalizzato per il client: testi senza caratteri di controllo, nomi fino a 160 caratteri, titoli fino a 300 e numeri fuori intervallo esposti come null. Test di stress Unicode e limiti del payload.

- Recupero automatico della diretta con tre tentativi distanziati, nuova risoluzione del flusso e attesa del ritorno della rete. Catalogo, cambio canale e standby annullano i tentativi; DRM, formati incompatibili e limiti del servizio non causano tentativi automatici.
- Pannello della diretta con orari, barra di avanzamento, minuti rimanenti e programma successivo; aggiornamento durante la visualizzazione e permanenza di sei secondi.
- Menu Opzioni con modalità CH+/CH− su tutti i canali o solo sui preferiti, salvata sul dispositivo. I numeri continuano a selezionare tutti i canali.
- Ultimo canale salvato solo quando la riproduzione inizia; un solo preferito non viene riaperto a ogni pressione di CH+/CH−.
- Nuove funzioni provate su Sony Bravia e su emulatore Android TV.

## 0.2.2 — 2026-10-25

- Distribuzione pubblica dei soli sorgenti: rimossa la pubblicazione automatica delle release e l’upload delle APK da GitHub Actions; restano gli archivi sorgente e i report di verifica.
- Adattamenti di WorldLiveTV modificati dal progetto Lume TV: porting Kotlin/Android, player Media3, catalogo, cache e guida TV. Riferimento upstream e avvisi GPL conservati nelle attribuzioni.
- Rimosse le quattro voci RSI La1/La2 e impedita la reintroduzione da catalogo, cache e aggiornamenti automatici.
- Aggiunti GPLv2 con Classpath Exception, Apache 2.0, MPL 2.0, dichiarazione XZ e avvisi delle dipendenze; consultazione dall’app.
- Archivi dei sorgenti corrispondenti con dipendenze, revisioni e checksum verificati.
- Documentati funzionamento degli endpoint, limiti di garanzia e procedura di segnalazione/rimozione.

## 0.2.1

- Il pulsante ↻ aggiorna catalogo e guida TV.
- La guida viene ricontrollata al ritorno nell’app e durante l’utilizzo, con nuovi tentativi dopo gli errori.
- Cache dei programmi aggiornata ogni sei ore e salvata in modo atomico.
- Stato di caricamento, copertura della guida e attese del servizio visibili nell’interfaccia.
- Associazioni EPG per Rai Radio 2 e TGR Veneto.
- Build, firma e pubblicazione delle APK tramite GitHub Actions.

## 0.2.0

- Nuova identità grafica, icona e banner Android TV.
- Navigazione compatta, schede canale e focus del telecomando ridisegnati.
- Pannello temporaneo con le informazioni della diretta.
- Aggiornamento automatico del catalogo, con validazione e cache locale.
- Animazioni che rispettano l’impostazione di sistema.

## 0.1.0

- Prima versione Android TV con player Media3 e supporto HLS, DASH e Widevine.
- Catalogo WLTV con Rai, Rai/TGR, Mediaset e altre sorgenti compatibili.
- Preferiti, selezione numerica, cambio canale e apertura dell’ultimo canale.
- Guida con programma attuale e successivo.
