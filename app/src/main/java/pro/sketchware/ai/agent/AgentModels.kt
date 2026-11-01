package pro.sketchware.ai.agent

enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

data class AgentTaskItem(
    val id: String,
    val title: String,
    val milestone: String = "General",
    val status: TaskStatus = TaskStatus.PENDING,
    val notes: String = ""
)

data class AgentPlan(
    val goal: String = "",
    val tasks: List<AgentTaskItem> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ChatEntryType {
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT,
    STATUS,
    ERROR
}

data class AgentChatEntry(
    val id: String,
    val type: ChatEntryType,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val checkpointPath: String? = null
)

data class AgentSessionMeta(
    val id: String,
    val scId: String,
    val title: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastPreview: String = "",
    val forkedFromSessionId: String? = null
)

data class AgentChatPage(
    val entries: List<AgentChatEntry>,
    val totalCount: Int,
    val startIndex: Int,
    val hasOlder: Boolean
)

data class AgentSessionSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val entryId: String?,
    val entryType: ChatEntryType?,
    val snippet: String,
    val timestamp: Long
)
