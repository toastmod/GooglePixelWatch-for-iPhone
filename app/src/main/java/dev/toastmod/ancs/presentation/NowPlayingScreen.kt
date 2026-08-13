package dev.toastmod.ancs.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.IconToggleButton
import androidx.wear.compose.material3.IconToggleButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
fun NowPlayingScreen(
    state: AMSState,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit
) {
    val hasTrackData = state.track.hasData
    val controlsEnabled = state.available || hasTrackData
    val trackKey = "${state.track.title} ${state.track.artist} ${state.track.album}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Box(modifier = Modifier.weight(1f).clipToBounds()) {
                AnimatedContent(
                    targetState = trackKey,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "track_change"
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (!state.available && !hasTrackData) {
                            Text(
                                text = "Media unavailable",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (!hasTrackData && state.player.isPlaying) {
                            // Player and Track arrive as separate AMS notifications - Player's
                            // single "is playing" push routinely lands before Track's four
                            // attributes (title/artist/album/duration) finish streaming in,
                            // especially for a song that was already playing before the watch
                            // connected. Trust the player state we do have instead of showing
                            // the contradictory "Nothing playing" while the buttons already say
                            // otherwise; this clears itself the moment the first track attribute
                            // arrives, no AMS/BLE changes needed.
                            Text(
                                text = "Loading track info…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (!hasTrackData) {
                            Text(
                                text = "Nothing playing",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            MarqueeText(text = state.track.title.ifBlank { "Unknown title" })
                            MarqueeText(
                                text = state.track.artist.ifBlank { "Unknown artist" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // A real Row (not a Box with aligned children) so the buttons lay out sequentially and
        // can never overlap on a small round screen. Because the previous/next buttons are the
        // same size, centering the group as a whole (spacedBy's alignment param) also lands
        // play/pause exactly on the row's true center, same guarantee as before without the
        // overlap risk.
        //
        // Sizes are set with requiredSize rather than touchTargetAwareSize/size: Wear M3's
        // filled icon buttons always pad themselves back up to a 48dp minimum touch target
        // internally, which is more width than three buttons can share on a ~190dp round card -
        // the previous attempt lost the "Next" button entirely off the card's right edge because
        // of this. requiredSize pins the actual layout footprint so the row can fit all three.
        //
        // Skip controls use the standard M3 "filled tonal" treatment (small, muted background)
        // while play/pause is a larger, primary-filled toggle button - the usual media-player
        // hierarchy of one prominent transport action flanked by two secondary ones. Both use
        // Wear M3's animated-shape button variants, which morph their corner rounding on press
        // (and, for play/pause, also between the checked/unchecked state) instead of just
        // swapping icons - the standard Material 3 Expressive treatment for toggle controls.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val skipButtonSize = 36.dp
            val playPauseButtonSize = 48.dp

            FilledTonalIconButton(
                onClick = onPreviousTrack,
                enabled = controlsEnabled,
                shapes = IconButtonDefaults.animatedShapes(),
                modifier = Modifier.requiredSize(skipButtonSize)
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(IconButtonDefaults.iconSizeFor(skipButtonSize))
                )
            }
            IconToggleButton(
                checked = state.player.isPlaying,
                onCheckedChange = { onTogglePlayPause() },
                enabled = controlsEnabled,
                shapes = IconToggleButtonDefaults.variantAnimatedShapes(),
                modifier = Modifier.requiredSize(playPauseButtonSize)
            ) {
                Icon(
                    imageVector = if (state.player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.player.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(IconToggleButtonDefaults.iconSizeFor(playPauseButtonSize))
                )
            }
            FilledTonalIconButton(
                onClick = onNextTrack,
                enabled = controlsEnabled,
                shapes = IconButtonDefaults.animatedShapes(),
                modifier = Modifier.requiredSize(skipButtonSize)
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(IconButtonDefaults.iconSizeFor(skipButtonSize))
                )
            }
        }
    }
}

/**
 * Single-line text that only scrolls (marquee) when it doesn't fit its available width; text
 * that already fits never animates at all. Iterations are unbounded (rather than a fixed count)
 * because basicMarquee leaves clipped text resting mid-cut once it stops - with a finite count
 * it would freeze on a truncated, ellipsis-less string, which reads worse than plain "...". The
 * slow velocity and long repeat delay keep the actual animation frequency low, and Compose stops
 * driving the animation entirely whenever the screen isn't visible (ambient/off), so the battery
 * cost is paid only while this text is on-screen, overflowing, and actually being looked at.
 */
@Composable
private fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    Text(
        text = text,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier
            .fillMaxWidth()
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                repeatDelayMillis = 1_500,
                velocity = 20.dp
            )
    )
}
