package com.zhelenskiy.kotlin_playground.core

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

val webClient = HttpClient(CIO) {
    install(Logging)
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
        })
    }
}
