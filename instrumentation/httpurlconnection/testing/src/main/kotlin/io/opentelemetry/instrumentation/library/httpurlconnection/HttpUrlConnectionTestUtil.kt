/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object HttpUrlConnectionTestUtil {
    private const val TAG = "HttpUrlConnectionTest"

    fun executeGet(
        inputUrl: String,
        getInputStream: Boolean = true,
        disconnect: Boolean = true,
        onComplete: Runnable = Runnable {},
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(inputUrl).openConnection() as HttpURLConnection

            // always call one API that reads from the connection
            val responseCode = connection.responseCode

            val readInput = if (getInputStream) connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() } else ""

            Log.d(TAG, "response code: $responseCode ,input Stream: $readInput")
        } catch (e: IOException) {
            Log.e(TAG, "Exception occurred while executing GET request", e)
        } finally {
            connection?.takeIf { disconnect }?.disconnect()
            onComplete.run()
        }
    }

    /**
     * Reads the body through `getInputStream()` **without** calling `getResponseCode()` first.
     *
     * [executeGet] always reads `responseCode` before the stream, and `HttpUrlReplacements
     * .endTracing` reports only once — so for a `>= 400` response that path ends the span with the
     * real code and hides what happens when an app goes straight to the stream. That is the common
     * shape in real code, and the one where `getInputStream()` raises `FileNotFoundException` and
     * the connection is reported with the `-1` sentinel instead.
     *
     * Lives here rather than in `androidTest` because the ByteBuddy plugin only weaves `src/main`;
     * a connection opened directly from a test class is never instrumented.
     */
    fun executeGetReadingInputStreamOnly(inputUrl: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(inputUrl).openConnection() as HttpURLConnection
            val readInput = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            Log.d(TAG, "input stream: $readInput")
        } catch (e: IOException) {
            Log.e(TAG, "Exception occurred while reading the input stream", e)
        } finally {
            connection?.disconnect()
        }
    }

    fun post(inputUrl: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(inputUrl).openConnection() as HttpURLConnection
            connection.doOutput = true
            connection.requestMethod = "POST"

            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { out -> out.write("Writing content to output stream!") }

            // always call one API that reads from the connection
            val readInput = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

            Log.d(TAG, "InputStream: $readInput")
        } catch (e: IOException) {
            Log.e(TAG, "Exception occurred while executing post", e)
        } finally {
            connection?.disconnect()
        }
    }
}
