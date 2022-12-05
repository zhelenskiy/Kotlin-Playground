package com.zhelenskiy.kotlin_playground.core

import android.content.Context
import android.widget.Toast
import com.zhelenskiy.kotlin_playground.VersionState
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val groupId = "org.jetbrains.kotlin"
private const val artefactName = "kotlin-compiler"
private const val kotlinVersionsUrl =
    "https://search.maven.org/solrsearch/select?q=g:$groupId+AND+a:$artefactName&rows=200&core=gav&wt=json"

@kotlinx.serialization.Serializable
private data class WholeResponse(val response: Response)

@kotlinx.serialization.Serializable
private data class Response(val docs: List<Doc>)

@kotlinx.serialization.Serializable
private data class Doc(
    @SerialName("g")
    val groupId: String,
    @SerialName("a")
    val artifactName: String,
    @SerialName("v")
    val version: String,
    val timestamp: Long,
)

@kotlinx.serialization.Serializable
data class CompilerMetadata(val version: String, val timestamp: Long)

suspend fun loadKotlinCompilerVersionsMetadata(applicationContext: Context): List<CompilerMetadata> =
    try {
        val compilerMetadata = makeRequestForKotlinCompilerVersions()
        withContext(Dispatchers.IO) {
            val compilersDirectory = File(applicationContext.filesDir, "compiler/")
            compilersDirectory.mkdirs()
            val timestamps = File(compilersDirectory, "timestamps.txt")
            timestamps.writeText(Json.encodeToString(compilerMetadata))
        }
        compilerMetadata
    } catch (e: Exception) {
        e.printStackTrace()
        listOf()
    }

private suspend fun makeRequestForKotlinCompilerVersions() =
    webClient.get(kotlinVersionsUrl).body<WholeResponse>().response.docs.map {
        require(it.groupId == groupId) { "Expected group id $groupId but got ${it.groupId}" }
        require(it.artifactName == artefactName) {
            "Expected artefact name $artefactName but got ${it.artifactName}"
        }
        CompilerMetadata(it.version, it.timestamp)
    }

suspend fun checkExists(file: File): Boolean = withContext(Dispatchers.IO) {
    file.exists()
}

suspend fun downloadVersion(
    applicationContext: Context,
    metadata: CompilerMetadata,
    onProgress: suspend (bytesReceived: Long, bytesTotal: Long) -> Unit
): File = withContext(Dispatchers.IO) {
    val directory = File(applicationContext.filesDir, "compiler/")
    directory.mkdirs()
    val file = File(directory, "${metadata.version}.jar")
    val fileRequest =
        webClient.get("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler/${metadata.version}/kotlin-compiler-${metadata.version}.jar") {
            onDownload(onProgress)
        }
    val responseBody: ByteArray = fileRequest.body()
    file.writeBytes(responseBody)
    withContext(Dispatchers.Main) {
        val message = "Compiler version ${metadata.version} is saved to $file"
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
    require(checkExists(file)) { "Not saved: $file" }
    file
}

suspend fun removeVersion(file: File) = withContext(Dispatchers.IO) {
    file.delete()
}

private suspend fun getVersionState(
    applicationContext: Context,
    metadata: CompilerMetadata
): VersionState = withContext(Dispatchers.IO) {
    val jar = File(applicationContext.filesDir, "compiler/${metadata.version}.jar")
    if (jar.isFile) VersionState.Downloaded(jar) else VersionState.NotDownloaded
}


private suspend fun getLoadedVersions(applicationContext: Context): List<CompilerMetadata>? =
    withContext(Dispatchers.IO) {
        val directory = File(applicationContext.filesDir, "compiler/timestamps.txt")
        runCatching { Json.decodeFromString<List<CompilerMetadata>>(directory.readText()) }.getOrNull()
    }

suspend fun getLoadedVersionsWithState(applicationContext: Context): Map<CompilerMetadata, VersionState>? =
    getLoadedVersions(applicationContext)?.associateWith { getVersionState(applicationContext, it) }

suspend fun saveCurrentCompiler(applicationContext: Context, compilerMetadata: CompilerMetadata?) =
    withContext(Dispatchers.IO) {
        val compilersDirectory = File(applicationContext.filesDir, "compiler/")
        compilersDirectory.mkdirs()
        File(compilersDirectory, "chosen.txt").writeText(Json.encodeToString(compilerMetadata))
    }

suspend fun getCurrentCompiler(applicationContext: Context): CompilerMetadata? =
    withContext(Dispatchers.IO) {
        val compilersDirectory = File(applicationContext.filesDir, "compiler/")
        val file = File(compilersDirectory, "chosen.txt")
        runCatching { Json.decodeFromString<CompilerMetadata?>(file.readText()) }.getOrNull()
    }
