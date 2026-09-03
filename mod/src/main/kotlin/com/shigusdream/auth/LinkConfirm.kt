package com.shigusdream.auth

import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Дополнение AuthManager: подтверждение кода привязки прямо из игры (/link <код>). */
fun AuthManager.confirmLink(code: String, username: String): Pair<Int, String> {
    return try {
        val form = "code=${urlEncode(code.uppercase())}&username=${urlEncode(username)}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + "/link"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() to response.body()
    } catch (e: Exception) {
        -1 to (e.message ?: e.javaClass.simpleName)
    }
}

private fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)
