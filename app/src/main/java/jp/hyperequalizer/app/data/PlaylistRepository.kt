package jp.hyperequalizer.app.data

import kotlinx.coroutines.flow.Flow

class PlaylistRepository(private val dao: PlaylistDao) {

    fun observePlaylists(): Flow<List<PlaylistEntity>> = dao.observePlaylists()

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)

    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>> = dao.observeItems(playlistId)

    suspend fun createPlaylist(name: String, mediaType: MediaType): Long =
        dao.insertPlaylist(PlaylistEntity(name = name, mediaType = mediaType.name))

    suspend fun renamePlaylist(playlist: PlaylistEntity, newName: String) =
        dao.updatePlaylist(playlist.copy(name = newName))

    suspend fun deletePlaylist(playlistId: Long) = dao.deletePlaylist(playlistId)

    suspend fun addItem(playlistId: Long, uri: String, displayName: String, mediaType: MediaType) {
        val nextPos = dao.getMaxPosition(playlistId) + 1
        dao.insertItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                uri = uri,
                displayName = displayName,
                mediaType = mediaType.name,
                position = nextPos
            )
        )
    }

    suspend fun removeItem(itemId: Long) = dao.deleteItem(itemId)

    suspend fun removeItemByUri(playlistId: Long, uri: String) = dao.deleteItemByUri(playlistId, uri)

    suspend fun itemCount(playlistId: Long): Int = dao.getItemCount(playlistId)
}
