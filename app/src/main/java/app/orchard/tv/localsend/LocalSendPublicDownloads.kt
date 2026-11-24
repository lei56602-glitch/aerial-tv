package app.orchard.tv.localsend

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a fully received temporary file into the user's public Downloads/LocalSend folder.
 * API 29+ uses MediaStore so scoped storage is respected; older Android versions use the
 * public Downloads filesystem path (WRITE_EXTERNAL_STORAGE is declared for API 28 and below).
 */
internal object LocalSendPublicDownloads {
    data class PublishedFile(
        val uri: Uri,
        val folderUri: Uri?,
        val displayPath: String,
        val legacyFile: File? = null,
    )

    fun publish(context: Context, source: File, displayName: String, mimeType: String): PublishedFile {
        require(source.isFile && source.exists()) { "接收的临时文件不存在" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, source, displayName, mimeType)
        } else {
            publishLegacy(context, source, displayName)
        }
    }

    private fun publishWithMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): PublishedFile {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LocalSend")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "无法在公共下载目录创建文件"
        }
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "无法写入公共下载目录" }
                source.inputStream().use { input -> input.copyTo(output) }
            }
            val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(uri, ready, null, null) == 1) { "无法完成公共文件登记" }
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        val folderUri = runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download/LocalSend"
            )
        }.getOrNull()
        return PublishedFile(uri, folderUri, "内部存储/Download/LocalSend/$displayName")
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, source: File, displayName: String): PublishedFile {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LocalSend"
        )
        check(directory.exists() || directory.mkdirs()) { "无法创建公共下载目录" }
        var target = File(directory, displayName)
        var suffix = 1
        while (target.exists()) {
            val dot = displayName.lastIndexOf('.').takeIf { it > 0 } ?: displayName.length
            target = File(directory, displayName.substring(0, dot) + " (" + suffix + ")" + displayName.substring(dot))
            suffix++
        }
        source.inputStream().use { input -> target.outputStream().use(input::copyTo) }
        return PublishedFile(
            Uri.fromFile(target),
            null,
            "内部存储/Download/LocalSend/${target.name}",
            target
        )
    }
}
