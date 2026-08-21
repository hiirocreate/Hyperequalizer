package jp.hyperequalizer.app.data

import kotlinx.coroutines.flow.Flow

class HiddenFolderRepository(private val dao: HiddenFolderDao) {

    fun observeAll(): Flow<List<HiddenFolderEntity>> = dao.observeAll()

    suspend fun hide(folderPath: String, mediaType: MediaType) =
        dao.hide(HiddenFolderEntity(folderPath = folderPath, mediaType = mediaType.name))

    suspend fun unhide(folderPath: String, mediaType: MediaType) =
        dao.unhide(folderPath, mediaType.name)

    suspend fun getHiddenFolderPaths(mediaType: MediaType): Set<String> =
        dao.getHiddenFolderPaths(mediaType.name).toSet()
}
