package jp.hyperequalizer.app.data

import kotlinx.coroutines.flow.Flow

/**
 * 「常時メモリ機能」用リポジトリ。
 * PlayerActivity/EqualizerなどUI層はこのクラス経由でのみ状態を読み書きする。
 */
class MediaStateRepository(private val dao: MediaStateDao) {

    suspend fun getState(uri: String): MediaStateEntity =
        dao.getByUri(uri) ?: MediaStateEntity(uri = uri)

    fun observeState(uri: String): Flow<MediaStateEntity?> = dao.observeByUri(uri)

    fun observeFavorites(): Flow<List<MediaStateEntity>> = dao.observeFavorites()

    suspend fun save(state: MediaStateEntity) {
        dao.upsert(state.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun updatePosition(uri: String, positionMs: Long, durationMs: Long) {
        val current = getState(uri)
        dao.upsert(current.copy(lastPositionMs = positionMs, durationMs = durationMs, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateAspect(uri: String, mode: AspectMode, zoom: Float, panX: Float, panY: Float) {
        val current = getState(uri)
        dao.upsert(
            current.copy(
                aspectMode = mode.name,
                zoomScale = zoom,
                panX = panX,
                panY = panY,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateLoop(uri: String, startMs: Long, endMs: Long, enabled: Boolean) {
        val current = getState(uri)
        dao.upsert(current.copy(loopStartMs = startMs, loopEndMs = endMs, loopEnabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateSpeed(uri: String, speed: Float) {
        val current = getState(uri)
        dao.upsert(current.copy(playbackSpeed = speed, updatedAt = System.currentTimeMillis()))
    }

    /**
     * お気に入りフラグを更新する。対象のレコードがまだ存在しない場合は
     * displayName/mediaType/durationMsを添えて新規作成する(UPDATEのみだと
     * 未作成レコードに対しては何も起こらないため)。
     */
    suspend fun setFavorite(
        uri: String,
        favorite: Boolean,
        displayName: String = "",
        mediaType: MediaType = MediaType.VIDEO,
        durationMs: Long = 0L
    ) {
        val current = dao.getByUri(uri)
        if (current == null) {
            dao.upsert(
                MediaStateEntity(
                    uri = uri,
                    displayName = displayName,
                    mediaType = mediaType.name,
                    durationMs = durationMs,
                    isFavorite = favorite
                )
            )
        } else {
            dao.upsert(current.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun updateVolumeMix(uri: String, vocal: Float, instrumental: Float) {
        val current = getState(uri)
        dao.upsert(current.copy(vocalVolume = vocal, instrumentalVolume = instrumental, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateSeparation(uri: String, vocalPath: String?, instrumentalPath: String?, status: SeparationStatus) {
        val current = getState(uri)
        dao.upsert(
            current.copy(
                separatedVocalPath = vocalPath,
                separatedInstrumentalPath = instrumentalPath,
                separationStatus = status.name,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateEq(uri: String, bandLevelsCsv: String?, enabled: Boolean, bassBoost: Int) {
        val current = getState(uri)
        dao.upsert(current.copy(eqBandLevelsCsv = bandLevelsCsv, eqEnabled = enabled, bassBoostStrength = bassBoost, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(uri: String) = dao.delete(uri)
}
