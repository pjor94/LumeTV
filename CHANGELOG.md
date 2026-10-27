# Changelog

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
