/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp

import android.content.Context
import com.google.auto.service.AutoService
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.instrumentation.api.internal.HttpConstants
import io.opentelemetry.instrumentation.library.okhttp.internal.OkHttpSingletons
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Instrumentation for okhttp requests.
 */
@AutoService(AndroidInstrumentation::class)
class OkHttpInstrumentation : AndroidInstrumentation {
    @JvmField
    val additionalExtractors: MutableList<AttributesExtractor<Interceptor.Chain, Response>> =
        mutableListOf()

    /**
     * Configures the HTTP request headers that will be captured as span attributes as described in
     * [HTTP semantic conventions](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#http-request-and-response-headers).
     *
     * The HTTP request header values will be captured under the `http.request.header.<name>`
     * attribute key. The `<name>` part in the attribute key is
     * the normalized header name: lowercase, with dashes replaced by underscores.
     *
     * @param capturedRequestHeaders A list of HTTP header names.
     */
    var capturedRequestHeaders: List<String> = listOf()
        set(requestHeaders) {
            field = requestHeaders.toMutableList()
        }

    /**
     * Configures the HTTP response headers that will be captured as span attributes as described in
     * [HTTP semantic conventions](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/http.md#http-request-and-response-headers).
     *
     * The HTTP response header values will be captured under the `http.response.header.<name>`
     * attribute key. The `<name>` part in the attribute key is
     * the normalized header name: lowercase, with dashes replaced by underscores.
     *
     * @param capturedResponseHeaders A list of HTTP header names.
     */
    var capturedResponseHeaders: List<String> = listOf()
        set(responseHeaders) {
            field = responseHeaders.toMutableList()
        }

    /**
     * Configures the attrs extractor to recognize an alternative set of HTTP request methods.
     *
     * By default, the extractor defines "known" methods as the ones listed in
     * [RFC9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-methods) and the PATCH
     * method defined in [RFC5789](https://www.rfc-editor.org/rfc/rfc5789.html). If an
     * unknown method is encountered, the extractor will use the value {@value HttpConstants#_OTHER}
     * instead of it and put the original value in an extra `http.request.method_original`
     * attribute.
     *
     * Note: calling this method **overrides** the default known method sets completely; it
     * does not supplement it.
     *
     * @param knownMethods A set of recognized HTTP request methods.
     */
    var knownMethods: Set<String> = HttpConstants.KNOWN_METHODS
        set(knownMethods) {
            field = knownMethods.toMutableSet()
        }

    private var peerServiceMapping: Map<String, String> = mapOf()
    private var emitExperimentalHttpClientTelemetry = false
    private var captureNetworkTimingPhasesEnabled = true
    private var maxCallDurationMillis = DEFAULT_MAX_CALL_DURATION_MILLIS

    /**
     * Adds an [AttributesExtractor] that will extract additional attributes.
     */
    fun addAttributesExtractor(extractor: AttributesExtractor<Interceptor.Chain, Response>) {
        additionalExtractors.add(extractor)
    }

    /**
     * Configures the extractor of the `peer.service` span attribute, described in
     * [the specification](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/span-general.md#general-remote-service-attributes).
     */
    fun setPeerServiceMapping(peerServiceMapping: MutableMap<String, String>) {
        this.peerServiceMapping = peerServiceMapping.toMap()
    }

    /**
     * When enabled keeps track of [non-stable
     * HTTP client metrics](https://github.com/open-telemetry/semantic-conventions/blob/main/docs/http/http-metrics.md#http-client): [the
     * request size ](https://github.com/open-telemetry/semantic-conventions/blob/main/docs/http/http-metrics.md#metric-httpclientrequestbodysize) and the [
     * the response size](https://github.com/open-telemetry/semantic-conventions/blob/main/docs/http/http-metrics.md#metric-httpserverresponsebodysize).
     */
    fun setEmitExperimentalHttpClientTelemetry(emitExperimentalHttpClientTelemetry: Boolean) {
        this.emitExperimentalHttpClientTelemetry = emitExperimentalHttpClientTelemetry
    }

    fun emitExperimentalHttpClientTelemetry(): Boolean = emitExperimentalHttpClientTelemetry

    /**
     * When enabled, captures per-request network phase timings (DNS, connect, TLS, TTFB, download)
     * as incubating `http.client.timing.*` span attributes and `http.*` span events.
     */
    fun setCaptureNetworkTimingPhases(captureNetworkTimingPhases: Boolean) {
        this.captureNetworkTimingPhasesEnabled = captureNetworkTimingPhases
    }

    fun captureNetworkTimingPhases(): Boolean = captureNetworkTimingPhasesEnabled

    /**
     * Upper bound on how long an `http.client` span may stay open.
     *
     * Span completion is driven by OkHttp's `EventListener`, which reports the end of a call only
     * once its response body has been fully read or closed. A caller that holds a body open -- a
     * server-sent-event stream, a long poll, or simply a body that is never closed -- would
     * otherwise produce a span lasting as long as the caller keeps it, which is not a measure of
     * the request at all.
     *
     * Once this much time has passed the span is ended anyway, carrying
     * `http.client.timing.abandoned`, so no span can outlive the bound. A call that sets OkHttp's
     * own `callTimeout` is held to that instead, since the application has already stated what it
     * considers the longest legitimate call.
     *
     * **Every call that finishes inside the cap reports its true duration**, however slow it was --
     * a two-minute request is recorded as two minutes, not truncated. The cap only applies to calls
     * that never report completion at all, and those are marked rather than silently shortened, so
     * `abandoned = true` should be read as "ran at least this long, exact duration unknown" and
     * excluded from latency percentiles.
     *
     * Set it below the slowest request worth investigating and real findings disappear; that is the
     * failure mode to avoid when tuning it.
     *
     * @param maxCallDurationMillis Milliseconds; must be positive.
     */
    fun setMaxCallDurationMillis(maxCallDurationMillis: Long) {
        require(maxCallDurationMillis > 0) {
            "maxCallDurationMillis must be positive but was $maxCallDurationMillis"
        }
        this.maxCallDurationMillis = maxCallDurationMillis
    }

    fun maxCallDurationMillis(): Long = maxCallDurationMillis

    override fun install(context: Context, openTelemetryRum: OpenTelemetryRum) {
        RumDiagnostics.d { "okhttp: interceptor install" }
        OkHttpSingletons.configure(this, openTelemetryRum.openTelemetry)
    }

    override val name: String = "okhttp"

    companion object {
        /**
         * Default cap on span lifetime; see [setMaxCallDurationMillis].
         *
         * Deliberately generous. The cap exists to stop a span running for hours, **not** to decide
         * what counts as slow -- a request that genuinely takes two minutes is a finding this SDK
         * exists to surface, and truncating it at a tight bound would destroy exactly the signal
         * worth having. Five minutes sits above any plausible real request while still removing the
         * multi-hour pathology.
         */
        const val DEFAULT_MAX_CALL_DURATION_MILLIS: Long = 300_000L
    }
}
