package godau.fynn.usagedirectplus.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [Index("appId"), Index("day"), Index("updatedAt")]
)
data class StoredNote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val appId: Long?,
    val day: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val tag: String?,
    val body: String
)
