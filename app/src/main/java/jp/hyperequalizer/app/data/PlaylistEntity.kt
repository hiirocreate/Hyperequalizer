package jp.hyperequalizer.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mediaType: String = MediaType.MIXED.name,
    val createdAt: Long = System.currentTimeMillis()
)
