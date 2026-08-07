package dev.toastmod.ancs.presentation

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

val AMS_SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
val AMS_REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
val AMS_ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
val AMS_ENTITY_ATTRIBUTE: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")

object AMSCommand {
    const val TOGGLE_PLAY_PAUSE: Byte = 2
    const val NEXT_TRACK: Byte = 3
    const val PREVIOUS_TRACK: Byte = 4
}

enum class AMSEntityID(val b: Byte) {
    Player(0), Queue(1), Track(2);

    companion object {
        fun fromByte(b: Byte): AMSEntityID = entries.firstOrNull { it.b == b } ?: Player
    }
}

enum class AMSTrackAttributeID(val b: Byte) {
    Artist(0), Album(1), Title(2), Duration(3);
}

enum class AMSPlayerAttributeID(val b: Byte) {
    Name(0), PlaybackInfo(1), Volume(2);
}

data class AMSTrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Float = 0f
) {
    /** True once at least one text attribute has arrived from the AMS Track entity. */
    val hasData: Boolean get() = title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()
}

data class AMSPlayerInfo(
    val isPlaying: Boolean = false,
    val playbackRate: Float = 1f,
    val elapsedTime: Float = 0f
)

data class AMSState(
    val available: Boolean = false,
    val track: AMSTrackInfo = AMSTrackInfo(),
    val player: AMSPlayerInfo = AMSPlayerInfo()
)

class AMSClient {

    private val _state = MutableStateFlow(AMSState())
    val state: StateFlow<AMSState> = _state.asStateFlow()

    fun setAvailable(available: Boolean) {
        _state.value = _state.value.copy(available = available)
    }

    fun reset() {
        _state.value = AMSState()
    }

    /**
     * Called when a notification arrives on the AMS Entity Update characteristic.
     * Format: EntityID(1) | AttributeID(1) | EntityUpdateFlags(1) | Value(UTF-8 string)
     */
    fun onEntityUpdate(data: ByteArray) {
        if (data.size < 3) {
            Log.w("AMS", "Entity Update too short (${data.size} bytes)")
            return
        }
        val entityId = AMSEntityID.fromByte(data[0])
        val attributeId = data[1]
        // data[2] = EntityUpdateFlags (bit 0 = Truncated)
        val value = if (data.size > 3) data.copyOfRange(3, data.size).decodeToString() else ""
        Log.i("AMS", "EntityUpdate: entity=$entityId attr=$attributeId value=\"$value\"")

        when (entityId) {
            AMSEntityID.Track -> {
                val cur = _state.value.track
                val updated = when (attributeId) {
                    AMSTrackAttributeID.Title.b  -> cur.copy(title = value)
                    AMSTrackAttributeID.Artist.b -> cur.copy(artist = value)
                    AMSTrackAttributeID.Album.b  -> cur.copy(album = value)
                    AMSTrackAttributeID.Duration.b -> cur.copy(duration = value.toFloatOrNull() ?: 0f)
                    else -> cur
                }
                _state.value = _state.value.copy(track = updated)
            }

            AMSEntityID.Player -> {
                if (attributeId == AMSPlayerAttributeID.PlaybackInfo.b) {
                    // "playbackState,playbackRate,elapsedTime"  state: 0=paused 1=playing
                    val parts = value.split(",")
                    if (parts.size >= 3) {
                        _state.value = _state.value.copy(
                            player = AMSPlayerInfo(
                                isPlaying = (parts[0].toIntOrNull() ?: 0) == 1,
                                playbackRate = parts[1].toFloatOrNull() ?: 1f,
                                elapsedTime = parts[2].toFloatOrNull() ?: 0f
                            )
                        )
                    }
                }
            }

            else -> {}
        }
    }
}
