package godau.fynn.usagedirectplus.persistence

data class DayUsageTotal(
    val day: Long,
    val timeUsed: Long
)

data class AppUsageTotal(
    val applicationId: String,
    val timeUsed: Long
)
