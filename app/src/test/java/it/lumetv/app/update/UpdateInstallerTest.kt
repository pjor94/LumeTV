package it.lumetv.app.update

import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.jar.JarFile
import java.util.zip.ZipEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 29, 35], manifest = Config.NONE,
    shadows = [UpdateInstallerTest.PlatformSigningInfo::class, UpdateInstallerTest.JvmStrictJarFile::class])
@Suppress("DEPRECATION")
class UpdateInstallerTest {
    // Use Android's SigningInfo implementation so certificates collected by the real
    // archive parser remain visible instead of being replaced by shadow state.
    @Implements(SigningInfo::class, minSdk = 28)
    class PlatformSigningInfo

    // Android's StrictJarFile needs ART native methods that are unavailable on the
    // JVM. Delegate only JAR I/O and v1 signature verification to the JDK; keep
    // PackageManager's certificate collection and PackageInfo generation intact.
    @Implements(className = "android.util.jar.StrictJarFile", isInAndroidSdk = false)
    class JvmStrictJarFile {
        private lateinit var jar: JarFile

        @Implementation fun __constructor__(path: String, fd: java.io.FileDescriptor, verify: Boolean, rollbackProtection: Boolean) {
            jar = JarFile(path, verify)
            android.system.Os.close(fd)
        }
        @Implementation fun iterator(): Iterator<ZipEntry> = jar.entries().asSequence().iterator()
        @Implementation fun findEntry(name: String): ZipEntry? = jar.getJarEntry(name)
        @Implementation fun getInputStream(entry: ZipEntry) = jar.getInputStream(entry)
        @Implementation fun getCertificateChains(entry: ZipEntry) = jar.getJarEntry(entry.name).codeSigners
            ?.map { it.signerCertPath.certificates.toTypedArray() }?.toTypedArray()
        @Implementation fun close() = jar.close()
    }

    @get:Rule val temporary = TemporaryFolder()
    private val context get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = "it.lumetv.app"
    }
    private fun fixture(name: String) = temporary.newFile("$name.apk").apply {
        val encoded = requireNotNull(UpdateInstallerTest::class.java.getResourceAsStream("/updates/$name.txt")).use { it.readBytes() }
        writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
    private lateinit var apk: File
    private fun info(file: File) = UpdateInfo("1.0.1", 2, 23, false, file.length(),
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })

    @Before fun installPreviousVersion() {
        apk = fixture("update")
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
        } else PackageManager.GET_SIGNATURES
        val installed = requireNotNull(pm.getPackageArchiveInfo(apk.absolutePath, flags))
        installed.versionCode = 1
        installed.versionName = "1.0.0"
        // Keep the archive path separate so Robolectric uses Android's real parser
        // for the downloaded APK rather than returning the installed PackageInfo.
        installed.applicationInfo!!.sourceDir = "/installed/base.apk"
        shadowOf(pm).installPackage(installed)
    }

    @Test fun `signed update passes verification with real archive parsing`() = runBlocking {
        UpdateInstaller.verify(context, apk, info(apk))
    }

    @Test fun `different installed signer still rejects the update`() = runBlocking {
        val other = fixture("other-signer")
        try {
            UpdateInstaller.verify(context, other, info(other))
            fail("An update with a different signer must be rejected")
        } catch (error: UpdateException) {
            assertTrue(error.message.orEmpty().contains("firma"))
        }
    }
}
