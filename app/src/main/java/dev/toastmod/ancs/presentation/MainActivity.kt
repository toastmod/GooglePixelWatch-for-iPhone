package dev.toastmod.ancs.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import dev.toastmod.ancs.presentation.theme.ANCSTheme
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

val ANCS_SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
val ANCS_NOTIFICAITON_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
val ANCS_DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
val ANCS_CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")

enum class EventID(val b: Byte) {
    NotificationAdded(0),
    NotificationModified(1),
    NotificationRemoved(2),
    RESERVED(3);

    companion object {
        private val key = EventID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): EventID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class CategoryID(val b: Byte) {
    Other(0),
    IncomingCall(1),
    MissedCall(2),
    Voicemail(3),
    Social(4),
    Schedule(5),
    Email(6),
    News(7),
    HealthAndFitness(8),
    BusinessAndFinance(9),
    Location(10),
    Entertainment(11),
    RESERVED(12);

    companion object {
        private val key = CategoryID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): CategoryID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class EventFlag(val b: Byte) {
    Silent(0),
    Important(1),
    PreExisting(2),
    PositiveAction(3),
    NegativeAction(4),
    RESERVED(5);

    companion object {
        private val key = EventFlag::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): EventFlag {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class CommandID(val b: Byte) {
    GetNotificationAttributes(0),
    GetAppAttributes(1),
    PerformNotificationAction(2),
    RESERVED(3);

    companion object {
        private val key = CommandID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): CommandID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class NotificationAttributeID(val b: Byte) {
    AppIdentifier(0),
    Title(1),
    Subtitle(2),
    Message(3),
    MessageSize(4),
    Date(5),
    PositiveActionLabel(6),
    NegativeActionLabel(7),
    RESERVED(8);

    companion object {
        private val key = NotificationAttributeID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): NotificationAttributeID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class ActionID(val b: Byte) {
    Positive(0),
    Negative(1),
    RESERVED(3);
    companion object {
        private val key = ActionID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): ActionID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class AppAttributeID(val b: Byte) {
    DisplayName(0),
    RESERVED(1);

    companion object {
        private val key = AppAttributeID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): AppAttributeID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

val NOTIF_CHANNEL_ID = "com.apple.ancs"
var wakelock: PowerManager.WakeLock? = null

class MainActivity : ComponentActivity() {
    private val amsClient = AMSClient()
    private var gattClient: ANCSGattServiceClient? = null
    private var gattService: ANCSAndroidService? = null

    @RequiresPermission(allOf = [Manifest.permission.VIBRATE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS])
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        if (requestCode == 9001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            val intent =  Intent()
        }
    }

    fun sendAMSCommand(cmd: Byte) {

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp(
                amsState = amsClient.state,
                onTogglePlayPause = { sendAMSCommand(AMSCommand.TOGGLE_PLAY_PAUSE) },
                onNextTrack = { sendAMSCommand(AMSCommand.NEXT_TRACK) },
                onPreviousTrack = { sendAMSCommand(AMSCommand.PREVIOUS_TRACK) }
            )
        }

        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS), 9001)
            return
        }

        val intent = Intent(this, ANCSAndroidService::class.java)
        baseContext.startForegroundService(intent)

    }
}

@Composable
fun WearApp(
    amsState: StateFlow<AMSState>,
    onTogglePlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit
) {
    val state by amsState.collectAsState()
    ANCSTheme {
        AppScaffold {
            ScreenScaffold { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center
                ) {
                    NowPlayingScreen(
                        state = state,
                        onTogglePlayPause = onTogglePlayPause,
                        onNextTrack = onNextTrack,
                        onPreviousTrack = onPreviousTrack
                    )
                }
            }
        }
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun DefaultPreview() {
    WearApp(
        amsState = kotlinx.coroutines.flow.MutableStateFlow(
            AMSState(
                available = true,
                track = AMSTrackInfo(title = "Track Title", artist = "Artist", album = "Album", duration = 230f),
                player = AMSPlayerInfo(isPlaying = true, elapsedTime = 42f, playbackRate = 1f)
            )
        ),
        onTogglePlayPause = {},
        onNextTrack = {},
        onPreviousTrack = {}
    )
}
