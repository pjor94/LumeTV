# Crediti e licenze

## TMDB · Informazioni VOD

Le schede online dei video usano dati e immagini di [The Movie Database](https://www.themoviedb.org). This product uses the TMDB API but is not endorsed or certified by TMDB.

Il logo TMDB proviene dal file ufficiale indicato in [docs/tmdb.md](docs/tmdb.md). La variante bianca nell’app mantiene le forme originali e segue le [condizioni di attribuzione](https://developer.themoviedb.org/docs/faq). Dati e immagini sono soggetti ai rispettivi diritti e alle condizioni TMDB; non sono distribuiti come parte del codice GPL dell’app.

## WorldLiveTV / WLTV Group

Il catalogo delle sorgenti e la logica delle integrazioni usate da Lume TV derivano dal lavoro di **WorldLiveTV e WLTV Group**, autori dell’add-on Kodi `plugin.video.wltvhelper`.

- Progetto originale: [WorldLiveTV/WorldLiveTV.github.io](https://github.com/WorldLiveTV/WorldLiveTV.github.io).
- Riferimento degli adattamenti: add-on **1.2.14**, hotfix **121417**.
- Revisione del repository: [`af57d4ccbac56f6464b16a9dfaf4f0d7fb03d20e`](https://github.com/WorldLiveTV/WorldLiveTV.github.io/tree/af57d4ccbac56f6464b16a9dfaf4f0d7fb03d20e).
- Licenza dichiarata: **GPL-3.0-or-later**.

**Avviso di modifica — 2026-10-25:** il progetto Lume TV ha modificato e adattato il lavoro upstream con un porting Kotlin/Android, player Media3, gestione del catalogo, sessioni, cache e guida TV. Questa è una versione modificata e indipendente, non l’add-on originale.

**Ulteriori modifiche — 2026-10-27:** guida TV nativa con programmazione delle prossime ore, navigazione temporale, descrizioni facoltative e gestione dei duplicati del palinsesto.

**Ulteriori modifiche — 2026-11-07:** integrazione La7 aggiornata alle pagine di La7 Play, con lettura della configurazione del player e dei nuovi parametri di autorizzazione DRM.

**Ulteriori modifiche — 2026-11-09:** interfacce dedicate a smartphone Android e TV nella stessa APK, riconoscimento del dispositivo, comandi touch e guida per canale sul telefono, con catalogo, risoluzione delle sorgenti e riproduzione condivisi.

**Ulteriori modifiche — 2026-11-15:** telecomando locale tra smartphone e TV, backup cifrati e ripristino selettivo, continuità di visione dei video, gestione di preferiti e canali nascosti, opzioni di riproduzione e timer.

Gli adattamenti Kotlin si trovano principalmente in `RaiResolver.kt`, `MediasetResolver.kt`, `La7Resolver.kt`, `StreamResolver.kt` e `DiscoveryResolver.kt`. Lume TV reimplementa queste integrazioni per il player Android e usa il servizio playlist WLTV per aggiornare il catalogo. L’origine e il checksum della copia inclusa sono registrati in `catalog/source.json` nel repository.

A WorldLiveTV, WLTV Group e ai collaboratori del progetto originale va il merito della raccolta e della manutenzione delle sorgenti. Lume TV è un progetto indipendente e non rappresenta un’app ufficiale WorldLiveTV.

## Radio WorldRadio

**Integrazione Android — 2026-11-11:** il catalogo radio italiano è fornito da WorldLiveTV / WLTV Group attraverso il modulo WorldRadio dell’add-on. Lume TV ne importa i dati con un’implementazione Kotlin indipendente e include una copia per il primo avvio in `app/src/main/assets/radios.json`. Origine, data e checksum sono registrati in `catalog/radio-source.json`; `tools/update_radio_catalog.py` rigenera la copia.

Gran parte degli indirizzi del catalogo passa dal servizio Radio Garden, che reindirizza ai flussi delle emittenti. Lume TV contatta direttamente queste sorgenti dal dispositivo. Nomi, programmi, loghi e audio restano dei rispettivi titolari; la licenza del software non concede diritti sui contenuti.

## Guida programmi

**EPGShare IT1 — integrazione Android del 18 novembre 2026:** il feed pubblico [epg_ripper_IT1.xml.gz](https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz) di [EPGShare01](https://epgshare01.online/) completa guida e loghi delle liste personali soltanto dopo i dati della lista e quelli Lume, riprendendo l’integrazione desktop del progetto. Feed e immagini vengono scaricati dal dispositivo nella cache locale e non sono inclusi nel pacchetto dell’app. La fonte è indicata nei dettagli dei programmi; loghi e palinsesti restano dei rispettivi titolari.

I palinsesti e parte dei loghi dei canali provengono dal servizio esterno [EPG-Guide](https://epg-guide.com/). Altri loghi provengono dagli indirizzi indicati nella playlist WLTV. Dati e immagini vengono scaricati direttamente dalle sorgenti e conservati nella cache del dispositivo; non sono inclusi nella distribuzione del codice. I marchi restano dei rispettivi titolari.

## Librerie

| Componente | Versione | Licenza e titolari |
| --- | --- | --- |
| AndroidX e Media3 | Versioni risolte in `third_party/sources.lock.json` | Apache-2.0; The Android Open Source Project e autori dei singoli componenti |
| Kotlin | 2.3.10 (compatibilità JDK 7/8: 1.9.10) | Apache-2.0; JetBrains e contributori |
| kotlinx.coroutines / serialization | 1.10.2 / 1.7.3 | Apache-2.0; JetBrains e contributori |
| OkHttp / Okio | 4.12.0 / 3.6.0 | Apache-2.0; Square e contributori |
| NewPipe Extractor | 0.27.1 | GPL-3.0-or-later; Team NewPipe, NewPipe e.V. e contributori |
| nanojson, fork TeamNewPipe | e9d656ddb49a412a5a0a5d5ef20ca7ef09549996 | Apache-2.0; The nanojson Authors e contributori |
| jsoup | 1.24.1 | MIT; Copyright (c) 2009-2026 Jonathan Hedley |
| Protocol Buffers Java Lite | 4.36.2 | BSD-3-Clause; Copyright 2008 Google Inc. |
| Mozilla Rhino | 1.8.1 | MPL-2.0 e avvisi aggiuntivi nel file upstream `Rhino-LICENSE.txt`; Mozilla e contributori |
| Guava / failureaccess | 33.3.1-android / 1.0.2 | Apache-2.0; Google e contributori |
| JetBrains annotations / JSpecify | 23.0.0 / 1.0.0 | Apache-2.0; rispettivi autori |
| JSR-305 / Error Prone annotations / J2ObjC annotations | 3.0.2 / 2.28.0 / 3.0.0 | Apache-2.0; rispettivi autori |
| Checker Framework qualifiers | 3.43.0 | MIT; Copyright 2004-present by the Checker Framework developers |
| desugar_jdk_libs_nio | 2.1.5 | GPL-2.0-only WITH Classpath-exception-2.0; Oracle, Google e contributori OpenJDK |
| desugar_jdk_libs_configuration_nio (R8) | 2.1.5 | BSD-3-Clause; Copyright (c) 2016, the R8 project authors |
| Public Suffix List, inclusa in OkHttp | Copia distribuita con OkHttp 4.12.0 | MPL-2.0; contributori Public Suffix List |
| XZ for Java | 1.9 | Pubblico dominio; Lasse Collin, Igor Pavlov, Brett Okken |
| ZXing Core | 3.5.4 | Apache-2.0; ZXing authors |
| ZXing Android Embedded | 4.3.0 | Apache-2.0; Journey Mobile, Inc. e contributori |
| JUnit, usato solo nei test | 4.13.2 | EPL-1.0; autori JUnit |

Le licenze si applicano ai rispettivi componenti e non ai contenuti delle emittenti. Il progetto conserva i copyright dei sorgenti upstream; i nomi sopra riassumono i titolari, senza sostituire gli avvisi dei singoli file. Gli avvisi originali sono conservati in `app/src/main/assets/DEPENDENCY_NOTICES.txt` e nell’app.

La risoluzione dei trailer usa NewPipe Extractor senza modifiche alla libreria, con un downloader e un player Android propri. Viene usato Rhino direttamente in modalità interpretata; il bridge desktop opzionale `rhino-engine` è escluso dalla build Android. I trailer vengono richiesti ai rispettivi host e non sono inclusi nell’APK o nei sorgenti. L’integrazione è indipendente da YouTube; la licenza del software non concede diritti sui video né modifica le condizioni dei servizi ospitanti.

Testi integrali nell’app, in **Opzioni → Licenze e avvisi**, e nei sorgenti in `app/src/main/assets/licenses/`: Apache 2.0, GPLv2 con Classpath Exception, BSD R8, MPL 2.0, dichiarazione XZ, informazioni aggiuntive OpenJDK. La EPL 1.0 dei test è inclusa nella stessa cartella. La Classpath Exception consente il collegamento con moduli indipendenti con licenze diverse; non elimina gli obblighi relativi al componente desugar.

### Sorgenti corrispondenti

Il progetto pubblica [codice sorgente](https://github.com/pjor94/LumeTV) e APK firmate nelle [release su GitHub](https://github.com/pjor94/LumeTV/releases). Ogni APK è accompagnata dall’archivio completo `LumeTV-<versione>-source.zip`, con gli script di compilazione/installazione e gli archivi upstream in `third_party/sources/`. Il workflow Android conserva gli archivi anche tra gli artefatti di [GitHub Actions](https://github.com/pjor94/LumeTV/actions). Versioni, revisioni, licenze, URL e checksum sono registrati in `third_party/sources.lock.json`. Istruzioni in `third_party/README.md`.

Chi ridistribuisce una propria APK deve fornire i sorgenti corrispondenti secondo le licenze applicabili, comprese le dipendenze richieste; il pacchetto deve corrispondere alla compilazione distribuita.

desugar_jdk_libs 2.1.5 proviene dalla revisione Google `73170c345e6a762fc6a1f0301bb15218850023ef`; la configurazione R8 dalla revisione `c331a820ddae7dea41f41a04375b784486d0f705`. Il componente desugar non è stato modificato manualmente; D8/R8 ne adatta e riduce il bytecode per l’APK. La Public Suffix List non è stata modificata: la copia modificabile, il generatore e gli avvisi MPL sono inclusi nei sorgenti OkHttp del tag `parent-4.12.0`. I sorgenti mantengono i copyright e le rispettive licenze.

## Lume TV

Copyright (c) 2026, contributori del progetto Lume TV.

Il codice, il simbolo, il lettering e le altre risorse originali di Lume TV sono distribuiti con licenza **GPL-3.0-or-later**. Il testo integrale è disponibile in `LICENSE` e nell’app, in **Opzioni → Licenze e avvisi**.

Lume TV è software libero: puoi ridistribuirlo e modificarlo secondo la GNU GPL versione 3 o successiva. È fornito senza garanzie nei limiti consentiti dalla legge; vedi le sezioni 15–17 della licenza. La licenza del software non autorizza utilizzi di contenuti o servizi di terzi. Segnalazioni sui diritti e richieste di rimozione: https://github.com/pjor94/LumeTV/issues/new. La raggiungibilità di un endpoint non prova un’autorizzazione; condizioni dei fornitori e limiti territoriali restano applicabili.

Gli stream sono forniti dalle rispettive emittenti. Nomi, marchi, loghi e contenuti delle emittenti appartengono ai loro titolari; la distribuzione dell’app non ne trasferisce i diritti.
