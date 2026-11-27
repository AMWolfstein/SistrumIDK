package com.amwolfstein.sistrum.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.amwolfstein.sistrum.player.DailyPlaybackAttemptStat
import com.amwolfstein.sistrum.player.DailyTrackListeningStat
import com.amwolfstein.sistrum.player.ListeningSession
import com.amwolfstein.sistrum.player.PlaybackAttempt

@Serializable
data class ListeningSyncSnapshot(
    val version: Int = 1,
    @SerialName("reconciliation_id") val reconciliationId: String,
    val sessions: List<ListeningSession> = emptyList(),
    val attempts: List<PlaybackAttempt> = emptyList(),
    @SerialName("daily_tracks") val dailyTracks: List<DailyTrackListeningStat> = emptyList(),
    @SerialName("daily_attempts") val dailyAttempts: List<DailyPlaybackAttemptStat> = emptyList(),
    val signature: String = "",
)

object ListeningSyncProtocol {
    fun signingInput(snapshot: ListeningSyncSnapshot): ByteArray =
        LibrarySyncProtocol.json.encodeToString(ListeningSyncSnapshot.serializer(), snapshot.copy(signature = "")).encodeToByteArray()
}

interface ListeningSyncStore {
    suspend fun listeningSnapshot(reconciliationId: String, sinceMs: Long): ListeningSyncSnapshot
    suspend fun mergeListeningSnapshot(snapshot: ListeningSyncSnapshot)
}
