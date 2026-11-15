package it.lumetv.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupCipherTest {
    private val password="Password di prova è 日本語".toCharArray()
    private fun encrypted(data:ByteArray):ByteArray=ByteArrayOutputStream().also {out->BackupCipher.encrypt(out,password){it.write(data)}}.toByteArray()
    private fun plain(data:ByteArray,password:CharArray=this.password):ByteArray=ByteArrayOutputStream().also {out->BackupCipher.decrypt(data.inputStream(),out,password)}.toByteArray()
    @Test fun `PBKDF2 matches the platform implementation including Unicode passwords`() {
        val salt=ByteArray(16){it.toByte()}
        for(iterations in listOf(1,2,1200)) {
            val expected=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password,salt,iterations,256)).encoded
            assertArrayEquals(expected,BackupCipher.derive(password,salt,iterations))
        }
    }
    @Test fun `multiple authenticated frames round trip without plaintext credentials`() {
        val secret="https://example.org/private?token=backup-secret"
        val data=(secret+"\n").repeat(20_000).toByteArray()
        val first=encrypted(data);val second=encrypted(data)
        assertArrayEquals(data,plain(first));assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains(secret))
    }
    @Test fun `wrong passwords tampering truncation and appended bytes are rejected`() {
        val original=encrypted(ByteArray(600_000){(it%251).toByte()})
        val altered=original.clone().apply {this[300_000]=(this[300_000].toInt() xor 1).toByte()}
        for(value in listOf(original.copyOf(31),original.copyOf(original.size-1),original.copyOf(original.size-20),altered,original+byteArrayOf(1))) {
            assertThrows(IOException::class.java){plain(value)}
        }
        assertThrows(IOException::class.java){plain(original,"un’altra password".toCharArray())}
    }
    @Test fun `empty payloads authenticate and password size is bounded`() {
        assertArrayEquals(byteArrayOf(),plain(encrypted(byteArrayOf())))
        assertThrows(IOException::class.java){BackupCipher.encrypt(ByteArrayOutputStream(),"breve".toCharArray()){}}
        assertThrows(IOException::class.java){BackupCipher.decrypt(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),CharArray(129){'x'})}
    }
}
