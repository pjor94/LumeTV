package it.lumetv.app.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import it.lumetv.app.BuildConfig
import java.security.MessageDigest

class LumeIntegrationProvider : ContentProvider() {
    override fun onCreate() = true

    private fun authorize() {
        val manager = requireNotNull(context).packageManager
        val uid = Binder.getCallingUid()
        val packages = manager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (packages != setOf(IntegrationIdentity.AURA_PACKAGE)) throw SecurityException("Unauthorized integration client")
        @Suppress("DEPRECATION")
        val info = manager.getPackageInfo(IntegrationIdentity.AURA_PACKAGE,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        if (info.applicationInfo?.uid != uid) throw SecurityException("Unauthorized integration UID")
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val certificates = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        if (!IntegrationIdentity.permitted(packages, certificates, BuildConfig.AURA_TRUSTED_CERTIFICATE_SHA256)) {
            throw SecurityException("Unauthorized integration certificate")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        authorize()
        require(uri.scheme == "content" && uri.authority == IntegrationContract.AUTHORITY && uri.query == null && uri.fragment == null)
        require(projection == null || projection.contentEquals(arrayOf("payload")))
        require(selection == null && selectionArgs == null && sortOrder == null)
        val app = requireNotNull(context)
        val payload = when (uri.path) {
            "/v1/capabilities" -> IntegrationContract.metadata(BuildConfig.VERSION_NAME, IntegrationStore.allowed(app)).toString()
            "/v1/snapshot" -> IntegrationStore.snapshot(app)
            else -> throw IllegalArgumentException("Unknown integration endpoint")
        }
        return MatrixCursor(arrayOf("payload"), 1).apply {
            addRow(arrayOf(payload)); setNotificationUri(app.contentResolver, uri)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
        cancellationSignal?.throwIfCanceled()
        return query(uri, projection, selection, selectionArgs, sortOrder).also {
            if (cancellationSignal?.isCanceled == true) { it.close(); cancellationSignal.throwIfCanceled() }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
        authorize()
        require(queryArgs == null || queryArgs.isEmpty)
        return query(uri, projection, null, null, null, cancellationSignal)
    }

    override fun getType(uri: Uri): String { authorize(); return "vnd.android.cursor.item/vnd.it.lumetv.integration" }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = denied()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = denied()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = denied()
    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int = denied()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = denied()
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = denied()
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = denied()
    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? = denied()
    override fun canonicalize(uri: Uri): Uri? = denied()
    override fun uncanonicalize(uri: Uri): Uri? = denied()
    override fun refresh(uri: Uri, args: Bundle?, cancellationSignal: CancellationSignal?): Boolean = denied()
    private fun denied(): Nothing { authorize(); throw UnsupportedOperationException("Read-only integration") }
}
