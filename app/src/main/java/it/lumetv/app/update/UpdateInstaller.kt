package it.lumetv.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class UpdateFileProvider : FileProvider()

internal object UpdateInstaller {
    @Suppress("DEPRECATION")
    suspend fun verify(context: Context, file: File, info: UpdateInfo) = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() != info.size) throw UpdateException("Il download non è più disponibile. Scarica di nuovo l’aggiornamento.")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        coroutineContext.ensureActive()
        if (digest.digest().joinToString("") { "%02x".format(it) } != info.sha256) UpdatePolicy.invalid()
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: UpdatePolicy.invalid()
        fun code(packageInfo: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        fun certificates(packageInfo: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) packageInfo.signingInfo?.apkContentsSigners else packageInfo.signatures
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        fun identity(packageInfo: PackageInfo) = ApkIdentity(packageInfo.packageName, code(packageInfo), packageInfo.versionName,
            packageInfo.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } ?: true,
            if (Build.VERSION.SDK_INT >= 24) packageInfo.applicationInfo?.minSdkVersion else null, certificates(packageInfo))
        verifyApkIdentity(identity(installed), identity(archive), info, Build.VERSION.SDK_INT)
        // Android's package installer performs the final cryptographic APK signature verification.
    }

    @Suppress("DEPRECATION")
    fun intent(context: Context, file: File): Intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        data = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }
}
