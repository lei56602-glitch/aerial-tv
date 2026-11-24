package app.orchard.tv.localsend

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Opens received files with the TV's installed system applications. */
internal object LocalSendFileActions {
    private fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.localsend.files", file)

    fun openFile(context: Context, file: File, mimeType: String) {
        require(file.isFile && file.exists()) { "文件不存在：${file.absolutePath}" }
        val uri = contentUri(context, file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        context.startActivity(Intent.createChooser(view, "选择打开方式").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openFolder(context: Context, directory: File) {
        require(directory.isDirectory && directory.exists()) { "文件目录不存在：${directory.absolutePath}" }
        val uri = contentUri(context, directory)
        var lastError: Throwable? = null
        for (type in listOf("vnd.android.document/directory", "resource/folder")) {
            try {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                view.clipData = ClipData.newUri(context.contentResolver, directory.name, uri)
                context.startActivity(Intent.createChooser(view, "打开文件夹").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("电视没有可用的文件管理器")
    }

    fun installApk(context: Context, apk: File) {
        require(apk.isFile && apk.exists() && apk.extension.equals("apk", ignoreCase = true)) {
            "APK 文件不存在或格式不正确"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val uri = contentUri(context, apk)
        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        install.clipData = ClipData.newUri(context.contentResolver, apk.name, uri)
        context.startActivity(install)
    }
}
