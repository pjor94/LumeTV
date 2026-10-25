# Crediti e licenze

## WorldLiveTV / WLTV Group

Il catalogo delle sorgenti e la logica delle integrazioni usate da Lume TV derivano dal lavoro di **WorldLiveTV e WLTV Group**, autori dell’add-on Kodi `plugin.video.wltvhelper`.

- Progetto originale: [WorldLiveTV/WorldLiveTV.github.io](https://github.com/WorldLiveTV/WorldLiveTV.github.io).
- Riferimento degli adattamenti: add-on **1.2.14**, hotfix **121417**.
- Revisione del repository: [`af57d4ccbac56f6464b16a9dfaf4f0d7fb03d20e`](https://github.com/WorldLiveTV/WorldLiveTV.github.io/tree/af57d4ccbac56f6464b16a9dfaf4f0d7fb03d20e).
- Licenza dichiarata: **GPL-3.0-or-later**.

Gli adattamenti Kotlin si trovano principalmente in `RaiResolver.kt`, `MediasetResolver.kt` e `StreamResolver.kt`. Lume TV reimplementa queste integrazioni per il player Android e usa il servizio playlist WLTV per aggiornare il catalogo. L’origine e il checksum della copia inclusa sono registrati in `catalog/source.json` nel repository.

A WorldLiveTV, WLTV Group e ai collaboratori del progetto originale va il merito della raccolta e della manutenzione delle sorgenti. Lume TV è un progetto indipendente e non rappresenta un’app ufficiale WorldLiveTV.

## Guida programmi

I palinsesti provengono dal servizio esterno [EPG-Guide](https://epg-guide.com/). I dati vengono scaricati e conservati nella cache del dispositivo; non sono inclusi nella distribuzione del codice.

## Librerie

| Componente | Licenza |
| --- | --- |
| AndroidX e Media3 | Apache-2.0 |
| Kotlin e kotlinx.coroutines | Apache-2.0 |
| OkHttp e Okio | Apache-2.0 |
| XZ for Java 1.9 | Pubblico dominio |
| JUnit, usato nei test | EPL-1.0 |

Le licenze delle dipendenze si applicano ai rispettivi componenti.

## Lume TV

Il codice, il simbolo, il lettering e le altre risorse originali di Lume TV sono distribuiti con licenza **GPL-3.0-or-later**. Il testo integrale è disponibile in `LICENSE` e nell’app, in **Info → Licenza**.

Gli stream sono forniti dalle rispettive emittenti. Nomi, marchi, loghi e contenuti delle emittenti appartengono ai loro titolari; la distribuzione dell’app non ne trasferisce i diritti.
