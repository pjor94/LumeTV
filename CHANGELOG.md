# Changelog

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
