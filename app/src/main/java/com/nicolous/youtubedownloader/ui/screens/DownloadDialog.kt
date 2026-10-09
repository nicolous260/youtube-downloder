package com.nicolous.youtubedownloader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nicolous.youtubedownloader.data.model.VideoItem
import com.nicolous.youtubedownloader.ui.viewmodel.MainViewModel

@Composable
fun DownloadDialog(
    video: VideoItem,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    var mediaType by remember { mutableStateOf("video") } // "video" or "audio"
    var quality by remember { mutableStateOf("1080") } // "360", "720", "1080"

    AlertDialog(
        onDismissRequest = { if (!viewModel.isDownloading) onDismiss() },
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

                if (viewModel.isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = viewModel.downloadProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = viewModel.downloadStatusText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
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
                                    onClick = { quality == q },
                                    label = { Text("${q}p") }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!viewModel.isDownloading) {
                Button(
                    onClick = {
                        viewModel.startDownload(video, mediaType, quality)
                    }
                ) {
                    Text("Download Now")
                }
            }
        },
        dismissButton = {
            if (!viewModel.isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
