@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@file:Suppress("UNUSED_VALUE", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE", "UNUSED_PARAMETER", "unused")
package com.example.pureaudio

import android.Manifest
import android.content.*
import android.graphics.BitmapFactory
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.google.common.util.concurrent.*
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// 1. DATA MODELS
data class AudioTrack(val id: Long, val title: String, val artist: String, val album: String, val uri: android.net.Uri) {
    // Helper to create MediaItem for the player
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build())
        .build()
}

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsResult(val isSynced: Boolean, val text: String, val syncedLines: List<LyricLine> = emptyList())

// 2. MAIN ACTIVITY
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val player = mutableStateOf<Player?>(null)
    private val audioList = mutableStateOf<List<AudioTrack>>(emptyList())

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) loadAudioFiles()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            player.value = controllerFuture?.get()
            loadAudioFiles()
        }, MoreExecutors.directExecutor())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        else requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)

        setContent { MinimalistPlayerUI(player.value, audioList.value) }
    }

    private fun loadAudioFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = mutableListOf<AudioTrack>()
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)

            contentResolver.query(uri, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    if (c.getLong(durCol) >= 40000) {
                        list.add(AudioTrack(c.getLong(idCol), c.getString(titleCol), c.getString(artCol) ?: "Unknown", c.getString(albCol) ?: "Unknown Album", ContentUris.withAppendedId(uri, c.getLong(idCol))))
                    }
                }
            }
            withContext(Dispatchers.Main) {
                audioList.value = list
                val p = player.value
                if (list.isNotEmpty() && p != null && p.mediaItemCount == 0) {
                    val mediaItems = list.map { it.toMediaItem() }
                    p.setMediaItems(mediaItems)
                    p.prepare()
                }
            }
        }
    }
}

// 3. HELPERS & LYRICS ENGINE
fun formatDuration(ms: Long): String = String.format(Locale.US, "%02d:%02d", (ms / 1000) / 60, (ms / 1000) % 60)

fun parseLrc(lrcText: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)")
    lrcText.lines().forEach { line ->
        val match = regex.find(line)
        if (match != null) {
            val (min, sec, msStr, text) = match.destructured
            val ms = msStr.padEnd(3, '0').toLong()
            val timeMs = (min.toLong() * 60000) + (sec.toLong() * 1000) + ms
            if (text.isNotBlank()) lines.add(LyricLine(timeMs, text.trim()))
        }
    }
    return lines.sortedBy { it.timeMs }
}

suspend fun fetchLyrics(context: Context, songId: String, title: String, artist: String): LyricsResult = withContext(Dispatchers.IO) {
    val cacheFile = File(context.cacheDir, "lyrics_$songId.json")

    // Pass 1: Load from Cache
    if (cacheFile.exists()) {
        try {
            val json = JSONObject(cacheFile.readText())
            return@withContext if (json.has("syncedLyrics") && !json.isNull("syncedLyrics")) LyricsResult(true, "", parseLrc(json.getString("syncedLyrics")))
            else LyricsResult(false, json.optString("plainLyrics", "No lyrics text found."))
        } catch (_: Exception) { cacheFile.delete() }
    }

    try {
        val scrubbed = title.replace(Regex("(?i)\\[.*?]|\\(.*?\\)|www\\.\\S+|\\d+\\s*[-.]*\\s*|telugu|song|mp3|audio|320kbps|naasongs|sensongs|teluguwap"), "").trim()
        val cleanTitle = scrubbed.substringBefore("-").trim()
        var res = performSearch(java.net.URLEncoder.encode("$cleanTitle $artist".trim(), "UTF-8"))
        if (res.length() == 0) res = performSearch(java.net.URLEncoder.encode(cleanTitle, "UTF-8"))

        if (res.length() > 0) {
            val bestMatch = res.getJSONObject(0)
            cacheFile.writeText(bestMatch.toString()) // SAVE FOR OFFLINE
            return@withContext if (bestMatch.has("syncedLyrics") && !bestMatch.isNull("syncedLyrics") && bestMatch.getString("syncedLyrics").isNotEmpty()) {
                LyricsResult(true, "", parseLrc(bestMatch.getString("syncedLyrics")))
            } else {
                LyricsResult(false, bestMatch.optString("plainLyrics", "No lyrics text found."))
            }
        } else {
            LyricsResult(false, text = "Lyrics not found for:\n'$cleanTitle'")
        }
    } catch (e: Exception) {
        LyricsResult(false, text = "Network error. Check connection.")
    }
}

fun performSearch(query: String): JSONArray {
    val url = URL("https://lrclib.net/api/search?q=$query")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 4000
    if (connection.responseCode == 200) {
        return JSONArray(connection.inputStream.bufferedReader().readText())
    }
    return JSONArray()
}

