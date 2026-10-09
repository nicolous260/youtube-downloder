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
                            progress = { viewModel.downloadProgress },
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SelectableButton(
                            text = "Video",
                            selected = mediaType == "video",
                            onClick = { mediaType = "video" },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableButton(
                            text = "Audio",
                            selected = mediaType == "audio",
                            onClick = { mediaType = "audio" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (mediaType == "video") {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Quality:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("360" to "360p", "720" to "720p", "1080" to "1080p").forEach { (qVal, qLabel) ->
                                SelectableButton(
                                    text = qLabel,
                                    selected = quality == qVal,
                                    onClick = { quality = qVal },
                                    modifier = Modifier.weight(1f)
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
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download Now")
                }
            }
        },
        dismissButton = {
            if (!viewModel.isDownloading) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun SelectableButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}
