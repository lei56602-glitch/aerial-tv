package app.orchard.tv.ui.home

import android.provider.OpenableColumns
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.webkit.MimeTypeMap
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.mutableStateListOf
import app.orchard.tv.localsend.LocalSendPeer
import app.orchard.tv.localsend.LocalSendPeerChannel
import app.orchard.tv.localsend.LocalSendRuntime
import app.orchard.tv.localsend.LocalSendFileActions
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private data class IncomingLocalFile(val id: String, val name: String, val size: Long)
private data class IncomingLocalRequest(val sessionId: String, val sender: String, val files: List<IncomingLocalFile>)
private data class ReceivedLocalFile(val name: String, val path: String, val mimeType: String, val size: Long)

@Composable
internal fun LocalSendScreen(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val runtime = remember(context) { LocalSendRuntime(context) }
    val scope = rememberCoroutineScope()
    var peers by remember { mutableStateOf<List<LocalSendPeer>>(emptyList()) }
    var selected by remember { mutableStateOf<Pair<LocalSendPeer, LocalSendPeerChannel>?>(null) }
    var status by remember { mutableStateOf("正在启动局域网发现…") }
    var receiverStatus by remember { mutableStateOf("接收服务正在启动…") }
    var transferSession by remember { mutableStateOf<String?>(null) }
    var transferText by remember { mutableStateOf("尚未发送文件") }
    var lastEvent by remember { mutableStateOf("") }
    var transferFileSize by remember { mutableStateOf(0L) }
    var incomingRequest by remember { mutableStateOf<IncomingLocalRequest?>(null) }
    val completedReceiveQueue = remember { mutableStateListOf<ReceivedLocalFile>() }
    val pendingReceiveFiles = remember { mutableMapOf<String, ReceivedLocalFile>() }
    val activeTransferSession by rememberUpdatedState(transferSession)
    val closeFocusRequester = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)

    // Give TV remotes a deterministic starting point instead of relying on firmware focus heuristics.
    LaunchedEffect(Unit) {
        closeFocusRequester.requestFocus()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = selected ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "正在读取文件…"
            var detachedFd = -1
            try {
                val metadata = withContext(Dispatchers.IO) {
                    var name = uri.lastPathSegment ?: "file"
                    var size = 0L
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        }
                    }
                    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("无法打开所选文件")
                    if (size <= 0L && descriptor.statSize > 0L) size = descriptor.statSize
                    Triple(name, size, descriptor)
                }
                detachedFd = metadata.third.detachFd()
                metadata.third.close()
                val session = withContext(Dispatchers.IO) {
                    runtime.startSendFile(
                        target.first, target.second, detachedFd, metadata.first, metadata.second,
                        context.contentResolver.getType(uri) ?: "application/octet-stream",
                    ).also {
                        // Rust owns the descriptor once JNI has accepted the send session.
                        detachedFd = -1
                    }
                }
                transferFileSize = metadata.second
                transferSession = session
                transferText = "正在发送：${metadata.first}"
                status = "已连接到 ${target.first.alias}"
            } catch (error: Throwable) {
                // If setup failed before JNI took ownership, close the detached descriptor here.
                if (detachedFd >= 0) {
                    runCatching { ParcelFileDescriptor.adoptFd(detachedFd).close() }
                    detachedFd = -1
                }
                status = "发送启动失败：${error.message ?: "未知错误"}"
            }
        }
    }

    LaunchedEffect(runtime) {
        try {
            withContext(Dispatchers.IO) {
                val receivePort = runtime.startHttpServer()
                receiverStatus = "接收服务已开启 · 端口 $receivePort"
                runtime.startDiscovery()
            }
            status = "正在搜索同一局域网内的 LocalSend 设备"
            while (true) {
                val snapshot = withContext(Dispatchers.IO) {
                    runtime.getDiscoveredDevices() to (activeTransferSession?.let(runtime::pollSendEvents) ?: JSONArray())
                }
                peers = snapshot.first
                if (snapshot.second.length() > 0) {
                    for (index in 0 until snapshot.second.length()) {
                        val event = snapshot.second.optJSONObject(index) ?: continue
                        lastEvent = event.toString()
                        val kind = event.optString("type", event.optString("event"))
                        val sent = event.optLong("bytesSent", -1L)
                        if (kind == "sendProgress" && sent >= 0L) {
                            val percent = if (transferFileSize > 0L) (sent * 100L / transferFileSize).coerceIn(0L, 100L) else 0L
                            transferText = "传输进度：$percent%（$sent / $transferFileSize 字节）"
                        }
                        if (kind == "sendComplete") {
                            transferText = if (event.optBoolean("success")) "文件发送完成" else
                                "发送失败：${event.optString("error", "未知错误")}"
                            activeTransferSession?.let { id -> withContext(Dispatchers.IO) { runtime.releaseSend(id) } }
                            transferSession = null
                        }
                    }
                }
                delay(900)
            }
        } catch (error: Throwable) {
            receiverStatus = "接收服务启动失败：${error.message ?: "未知错误"}"
            status = "LocalSend 启动失败：${error.message ?: "请检查网络"}"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                activeTransferSession?.let { runCatching { runtime.cancelSend(it); runtime.releaseSend(it) } }
                runCatching { runtime.stopDiscovery() }
                runCatching { runtime.stopHttpServer() }
            }
        }
    }

    LaunchedEffect(runtime) {
        while (true) {
            try {
                val events = withContext(Dispatchers.IO) { runtime.pollHttpServerEvents() }
                for (index in 0 until events.length()) {
                    val event = events.optJSONObject(index) ?: continue
                    when (event.optString("type")) {
                        "prepareUpload" -> {
                            val filesObject = event.optJSONObject("files") ?: JSONObject()
                            val files = buildList {
                                val keys = filesObject.keys()
                                while (keys.hasNext()) {
                                    val id = keys.next()
                                    val item = filesObject.optJSONObject(id) ?: continue
                                    add(IncomingLocalFile(id, item.optString("fileName", item.optString("file_name", "未命名文件")), item.optLong("size")))
                                }
                            }
                            val sender = event.optJSONObject("device")?.optString("alias")?.takeIf { it.isNotBlank() } ?: event.optString("ip")
                            incomingRequest = IncomingLocalRequest(event.optString("sessionId"), sender, files)
                        }
                        "fileUpload" -> {
                            val sessionId = event.optString("sessionId")
                            val fileId = event.optString("fileId")
                            val item = event.optJSONObject("file") ?: JSONObject()
                            val rawName = item.optString("fileName", item.optString("file_name", "received_file"))
                            val candidateName = rawName.substringAfterLast('/').substringAfterLast('\\')
                                .filter { it.isLetterOrDigit() || it in " ._-()" }.trim()
                            // Never let dot-path names escape the receive directory.
                            val safeName = candidateName.takeUnless { it.isBlank() || it == "." || it == ".." }
                                ?: "received_file"
                            val destination = withContext(Dispatchers.IO) {
                                val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "Downloads")
                                directory.mkdirs()
                                var output = File(directory, safeName)
                                var suffix = 1
                                while (output.exists()) {
                                    val dot = safeName.lastIndexOf('.').takeIf { it > 0 } ?: safeName.length
                                    output = File(directory, safeName.substring(0, dot) + " (" + suffix + ")" + safeName.substring(dot))
                                    suffix++
                                }
                                runtime.acceptIncomingFile(sessionId, fileId, output.absolutePath)
                                output.absolutePath
                            }
                            val extension = safeName.substringAfterLast(".", "").lowercase()
                            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                ?: "application/octet-stream"
                            pendingReceiveFiles["$sessionId|$fileId"] = ReceivedLocalFile(safeName, destination, mimeType, item.optLong("size"))
                            transferText = "正在接收：" + safeName
                            lastEvent = "保存位置：" + destination
                        }
                        "fileUploadProgress" -> {
                            val received = event.optLong("bytesReceived", -1L)
                            if (received >= 0L) {
                                transferText = "正在接收：$received 字节"
                            }
                        }
                        "fileUploadComplete" -> {
                            val key = event.optString("sessionId") + "|" + event.optString("fileId")
                            val received = pendingReceiveFiles.remove(key)
                            if (event.optBoolean("success")) {
                                transferText = "文件接收完成：" + (received?.name ?: "文件")
                                if (received != null) completedReceiveQueue.add(received)
                            } else {
                                transferText = "文件接收失败：" + event.optString("error", "未知错误")
                            }
                        }
                        "prepareUploadAborted" -> {
                            incomingRequest = null
                            transferText = "发送方已取消文件请求"
                        }
                        "sessionEnd" -> {
                            val reason = event.optString("reason")
                            if (reason.isNotBlank()) transferText = "传输会话结束：$reason"
                        }
                        "cancelReceived" -> {
                            transferText = "对方已取消传输"
                        }
                        "listenerFailed" -> status = "接收服务异常：" + event.optString("error")
                    }
                }
            } catch (error: Throwable) {
                status = "接收服务异常：" + (error.message ?: "未知错误")
            }
            delay(700)
        }
    }

    completedReceiveQueue.firstOrNull()?.let { received ->
        LocalSendReceivedDialog(
            file = received,
            onDismiss = { completedReceiveQueue.removeAt(0) },
            onOpenFile = {
                runCatching { LocalSendFileActions.openFile(context, File(received.path), received.mimeType) }
                    .onSuccess { completedReceiveQueue.removeAt(0) }
                    .onFailure { transferText = "无法打开文件：" + (it.message ?: "电视没有可用的对应应用") }
            },
            onOpenFolder = {
                runCatching { LocalSendFileActions.openFolder(context, File(received.path).parentFile ?: context.filesDir) }
                    .onSuccess { completedReceiveQueue.removeAt(0) }
                    .onFailure { transferText = "无法打开目录：" + (it.message ?: "电视没有可用的文件管理器") }
            },
            onInstall = {
                runCatching { LocalSendFileActions.installApk(context, File(received.path)) }
                    .onSuccess { launched -> if (launched) completedReceiveQueue.removeAt(0) }
                    .onFailure { transferText = "无法启动安装程序：" + (it.message ?: "请检查安装权限") }
            },
        )
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xE90B1424)).padding(horizontal = 54.dp, vertical = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xF0182940))
                .focusGroup().verticalScroll(rememberScrollState()).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("文件传输", style = Type.LargeTitle.copy(color = Color.White))
                    Spacer(Modifier.height(5.dp))
                    BasicText(status, style = Type.Caption.copy(color = Color(0xFFB7C8DA)), maxLines = 2)
                }
                LocalSendAction("关闭", focusRequester = closeFocusRequester, onClick = onDismiss)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LocalSendAction(
                    "发送文件",
                    onClick = {
                        when {
                            transferSession != null -> status = "当前有文件正在发送，请等待完成或取消后再发送。"
                            selected == null -> status = "请先在下方“附近设备”中选择接收文件的设备。"
                            else -> runCatching { picker.launch("*/*") }
                                .onFailure { status = "电视文件选择器无法启动：" + (it.message ?: "系统未提供文件选择界面") }
                        }
                    },
                )
                LocalSendAction(
                    "接收文件",
                    onClick = {
                        transferText = if (receiverStatus.startsWith("接收服务已开启")) {
                            "接收服务已开启，手机或电脑可在 LocalSend 中选择这台电视发送文件。"
                        } else receiverStatus
                    },
                )
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF173B38)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                BasicText("接收文件", style = Type.Headline.copy(color = Color.White))
                BasicText(receiverStatus, style = Type.Body.copy(color = Color(0xFF9EF0C5)))
                BasicText(
                    "手机或电脑打开 LocalSend，选择发送并点选这台电视。收到请求后，这里会出现“接收文件 / 拒绝”按钮。",
                    style = Type.Body.copy(color = Color(0xFFD0E8DF)),
                )
            }
            incomingRequest?.let { request ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF5A3A26)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText("收到来自 " + request.sender + " 的文件请求", style = Type.Headline.copy(color = Color.White))
                    request.files.forEach { file ->
                        BasicText(file.name + " · " + file.size + " 字节", style = Type.Body.copy(color = Color.White))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LocalSendAction("接收文件", onClick = {
                            val pending = incomingRequest ?: return@LocalSendAction
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        runtime.respondToIncomingFiles(pending.sessionId, pending.files.map { it.id })
                                    }
                                }.onSuccess { transferText = "已同意接收，正在准备文件" }
                                    .onFailure { transferText = "接收请求失败：" + it.message }
                            }
                            incomingRequest = null
                        })
                        LocalSendAction("拒绝", onClick = {
                            val pending = incomingRequest ?: return@LocalSendAction
                            scope.launch(Dispatchers.IO) {
                                runCatching { runtime.respondToIncomingFiles(pending.sessionId, null) }
                            }
                            incomingRequest = null
                        })
                    }
                }
            }
            BasicText("附近设备（${peers.size}）", style = Type.Headline.copy(color = Color.White))
            if (peers.isEmpty()) {
                BasicText("暂未发现设备。请确认电视与手机或电脑连接同一个局域网，并打开 LocalSend。", style = Type.Body.copy(color = Color(0xFFD0DCE8)))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    peers.forEach { peer ->
                        val channel = peer.channels.firstOrNull { it.protocol.equals("https", ignoreCase = true) }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (selected?.first?.fingerprint == peer.fingerprint) Color(0xFF285C86) else Color(0xFF20344B))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(peer.alias, style = Type.Body.copy(color = Color.White))
                                BasicText(
                                    listOfNotNull(peer.deviceModel, channel?.let { "${it.host}:${it.port}" }).joinToString(" · "),
                                    style = Type.Caption.copy(color = Color(0xFFB7C8DA)),
                                )
                            }
                            LocalSendAction(
                                if (selected?.first?.fingerprint == peer.fingerprint) "已选择" else "选择设备",
                                onClick = { if (channel != null) selected = peer to channel },
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LocalSendAction("取消传输", enabled = transferSession != null) {
                    transferSession?.let { id ->
                        scope.launch {
                            transferText = "正在取消传输…"
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val cancelled = runtime.cancelSend(id)
                                    // The native task owns its cancellation token and finishes its
                                    // worker independently; release the UI event handle immediately
                                    // so the TV does not remain stuck in a "transferring" state.
                                    runtime.releaseSend(id)
                                    cancelled
                                }
                            }.onSuccess { cancelled ->
                                transferSession = null
                                transferFileSize = 0L
                                transferText = if (cancelled) "已取消传输" else "传输任务已结束"
                            }.onFailure {
                                transferText = "取消失败：" + (it.message ?: "未知错误")
                            }
                        }
                    }
                }
            }
            BasicText(transferText, style = Type.Body.copy(color = Color.White))
            if (lastEvent.isNotBlank()) {
                BasicText(lastEvent, style = Type.Caption.copy(color = Color(0xFF9FB4C9)), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LocalSendInstallQrCard()
        }
    }
}


