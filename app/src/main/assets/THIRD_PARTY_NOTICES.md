# Crediti e licenze

## WorldLiveTV / WLTV Group

Il catalogo delle sorgenti e la logica delle integrazioni usate da Lume TV derivano dal lavoro di **WorldLiveTV e WLTV Group**, autori dell’add-on Kodi `plugin.video.wltvhelper`.

- Progetto originale: [WorldLiveTV/WorldLiveTV.github.io](https://github.com/WorldLiveTV/WorldLiveTV.github.io).
- Riferimento degli adattamenti: add-on **1.2.14**, hotfix **121417**.
- Revisione del repository: [`af57d4ccbac56f6464b16a9dfaf4f0d7fb03d20e`](https://github.com/WorldLiveTV/WorldLiveTV.github.io/tree/af57d4ccbac56f6464b16a9dfaf4f0d7fb03d20e).
- Licenza dichiarata: **GPL-3.0-or-later**.

**Avviso di modifica — 2026-10-25:** il progetto Lume TV ha modificato e adattato il lavoro upstream con un porting Kotlin/Android, player Media3, gestione del catalogo, sessioni, cache e guida TV. Questa è una versione modificata e indipendente, non l’add-on originale.

Gli adattamenti Kotlin si trovano principalmente in `RaiResolver.kt`, `MediasetResolver.kt` e `StreamResolver.kt`. Lume TV reimplementa queste integrazioni per il player Android e usa il servizio playlist WLTV per aggiornare il catalogo. L’origine e il checksum della copia inclusa sono registrati in `catalog/source.json` nel repository.

A WorldLiveTV, WLTV Group e ai collaboratori del progetto originale va il merito della raccolta e della manutenzione delle sorgenti. Lume TV è un progetto indipendente e non rappresenta un’app ufficiale WorldLiveTV.

## Guida programmi

I palinsesti provengono dal servizio esterno [EPG-Guide](https://epg-guide.com/). I dati vengono scaricati e conservati nella cache del dispositivo; non sono inclusi nella distribuzione del codice.

## Librerie

| Componente | Versione | Licenza e titolari |
| --- | --- | --- |
| AndroidX e Media3 | Versioni risolte in `third_party/sources.lock.json` | Apache-2.0; The Android Open Source Project e autori dei singoli componenti |
| Kotlin | 2.3.10 (compatibilità JDK 7/8: 1.9.10) | Apache-2.0; JetBrains e contributori |
| kotlinx.coroutines / serialization | 1.10.2 / 1.7.3 | Apache-2.0; JetBrains e contributori |
| OkHttp / Okio | 4.12.0 / 3.6.0 | Apache-2.0; Square e contributori |
| Guava / failureaccess | 33.3.1-android / 1.0.2 | Apache-2.0; Google e contributori |
| JetBrains annotations / JSpecify | 23.0.0 / 1.0.0 | Apache-2.0; rispettivi autori |
| desugar_jdk_libs | 2.1.5 | GPL-2.0-only WITH Classpath-exception-2.0; Oracle, Google e contributori OpenJDK |
| desugar_jdk_libs_configuration (R8) | 2.1.5 | BSD-3-Clause; Copyright (c) 2016, the R8 project authors |
| Public Suffix List, inclusa in OkHttp | Copia distribuita con OkHttp 4.12.0 | MPL-2.0; contributori Public Suffix List |
| XZ for Java | 1.9 | Pubblico dominio; Lasse Collin, Igor Pavlov, Brett Okken |
| JUnit, usato solo nei test | 4.13.2 | EPL-1.0; autori JUnit |

Le licenze si applicano ai rispettivi componenti e non ai contenuti delle emittenti. Il progetto conserva i copyright dei sorgenti upstream; i nomi sopra riassumono i titolari, senza sostituire gli avvisi dei singoli file. Gli avvisi originali sono conservati in `app/src/main/assets/DEPENDENCY_NOTICES.txt` e nell’app.

Testi integrali nell’app, in **Opzioni → Licenze e avvisi**, e nei sorgenti in `app/src/main/assets/licenses/`: Apache 2.0, GPLv2 con Classpath Exception, BSD R8, MPL 2.0, dichiarazione XZ, informazioni aggiuntive OpenJDK. La EPL 1.0 dei test è inclusa nella stessa cartella. La Classpath Exception consente il collegamento con moduli indipendenti con licenze diverse; non elimina gli obblighi relativi al componente desugar.

### Sorgenti corrispondenti

Il progetto pubblica soltanto [codice sorgente](https://github.com/pjor94/LumeTV). Gli archivi completi `LumeTV-<versione>-source.zip`, conservati tra gli artefatti del workflow Android in [GitHub Actions](https://github.com/pjor94/LumeTV/actions), comprendono gli script di compilazione/installazione e gli archivi upstream in `third_party/sources/`. Non vengono pubblicate APK. Versioni, revisioni, licenze, URL e checksum sono registrati in `third_party/sources.lock.json`. Istruzioni in `third_party/README.md`.

Chi ridistribuisce una propria APK deve fornire i sorgenti corrispondenti secondo le licenze applicabili, comprese le dipendenze richieste; il pacchetto deve corrispondere alla compilazione distribuita.

desugar_jdk_libs 2.1.5 proviene dalla revisione Google `73170c345e6a762fc6a1f0301bb15218850023ef`; la configurazione R8 dalla revisione `c331a820ddae7dea41f41a04375b784486d0f705`. Il componente desugar non è stato modificato manualmente; D8/R8 ne adatta e riduce il bytecode per l’APK. La Public Suffix List non è stata modificata: la copia modificabile, il generatore e gli avvisi MPL sono inclusi nei sorgenti OkHttp del tag `parent-4.12.0`. I sorgenti mantengono i copyright e le rispettive licenze.

## Lume TV

Copyright (c) 2026, contributori del progetto Lume TV.

Il codice, il simbolo, il lettering e le altre risorse originali di Lume TV sono distribuiti con licenza **GPL-3.0-or-later**. Il testo integrale è disponibile in `LICENSE` e nell’app, in **Opzioni → Licenze e avvisi**.

Lume TV è software libero: puoi ridistribuirlo e modificarlo secondo la GNU GPL versione 3 o successiva. È fornito senza garanzie nei limiti consentiti dalla legge; vedi le sezioni 15–17 della licenza. La licenza del software non autorizza utilizzi di contenuti o servizi di terzi. Segnalazioni sui diritti e richieste di rimozione: https://github.com/pjor94/LumeTV/issues/new. La raggiungibilità di un endpoint non prova un’autorizzazione; condizioni dei fornitori e limiti territoriali restano applicabili.

Gli stream sono forniti dalle rispettive emittenti. Nomi, marchi, loghi e contenuti delle emittenti appartengono ai loro titolari; la distribuzione dell’app non ne trasferisce i diritti.
