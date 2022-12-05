package com.zhelenskiy.kotlin_playground

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.zhelenskiy.kotlin_playground.core.CompilerMetadata
import com.zhelenskiy.kotlin_playground.ui.theme.KotlinPlaygroundTheme
import kotlinx.coroutines.Job
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KotlinPlaygroundTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VersionsSurface(UpdateVersionsToken())
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VersionsSurfacePreview() {
    KotlinPlaygroundTheme {
        val loaded = CompilerMetadata("1.7", 16)
        VersionsSurface(
            UpdateVersionsToken(), mapOf(
                CompilerMetadata("2.0-Alpha", 20) to VersionState.NotDownloaded,
                CompilerMetadata("1.9", 19) to VersionState.Downloading(Job(), 10),
                CompilerMetadata("1.9-Beta", 18) to VersionState.Downloading(Job(), null),
                CompilerMetadata("1.8", 17) to VersionState.Cancelling,
                loaded to VersionState.Downloaded(File("fake")),
                CompilerMetadata("1.6", 15) to VersionState.Removing,
            ), chosen = loaded
        )
    }
}
