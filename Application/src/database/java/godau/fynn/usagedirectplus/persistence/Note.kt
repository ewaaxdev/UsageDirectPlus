package godau.fynn.usagedirectplus.persistence

data class Note(
    val id: Long = 0,
    val applicationId: String? = null,
    val day: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val tag: String? = null,
    val body: String
)
