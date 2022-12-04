package com.zhelenskiy.kotlin_playground

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
    initialVersions: Map<CompilerMetadata, VersionState>? = null
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
        val newVersions = loadKotlinCompilerVersionsMetadata(localContext)
        val newStates = newVersions.associateWith {
            async(Dispatchers.IO) {
                when (val state = oldStates[it]) {
                    null -> NotLoaded
                    is Downloaded -> if (checkExists(state.path)) state else NotLoaded
                    Cancelling, is Downloading, NotLoaded, Removing -> state
                }
            }
        }.mapValues { (_, v) -> v.await() }
        versionStates.clear()
        versionStates.putAll(newStates)
        for (oldState in oldStates.filterKeys { it !in versionStates }.values) {
            when (oldState) {
                Cancelling, NotLoaded, Removing -> Unit
                is Downloaded -> coroutineScope.launch { removeVersion(oldState.path) }
                is Downloading -> oldState.job.cancel()
            }
        }

        refreshing = false
    }

    val refreshState = rememberPullRefreshState(refreshing = refreshing, onRefresh = {
        coroutineScope.launch { loadKotlinVersionsVisually() }
    })
    if (initialVersions == null) {
        LaunchedEffect(key1 = token) {
            getLoadedVersionsWithState(localContext)?.let {
                versionStates.clear()
                versionStates.putAll(it)
            } ?: loadKotlinVersionsVisually()
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
                DrawVersion(compilerMetadata, state, coroutineScope) {
                    if (versionStates[compilerMetadata] != it) {
                        versionStates[compilerMetadata] = it
                    }
                }
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
    object NotLoaded : VersionState()
    data class Downloading(val job: Job, val percents: Int?) : VersionState()
    data class Downloaded(val path: File) : VersionState()
    object Removing : VersionState()
    object Cancelling : VersionState()
}

@Composable
private fun DrawVersion(
    compilerMetadata: CompilerMetadata,
    loadingState: VersionState,
    coroutineScope: CoroutineScope,
    onStateUpdate: (VersionState) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
    ) {
        Text(text = compilerMetadata.version)
        val applicationContext = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (loadingState) {
                is NotLoaded -> {
                    Button(onClick = {
                        val parentJob = Job()
                        coroutineScope.launch(parentJob) {
                            onStateUpdate(Downloading(parentJob, null))
                            val downloaded =
                                downloadVersion(
                                    applicationContext,
                                    compilerMetadata
                                ) { received, total ->
                                    val percents = (received * 100 / total).toInt()
                                    onStateUpdate(Downloading(parentJob, percents))
                                }
                            onStateUpdate(Downloaded(downloaded))
                        }
                    }) {
                        Text(text = "Download")
                    }
                }
                is Downloading -> {
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
                                onStateUpdate(NotLoaded)
                            }
                        }
                    ) {
                        Text(text = "Cancel")
                    }
                }
                is Downloaded -> {
                    Text(text = "Downloaded", modifier = Modifier.padding(end = 3.dp))
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green.copy(green = 0.5f)),
                        onClick = {
                            onStateUpdate(Removing)
                            coroutineScope.launch {
                                removeVersion(loadingState.path)
                                onStateUpdate(NotLoaded)
                            }
                        }
                    ) {
                        Text(text = "Remove")
                    }
                }
                is Removing -> {
                    Button(
                        enabled = false,
                        onClick = { }
                    ) {
                        Text(text = "Removing...")
                    }
                }
                is Cancelling -> {
                    Button(
                        enabled = false,
                        onClick = { }
                    ) {
                        Text(text = "Cancelling...")
                    }
                }
            }
        }
    }
}
