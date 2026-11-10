# APK per i test degli aggiornamenti

`update.txt` e `other-signer.txt` sono APK minime codificate in Base64, costruite
dal manifest di questa cartella e senza codice applicativo. Sono firmate con due chiavi di test diverse, generate
appositamente e poi eliminate. Non usano la chiave di distribuzione di Lume TV.
La firma v1 consente al parser Android di verificare i campioni anche nella JVM
di test, senza richiedere le API native di ART usate dagli schemi successivi.
Lo shadow di `StrictJarFile` delega lettura e verifica della firma al `JarFile`
del JDK; il parser Android mantiene la propria logica di raccolta dei certificati.

`UpdateInstallerTest` usa il parser APK di Android su API 27, 28, 29 e 35.
Registra una versione precedente con il certificato di `update.txt`, poi verifica
che l’aggiornamento con la stessa firma passi e quello con l’altra firma venga
rifiutato. Il test positivo deve fallire su API 28 e 29 se viene rimosso il flag
`GET_SIGNATURES` da `UpdateInstaller`.

Per rigenerare i campioni dalla radice del repository, con JDK e Android SDK nel
PATH/ambiente:

```sh
update_fixture_tmp=$(mktemp -d)
update_fixture_tools="$ANDROID_HOME/build-tools/36.0.0"
"$update_fixture_tools/aapt2" link --manifest test-fixtures/updates/AndroidManifest.xml \
  -I "$ANDROID_HOME/platforms/android-36/android.jar" -o "$update_fixture_tmp/unsigned.apk"
for fixture in update other-signer; do
  keytool -genkeypair -keystore "$update_fixture_tmp/$fixture.jks" \
    -alias fixture -storepass fixture-password -keypass fixture-password \
    -dname "CN=Lume TV update test $fixture" -keyalg RSA -keysize 2048 -validity 36500
  "$update_fixture_tools/apksigner" sign --ks "$update_fixture_tmp/$fixture.jks" \
    --ks-key-alias fixture --ks-pass pass:fixture-password --key-pass pass:fixture-password \
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --v4-signing-enabled false --out "$update_fixture_tmp/$fixture.apk" "$update_fixture_tmp/unsigned.apk"
  base64 < "$update_fixture_tmp/$fixture.apk" > "test-fixtures/updates/$fixture.txt"
done
rm -r "$update_fixture_tmp"
```

Le nuove APK avranno certificati e checksum diversi; i test li leggono dai file.
