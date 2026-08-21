package jp.hyperequalizer.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaStateDao {

    @Upsert
    suspend fun upsert(state: MediaStateEntity)

    @Query("SELECT * FROM media_state WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): MediaStateEntity?

    @Query("SELECT * FROM media_state WHERE uri = :uri LIMIT 1")
    fun observeByUri(uri: String): Flow<MediaStateEntity?>

    @Query("SELECT * FROM media_state WHERE isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<MediaStateEntity>>

    @Query("UPDATE media_state SET isFavorite = :favorite, updatedAt = :now WHERE uri = :uri")
    suspend fun setFavorite(uri: String, favorite: Boolean, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM media_state WHERE isHidden = 1 ORDER BY updatedAt DESC")
    fun observeHidden(): Flow<List<MediaStateEntity>>

    @Query("SELECT uri FROM media_state WHERE isHidden = 1")
    suspend fun getHiddenUris(): List<String>

    @Query("UPDATE media_state SET isHidden = :hidden, updatedAt = :now WHERE uri = :uri")
    suspend fun setHiddenFlag(uri: String, hidden: Boolean, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM media_state WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("SELECT * FROM media_state")
    suspend fun getAll(): List<MediaStateEntity>
}
