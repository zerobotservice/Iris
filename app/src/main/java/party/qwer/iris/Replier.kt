package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import party.qwer.iris.model.MentionItem
import party.qwer.iris.Replier.Companion.SendMessageRequest
import java.io.File

class Replier {
    companion object {
        private val messageChannel = Channel<SendMessageRequest>(Channel.CONFLATED)
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private var messageSenderJob: Job? = null
        private val mutex = Mutex()

        init {
            startMessageSender()
        }

        fun startMessageSender() {
            coroutineScope.launch {
                if (messageSenderJob?.isActive == true) {
                    messageSenderJob?.cancelAndJoin()
                }
                messageSenderJob = launch {
                    for (request in messageChannel) {
                        try {
                            mutex.withLock {
                                request.send()
                                delay(Configurable.messageSendRate)
                            }
                        } catch (e: Exception) {
                            System.err.println("Error sending message from channel: $e")
                        }
                    }
                }
            }
        }

        fun restartMessageSender() {
            startMessageSender()
        }

        private fun sendMessageInternal(referer: String, chatId: Long, msg: String, threadId: Long?) {
            val intent = Intent().apply {
                component = ComponentName("com.kakao.talk", "com.kakao.talk.notification.NotificationActionService")
                putExtra("noti_referer", referer)
                putExtra("chat_id", chatId)
                putExtra("is_chat_thread_notification", threadId != null)
                if (threadId != null) putExtra("thread_id", threadId)
                action = "com.kakao.talk.notification.REPLY_MESSAGE"
                val results = Bundle().apply { putCharSequence("reply_message", msg) }
                val remoteInput = RemoteInput.Builder("reply_message").build()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
            }
            AndroidHiddenApi.startService(intent)
        }

        private fun sendMentionInternal(referer: String, chatId: Long, msg: String, mentions: List<MentionItem>, threadId: Long?) {
            val mentionJson = JSONArray().apply {
                for (m in mentions) {
                    put(JSONObject().apply {
                        put("user_id", m.user_id)
                        put("at", JSONArray(m.at))
                        put("len", m.len)
                    })
                }
            }
            val attachmentJson = JSONObject().apply {
                put("mentions", mentionJson)
            }.toString()

            // 알림에서 PendingIntent 찾아서 직접 실행
            val sbns = NotificationPoller.getActiveNotificationsStatic()
            for (sbn in sbns) {
                if (sbn.packageName != "com.kakao.talk") continue
                val extras = sbn.notification.extras ?: continue
                val notifChatId = extras.getLong("chat_id", -1L)
                if (notifChatId != chatId) continue

                val actions = sbn.notification.actions ?: continue
                for (action in actions) {
                    val remoteInputs = action.remoteInputs ?: continue
                    if (remoteInputs.isNotEmpty()) {
                        try {
                            val results = Bundle().apply {
                                putCharSequence("reply_message", msg)
                                putCharSequence("reply_attachment", attachmentJson)
                            }
                            val replyIntent = Intent()
                            RemoteInput.addResultsToIntent(remoteInputs, replyIntent, results)
                            action.actionIntent.send(null, 0, replyIntent)
                            println("멘션 전송 성공 via PendingIntent")
                            return
                        } catch (e: Exception) {
                            System.err.println("PendingIntent 멘션 실패: $e")
                        }
                    }
                }
            }

            // 알림 없으면 기존 방식 fallback
            println("알림 없음, 기존 방식으로 fallback")
            val intent = Intent().apply {
                component = ComponentName("com.kakao.talk", "com.kakao.talk.notification.NotificationActionService")
                putExtra("noti_referer", referer)
                putExtra("chat_id", chatId)
                putExtra("is_chat_thread_notification", threadId != null)
                if (threadId != null) putExtra("thread_id", threadId)
                action = "com.kakao.talk.notification.REPLY_MESSAGE"
                val results = Bundle().apply {
                    putCharSequence("reply_message", msg)
                    putCharSequence("reply_attachment", attachmentJson)
                }
                val remoteInput = RemoteInput.Builder("reply_message").build()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
            }
            AndroidHiddenApi.startService(intent)
        }

        fun sendMessage(referer: String, chatId: Long, msg: String, threadId: Long?) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMessageInternal(referer, chatId, msg, threadId)
                })
            }
        }

        fun sendMention(referer: String, chatId: Long, msg: String, mentions: List<MentionItem>, threadId: Long?) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMentionInternal(referer, chatId, msg, mentions, threadId)
                })
            }
        }

        fun sendPhoto(room: Long, base64ImageDataString: String) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest { sendPhotoInternal(room, base64ImageDataString) })
            }
        }

        fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest { sendMultiplePhotosInternal(room, base64ImageDataStrings) })
            }
        }

        private fun sendPhotoInternal(room: Long, base64ImageDataString: String) {
            sendMultiplePhotosInternal(room, listOf(base64ImageDataString))
        }

        private fun sendMultiplePhotosInternal(room: Long, base64ImageDataStrings: List<String>) {
            val picDir = File(IMAGE_DIR_PATH).apply { if (!exists()) mkdirs() }
            val uris = base64ImageDataStrings.mapIndexed { idx, base64ImageDataString ->
                val decodedImage = Base64.decode(base64ImageDataString, Base64.DEFAULT)
                val timestamp = System.currentTimeMillis().toString()
                val imageFile = File(picDir, "${timestamp}_${idx}.png").apply { writeBytes(decodedImage) }
                val imageUri = Uri.fromFile(imageFile)
                mediaScan(imageUri)
                imageUri
            }
            if (uris.isEmpty()) { System.err.println("No image URIs created."); return }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage("com.kakao.talk")
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra("key_id", room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try { AndroidHiddenApi.startActivity(intent) } catch (e: Exception) { throw e }
        }

        internal fun interface SendMessageRequest { suspend fun send() }

        private fun mediaScan(uri: Uri) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply { data = uri }
            AndroidHiddenApi.broadcastIntent(mediaScanIntent)
        }
    }
}
