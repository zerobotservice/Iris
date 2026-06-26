package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ReplyRequest(
    val type: ReplyType = ReplyType.TEXT,
    val room: String,
    val data: JsonElement,
    val threadId: String? = null,
    val mentions: List<MentionItem>? = null,
)

@Serializable
data class MentionItem(
    val user_id: Long,
    val at: List<Int>,
    val len: Int,
)
