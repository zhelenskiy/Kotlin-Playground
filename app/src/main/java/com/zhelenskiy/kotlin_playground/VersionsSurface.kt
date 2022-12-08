package com.zhelenskiy.kotlin_playground

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhelenskiy.kotlin_playground.VersionState.*
import com.zhelenskiy.kotlin_playground.core.*
import kotlinx.coroutines.*
import java.io.File

class UpdateVersionsToken

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun VersionsSurface(
    token: UpdateVersionsToken,
    initialVersions: Map<CompilerMetadata, VersionState>? = null,
    chosen: CompilerMetadata? = null,
) {
    val versionStates: SnapshotStateMap<CompilerMetadata, VersionState> = remember(token) {
        if (initialVersions == null) mutableStateMapOf()
        else mutableStateMapOf(*(initialVersions.map { (k, v) -> k to v }.toTypedArray()))
    }
    var refreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val localContext = LocalContext.current
    suspend fun loadKotlinVersionsVisually() = withContext(Dispatchers.Main) {
        refreshing = true

        val oldStates = versionStates.toMap()
        val newVersions = try {
            loadKotlinCompilerVersionsMetadata(localContext)
        } catch (e: Exception) {
            val message = "Cannot retrieve available compiler versions: ${e.message}"
            Toast.makeText(localContext, message, Toast.LENGTH_LONG).show()
            refreshing = false
            return@withContext
        }
        val newStates = newVersions.associateWith {
            async(Dispatchers.IO) {
                when (val state = oldStates[it]) {
                    null -> NotDownloaded
                    is Downloaded -> if (checkExists(state.path)) state else NotDownloaded
                    Cancelling, is Downloading, NotDownloaded, Removing -> state
                }
            }
        }.mapValues { (_, v) -> v.await() }
        versionStates.clear()
        versionStates.putAll(newStates)
        for (oldState in oldStates.filterKeys { it !in versionStates }.values) {
            when (oldState) {
                Cancelling, NotDownloaded, Removing -> Unit
                is Downloaded -> coroutineScope.launch { removeVersion(oldState.path) }
                is Downloading -> oldState.job.cancel()
            }
        }

        refreshing = false
    }

    val refreshState = rememberPullRefreshState(refreshing = refreshing, onRefresh = {
        coroutineScope.launch { loadKotlinVersionsVisually() }
    })
    var chosenCompiler by remember { mutableStateOf(chosen.takeIf { it in versionStates }) }
    if (initialVersions == null) {
        LaunchedEffect(key1 = token) {
            getLoadedVersionsWithState(localContext)?.let {
                versionStates.clear()
                versionStates.putAll(it)
            } ?: loadKotlinVersionsVisually()
            chosenCompiler = getCurrentCompiler(localContext).takeIf { it in versionStates }
        }
    }
    if (versionStates.isEmpty()) return
    Box(modifier = Modifier
        .pullRefresh(refreshState)
        .fillMaxSize()
    ) {
        LazyColumn(
            state = rememberLazyListState()
        ) {
            item {
                Text(
                    text = "Available Kotlin versions",
                    fontSize = LocalTextStyle.current.fontSize * 1.5,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            items(versionStates.entries.sortedByDescending { it.key.timestamp }) { (compilerMetadata, state) ->
                Version(compilerMetadata, state, coroutineScope, chosenCompiler == compilerMetadata,
                    onStateUpdate = {
                        if (versionStates[compilerMetadata] != it) {
                            versionStates[compilerMetadata] = it
                        }
                        if (chosenCompiler == compilerMetadata && it !is Downloaded) {
                            chosenCompiler = null
                            coroutineScope.launch { saveCurrentCompiler(localContext, chosenCompiler) }
                        }
                    },
                    onChosenCompiler = {
                        chosenCompiler =
                            if (chosenCompiler == compilerMetadata) null else compilerMetadata
                        coroutineScope.launch { saveCurrentCompiler(localContext, chosenCompiler) }
                    }
                )
            }
        }
        PullRefreshIndicator(
            refreshing = refreshing,
            state = refreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

sealed class VersionState {
    object NotDownloaded : VersionState()
    data class Downloading(val job: Job, val percents: Int?) : VersionState()
    data class Downloaded(val path: File) : VersionState()
    object Removing : VersionState()
    object Cancelling : VersionState()
}

@Composable
private fun Version(
    compilerMetadata: CompilerMetadata,
    loadingState: VersionState,
    coroutineScope: CoroutineScope,
    chosen: Boolean,
    onStateUpdate: (VersionState) -> Unit,
    onChosenCompiler: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = loadingState is Downloaded) { onChosenCompiler() }
            .let { if (chosen) it.background(color = MaterialTheme.colorScheme.secondary) else it }
            .padding(horizontal = 10.dp),
    ) {
        Text(
            text = compilerMetadata.version,
            color = if (chosen) MaterialTheme.colorScheme.onSecondary else Color.Unspecified
        )
        val applicationContext = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (loadingState) {
                is NotDownloaded -> NotDownloadedVersionButtonSide(
                    coroutineScope, onStateUpdate, applicationContext, compilerMetadata
                )
                is Downloading -> DownloadingVersionButtonSide(loadingState, onStateUpdate, coroutineScope)
                is Downloaded -> DownloadedVersionButtonSide(chosen, onStateUpdate, coroutineScope, loadingState)
                is Removing -> RemovingVersionButtonSide()
                is Cancelling -> CancellingVersionButtonSide()
            }
        }
    }
}

@Composable
private fun NotDownloadedVersionButtonSide(
    coroutineScope: CoroutineScope,
    onStateUpdate: (VersionState) -> Unit,
    applicationContext: Context,
    compilerMetadata: CompilerMetadata
) {
    Button(onClick = {
        val parentJob = Job()
        coroutineScope.launch(parentJob) {
            onStateUpdate(Downloading(parentJob, null))
            val downloaded = try {
                downloadVersion(applicationContext, compilerMetadata) { received, total ->
                    val percents = (received * 100 / total).toInt()
                    onStateUpdate(Downloading(parentJob, percents))
                }
            } catch (e: Exception) {
                val message = "Version ${compilerMetadata.version} cannot be downloaded: ${e.message}"
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                onStateUpdate(NotDownloaded)
                return@launch
            }
            onStateUpdate(Downloaded(downloaded))
        }
    }) {
        Text(text = "Download")
    }
}

@Composable
private fun DownloadingVersionButtonSide(
    loadingState: Downloading,
    onStateUpdate: (VersionState) -> Unit,
    coroutineScope: CoroutineScope
) {
    Text(
        text = loadingState.percents?.let { "Loading: $it%" } ?: "Loading...",
        modifier = Modifier.padding(end = 3.dp)
    )
    Button(
        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(red = 0.8f)),
        onClick = {
            loadingState.job.cancel()
            onStateUpdate(Cancelling)
            coroutineScope.launch {
                loadingState.job.join()
                onStateUpdate(NotDownloaded)
            }
        }
    ) {
        Text(text = "Cancel")
    }
}

@Composable
private fun DownloadedVersionButtonSide(
    chosen: Boolean,
    onStateUpdate: (VersionState) -> Unit,
    coroutineScope: CoroutineScope,
    loadingState: Downloaded
) {
    Text(
        text = "Downloaded",
        modifier = Modifier.padding(end = 3.dp),
        color = if (chosen) MaterialTheme.colorScheme.onSecondary else Color.Unspecified
    )
    Button(
        colors = ButtonDefaults.buttonColors(containerColor = Color.Green.copy(green = 0.5f)),
        onClick = {
            onStateUpdate(Removing)
            coroutineScope.launch {
                removeVersion(loadingState.path)
                onStateUpdate(NotDownloaded)
            }
        }
    ) {
        Text(text = "Remove")
    }
}

@Composable
private fun RemovingVersionButtonSide() {
    Button(
        enabled = false,
        onClick = { }
    ) {
        Text(text = "Removing...")
    }
}

@Composable
private fun CancellingVersionButtonSide() {
    Button(
        enabled = false,
        onClick = { }
    ) {
        Text(text = "Cancelling...")
    }
}
