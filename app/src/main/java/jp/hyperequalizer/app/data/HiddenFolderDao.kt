package jp.hyperequalizer.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hide(entity: HiddenFolderEntity)

    @Query("DELETE FROM hidden_folder WHERE folderPath = :folderPath AND mediaType = :mediaType")
    suspend fun unhide(folderPath: String, mediaType: String)

    @Query("SELECT * FROM hidden_folder ORDER BY hiddenAt DESC")
    fun observeAll(): Flow<List<HiddenFolderEntity>>

    @Query("SELECT folderPath FROM hidden_folder WHERE mediaType = :mediaType")
    suspend fun getHiddenFolderPaths(mediaType: String): List<String>
}