// 4. MAIN UI
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MinimalistPlayerUI(player: Player?, songList: List<AudioTrack>) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var currentSongId by remember { mutableStateOf("") }
    var currentSongTitle by remember { mutableStateOf("PureAudio") }
    var currentArtist by remember { mutableStateOf("") }
    var artworkBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // UI STATES
    var showQueue by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showLyricsView by remember { mutableStateOf(false) }
    var selectedSongForMenu by remember { mutableStateOf<AudioTrack?>(null) } // FOR PLAY NEXT

    var currentQueueItems by remember { mutableStateOf(listOf<MediaItem>()) }
    var lyricsResult by remember { mutableStateOf(LyricsResult(false, "Loading lyrics...")) }
    val listState = rememberLazyListState()
    var currentPlaybackPosition by remember { mutableLongStateOf(0L) }

    // DYNAMIC THEME SYSTEM
    var selectedAccentColor by remember { mutableStateOf(Color(0xFFC6FF00)) }
    val themeColors = listOf(Color(0xFFC6FF00), Color(0xFF00B0FF), Color(0xFFFF1744), Color(0xFFFFAB00), Color(0xFFD500F9))

    LaunchedEffect(player) {
        player?.let { p ->
            isPlaying = p.isPlaying
            currentSongId = p.currentMediaItem?.mediaId ?: ""
            currentSongTitle = p.mediaMetadata.title?.toString() ?: "PureAudio"
            currentArtist = p.mediaMetadata.artist?.toString() ?: ""
            val items = mutableListOf<MediaItem>()
            for (i in 0 until p.mediaItemCount) items.add(p.getMediaItemAt(i))
            currentQueueItems = items
        }
    }

    LaunchedEffect(isPlaying, showLyricsView) {
        while (isActive && isPlaying && showLyricsView && lyricsResult.isSynced) {
            currentPlaybackPosition = player?.currentPosition ?: 0L
            delay(200L)
        }
    }

    LaunchedEffect(currentSongId) {
        if (currentSongId.isNotEmpty() && currentSongTitle != "PureAudio") {
            lyricsResult = LyricsResult(false, "Searching database...")
            lyricsResult = fetchLyrics(context, currentSongId, currentSongTitle, currentArtist)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentSongId = mediaItem?.mediaId ?: ""
            }
            override fun onMediaMetadataChanged(m: MediaMetadata) {
                currentSongTitle = m.title?.toString() ?: "PureAudio"
                currentArtist = m.artist?.toString() ?: ""
                artworkBitmap = m.artworkData?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val items = mutableListOf<MediaItem>()
                for (i in 0 until (player?.mediaItemCount ?: 0)) player?.getMediaItemAt(i)?.let { items.add(it) }
                currentQueueItems = items
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    val filteredList = songList.filter {
        it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true) || it.album.contains(searchQuery, ignoreCase = true)
    }

    // ABOUT DIALOG
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("PureAudio Player", fontWeight = FontWeight.Bold, color = selectedAccentColor) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Developer: Rajesh", fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Instagram: rajesh_.19._", color = selectedAccentColor, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("\"PureAudio: For those who don't just hear music, but feel every beat.\"", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, textAlign = TextAlign.Center, color = Color.LightGray)
                }
            },
            confirmButton = { TextButton(onClick = { showAboutDialog = false }) { Text("Close", color = selectedAccentColor) } },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    // APPEARANCE DIALOG
    if (showAppearanceDialog) {
        AlertDialog(
            onDismissRequest = { showAppearanceDialog = false },
            title = { Text("Select Theme Color", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    themeColors.forEach { color ->
                        Box(Modifier.size(45.dp).clip(CircleShape).background(color).clickable { selectedAccentColor = color; showAppearanceDialog = false })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAppearanceDialog = false }) { Text("Cancel", color = Color.Gray) } },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {

            // TOP BAR
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    placeholder = { Text("Search songs or movies...", color = Color.Gray) },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E), focusedIndicatorColor = selectedAccentColor),
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) }
                )
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = Color(0xFF1E1E1E)) {
                        DropdownMenuItem(text = { Text("Appearance", color = Color.White) }, leadingIcon = { Icon(Icons.Default.Palette, null, tint = selectedAccentColor) }, onClick = { showMenu = false; showAppearanceDialog = true })
                        DropdownMenuItem(text = { Text("About", color = Color.White) }, leadingIcon = { Icon(Icons.Default.Info, null, tint = selectedAccentColor) }, onClick = { showMenu = false; showAboutDialog = true })
                    }
                }
            }

            Spacer(Modifier.height(15.dp))

            Box(Modifier.weight(1f)) {
                if (!showQueue) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(260.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1E1E1E)).clickable { showLyricsView = !showLyricsView }, Alignment.Center) {
                            if (showLyricsView) {
                                if (lyricsResult.isSynced) {
                                    val activeIndex = lyricsResult.syncedLines.indexOfLast { it.timeMs <= currentPlaybackPosition }.coerceAtLeast(0)
                                    LaunchedEffect(activeIndex) { if (activeIndex > 1) listState.animateScrollToItem(activeIndex - 1, scrollOffset = -100) }
                                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        itemsIndexed(lyricsResult.syncedLines) { index, line ->
                                            val isActive = index == activeIndex
                                            Text(text = line.text, color = if (isActive) selectedAccentColor else Color.Gray, fontSize = if (isActive) 18.sp else 14.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp).clickable { player?.seekTo(line.timeMs) })
                                        }
                                    }
                                } else {
                                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                                        Text(lyricsResult.text, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
                                    }
                                }
                            } else {
                                if (artworkBitmap != null) Image(artworkBitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Icon(Icons.Default.MusicNote, null, Modifier.size(100.dp), Color.DarkGray)
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(currentSongTitle, color = selectedAccentColor, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

                        LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp)) {
                            itemsIndexed(items = filteredList, key = { _, s -> s.id }) { index, song ->
                                Column(modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = {
                                        val items = filteredList.map { it.toMediaItem() }
                                        player?.setMediaItems(items)
                                        player?.seekTo(index, 0L)
                                        player?.prepare(); player?.play()
                                    },
                                    onLongClick = { selectedSongForMenu = song } // ADDED LONG CLICK
                                ).padding(vertical = 12.dp)) {
                                    Text(song.title, color = if(currentSongTitle == song.title) selectedAccentColor else Color.White, fontWeight = FontWeight.Bold)
                                    Text("${song.artist} | ${song.album}", color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                                    HorizontalDivider(Modifier.padding(top = 12.dp), color = Color(0xFF1E1E1E))
                                }
                            }
                        }
                    }
                } else {
                    Column {
                        Text("Current Playlist", color = selectedAccentColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(currentQueueItems) { i, item ->
                                val isPlayingThis = i == (player?.currentMediaItemIndex ?: -1)
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { player?.seekTo(i, 0L) }, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", color = if(isPlayingThis) selectedAccentColor else Color.DarkGray, modifier = Modifier.width(35.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.mediaMetadata.title?.toString() ?: "Track", color = if(isPlayingThis) selectedAccentColor else Color.White, maxLines = 1, fontSize = 14.sp)
                                    }
                                    IconButton(onClick = { player?.removeMediaItem(i) }) { Icon(Icons.Default.Close, null, tint = Color.DarkGray, modifier = Modifier.size(18.dp)) }
                                }
                                HorizontalDivider(color = Color(0xFF1E1E1E))
                            }
                        }
                    }
                }

                // PLAY NEXT DROPDOWN MENU
                DropdownMenu(expanded = selectedSongForMenu != null, onDismissRequest = { selectedSongForMenu = null }, containerColor = Color(0xFF222222)) {
                    DropdownMenuItem(
                        text = { Text("Play Next", color = Color.White) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = selectedAccentColor) },
                        onClick = {
                            selectedSongForMenu?.let { song ->
                                val nextPos = (player?.currentMediaItemIndex ?: 0) + 1
                                player?.addMediaItem(nextPos, song.toMediaItem())
                                Toast.makeText(context, "Added to Play Next", Toast.LENGTH_SHORT).show()
                            }
                            selectedSongForMenu = null
                        }
                    )
                }
            }

            TrackTimeline(player, isPlaying, selectedAccentColor)

            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                IconButton(onClick = {
                    val items = songList.shuffled().map { it.toMediaItem() }
                    player?.setMediaItems(items); player?.prepare(); player?.play()
                }) { Icon(Icons.Default.Shuffle, null, tint = if(isPlaying) selectedAccentColor else Color.Gray) }
                IconButton(onClick = { player?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(35.dp)) }
                FloatingActionButton(onClick = { if(isPlaying) player?.pause() else player?.play() }, containerColor = Color.White, shape = CircleShape) {
                    Icon(if(isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(35.dp), tint = Color.Black)
                }
                IconButton(onClick = { player?.seekToNext() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(35.dp)) }
                IconButton(onClick = { showLyricsView = !showLyricsView }) { Icon(Icons.Default.Lyrics, null, tint = if(showLyricsView) selectedAccentColor else Color.White) }
                IconButton(onClick = { showQueue = !showQueue }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = if(showQueue) selectedAccentColor else Color.White) }
            }
        }
    }
}

@Composable
fun TrackTimeline(player: Player?, isPlaying: Boolean, accent: Color) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaying, dragging) {
        while (isActive && isPlaying && !dragging) {
            pos = player?.currentPosition ?: 0L
            dur = player?.duration?.coerceAtLeast(0L) ?: 0L
            delay(500L)
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(pos), color = Color.Gray, fontSize = 11.sp)
        Slider(
            value = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f,
            onValueChange = { dragging = true; pos = (it * dur).toLong() },
            onValueChangeFinished = { dragging = false; player?.seekTo(pos) },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
        Text(formatDuration(dur), color = Color.Gray, fontSize = 11.sp)
    }
}