@Composable
private fun LocalSendReceivedDialog(
    file: ReceivedLocalFile,
    onDismiss: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
    onInstall: () -> Unit,
) {
    val isApk = file.name.endsWith(".apk", ignoreCase = true)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF182B40)).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BasicText("文件接收完成", style = Type.LargeTitle.copy(color = Color.White))
            BasicText(file.name, style = Type.Headline.copy(color = Color(0xFF9ED0FF)), maxLines = 2)
            BasicText("保存位置：\n" + file.path, style = Type.Caption.copy(color = Color(0xFFCAD8E6)), maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LocalSendAction("打开目录", onClick = onOpenFolder)
                if (isApk) LocalSendAction("安装应用", onClick = onInstall)
                else LocalSendAction("打开文件", onClick = onOpenFile)
            }
            LocalSendAction("稍后处理", onClick = onDismiss)
        }
    }
}

@Composable
private fun LocalSendAction(
    label: String,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1.04f,
    ) { state ->
        val background = when {
            !enabled -> Color(0xFF35475A)
            state.focused -> Palette.Focus
            else -> Color(0xFF31516F)
        }
        val foreground = if (state.focused) Palette.OnFocus else Color.White
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = Type.Body.copy(color = foreground), maxLines = 1)
        }
    }
}


/** Official cross-platform download entry; avoids hard-coding one phone CPU architecture. */
@Composable
private fun LocalSendInstallQrCard() {
    val qrBitmap by produceState<Bitmap?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(
                "https://localsend.org/zh-CN",
                BarcodeFormat.QR_CODE,
                420,
                420,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 3,
                ),
            )
            val pixels = IntArray(420 * 420)
            for (y in 0 until 420) {
                for (x in 0 until 420) {
                    pixels[y * 420 + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            Bitmap.createBitmap(420, 420, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, 420, 0, 0, 420, 420)
            }
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF172F49))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("手机端下载 LocalSend", style = Type.Headline.copy(color = Color.White))
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "LocalSend 官方中文下载二维码",
                modifier = Modifier.width(280.dp).height(280.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        BasicText(
            "使用手机扫码，下载手机版 LocalSend",
            style = Type.Body.copy(color = Color.White),
        )
    }
}
