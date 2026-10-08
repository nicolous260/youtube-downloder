package com.nicolous.youtubedownloader.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nicolous.youtubedownloader.data.model.VideoItem

@Composable
fun DownloadDialog(
    video: VideoItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var mediaType by remember { mutableStateOf("video") } // "video" or "audio"
    var quality by remember { mutableStateOf("1080") } // "360", "720", "1080"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Download Options") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(text = "Format:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mediaType == "video",
                        onClick = { mediaType = "video" },
                        label = { Text("Video (MP4)") }
                    )
                    FilterChip(
                        selected = mediaType == "audio",
                        onClick = { mediaType = "audio" },
                        label = { Text("Audio (MP3)") }
                    )
                }

                if (mediaType == "video") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Quality:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("360", "720", "1080").forEach { q ->
                            FilterChip(
                                selected = quality == q,
                                onClick = { quality = q },
                                label = { Text("${q}p") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Open server download endpoint in browser / download manager
                    val downloadUrl = "https://10.0.2.16:5000/api/download" // or web view / browser intent
                    // For direct download action, open browser or web UI stream
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://10.0.2.16:5000/"))
                    context.startActivity(intent)
                    onDismiss()
                }
            ) {
                Text("Open in App Web UI")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
