package app.orchard.tv.ui.home

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.localsend.LocalSendPeer
import app.orchard.tv.localsend.LocalSendPeerChannel
import app.orchard.tv.localsend.LocalSendRuntime
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

@Composable
internal fun LocalSendScreen(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val runtime = remember(context) { LocalSendRuntime(context) }
    val scope = rememberCoroutineScope()
    var peers by remember { mutableStateOf<List<LocalSendPeer>>(emptyList()) }
    var selected by remember { mutableStateOf<Pair<LocalSendPeer, LocalSendPeerChannel>?>(null) }
    var status by remember { mutableStateOf("正在启动局域网发现…") }
    var transferSession by remember { mutableStateOf<String?>(null) }
    var transferText by remember { mutableStateOf("尚未发送文件") }
    var lastEvent by remember { mutableStateOf("") }
    var transferFileSize by remember { mutableStateOf(0L) }
    val activeTransferSession by rememberUpdatedState(transferSession)

    BackHandler(onBack = onDismiss)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = selected ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "正在读取文件…"
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
                    Triple(name, size, descriptor)
                }
                val fd = metadata.third.detachFd()
                val session = withContext(Dispatchers.IO) {
                    runtime.startSendFile(
                        target.first, target.second, fd, metadata.first, metadata.second,
                        context.contentResolver.getType(uri) ?: "application/octet-stream",
                    )
                }
                transferFileSize = metadata.second
                transferSession = session
                transferText = "正在发送：${metadata.first}"
                status = "已连接到 ${target.first.alias}"
            } catch (error: Throwable) {
                status = "发送启动失败：${error.message ?: "未知错误"}"
            }
        }
    }

    LaunchedEffect(runtime) {
        try {
            withContext(Dispatchers.IO) {
                runtime.startHttpServer()
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
            status = "LocalSend 启动失败：${error.message ?: "请检查网络"}"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                activeTransferSession?.let { runCatching { runtime.cancelSend(it); runtime.releaseSend(it) } }
                runCatching { runtime.stopDiscovery() }
                runCatching { runtime.stopHttpServer() }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xE90B1424)).padding(horizontal = 54.dp, vertical = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xF0182940)).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("LocalSend · 局域网传输", style = Type.LargeTitle.copy(color = Color.White))
                    Spacer(Modifier.height(5.dp))
                    BasicText(status, style = Type.Caption.copy(color = Color(0xFFB7C8DA)), maxLines = 2)
                }
                LocalSendAction("关闭", onClick = onDismiss)
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
                LocalSendAction("选择文件并发送", enabled = selected != null && transferSession == null) {
                    picker.launch(arrayOf("*/*"))
                }
                LocalSendAction("取消传输", enabled = transferSession != null) {
                    transferSession?.let { id ->
                        scope.launch(Dispatchers.IO) { runtime.cancelSend(id) }
                        transferText = "已请求取消传输"
                    }
                }
            }
            BasicText(transferText, style = Type.Body.copy(color = Color.White))
            if (lastEvent.isNotBlank()) {
                BasicText(lastEvent, style = Type.Caption.copy(color = Color(0xFF9FB4C9)), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LocalSendAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Pressable(onClick = onClick, enabled = enabled, focusedScale = 1.04f) { state ->
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
