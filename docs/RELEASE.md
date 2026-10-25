# Pubblicare una versione

Il workflow [Release](../.github/workflows/release.yml) compila l’app al push di un tag di versione. Prima della pubblicazione esegue test e Lint, firma l’APK e ne verifica certificato, versione e assenza del flag di debug.

La release contiene:

- `LumeTV-<versione>.apk`: APK ottimizzata e firmata.
- `LumeTV-<versione>-source.zip`: sorgente corrispondente, comprese licenze, risorse e test.
- `SHA256SUMS`: checksum dei file distribuiti.
- `SIGNING-CERTIFICATE.txt`: impronta SHA-256 del certificato pubblico.

Le note vengono dalla sezione della versione in `CHANGELOG.md` e includono i crediti a WorldLiveTV / WLTV Group.

## Configurare la firma

Usa una chiave dedicata alle release e conservala con un backup. Tutte le versioni successive devono mantenere la stessa identità di firma per poter aggiornare l’app installata. Le anteprime locali firmate con il certificato di sviluppo hanno un’identità diversa: passando alla firma di release può essere necessario reinstallarle.

Puoi generare il keystore con Android Studio oppure con JDK 17:

```sh
keytool -genkeypair -v \
  -keystore /percorso/sicuro/lumetv-release.jks \
  -alias lumetv \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype JKS
```

Il comando chiede le password in modo interattivo. Il keystore e le password non vanno aggiunti al repository. [Indicazioni Android sulla firma](https://developer.android.com/studio/publish/app-signing).

In **Settings → Secrets and variables → Actions** del repository aggiungi:

| Secret | Valore |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Contenuto del keystore codificato in Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Password del keystore |
| `ANDROID_KEY_ALIAS` | Alias della chiave, per esempio `lumetv` |
| `ANDROID_KEY_PASSWORD` | Password della chiave |

Con GitHub CLI puoi trasferire il keystore direttamente al Secret, senza stampare il contenuto:

```sh
base64 < /percorso/sicuro/lumetv-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --repo pjor94/LumeTV
gh secret set ANDROID_KEYSTORE_PASSWORD --repo pjor94/LumeTV
gh secret set ANDROID_KEY_ALIAS --repo pjor94/LumeTV
gh secret set ANDROID_KEY_PASSWORD --repo pjor94/LumeTV
```

Il workflow usa la chiave soltanto durante la firma e rimuove la copia temporanea. In assenza dei Secrets si interrompe, senza pubblicare un’APK non firmata o usare una firma di sviluppo.

## Creare il tag

1. Aggiorna `versionName` e incrementa `versionCode` in `app/build.gradle.kts`.
2. Aggiungi la sezione della versione a `CHANGELOG.md`.
3. Salva le modifiche in un commit e pubblicalo sul repository.
4. Crea e invia il tag corrispondente. Per la versione attuale:

```sh
git tag -a v0.2.1 -m "Lume TV 0.2.1"
git push origin v0.2.1
```

Il tag deve coincidere esattamente con `v` seguito da `versionName`. Le versioni `0.x` e quelle con suffisso, come `1.0.0-rc.1`, vengono pubblicate come prerelease; le altre come release stabili.

Il workflow prepara una bozza, carica tutti i file e la rende pubblica soltanto dopo il completamento degli upload. Una release già pubblicata non viene sovrascritta. Per correggerla, prepara una nuova versione; una bozza incompleta può essere completata rieseguendo il workflow sullo stesso tag.

Per avviare manualmente la procedura, apri **Actions → Release → Run workflow** e indica un tag già presente nel repository. Non servono token personali: la pubblicazione usa il `GITHUB_TOKEN` del workflow.

## Catalogo

Il workflow [Refresh bundled catalogue](../.github/workflows/catalog.yml) aggiorna la sola copia iniziale della playlist. Non crea tag o release. L’aggiornamento ricevuto dalla TV continua a usare direttamente il servizio WLTV.
