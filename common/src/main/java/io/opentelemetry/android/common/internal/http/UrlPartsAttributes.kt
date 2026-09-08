/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.http

import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.instrumentation.api.internal.HttpConstants
import io.opentelemetry.instrumentation.api.semconv.url.internal.UrlQuerySanitizer
import io.opentelemetry.semconv.UrlAttributes

/**
 * Shared emitter for the decomposed URL attributes — `url.scheme`, `url.path`, `url.query` — on
 * HTTP client spans.
 *
 * The upstream OTel HTTP client extractor records `url.full` and nothing else:
 * `HttpClientAttributesGetter` exposes only `getUrlFull()`, so no part of the pipeline splits the
 * URL up. (The server-side getter does have the per-part accessors, which is why these attributes
 * are easy to mistake for server-only ones — they are not.)
 *
 * That left Android as the only vuTelemetry platform not sending them. Confirmed against the live
 * field-spec matrix on 2026-09-07: `url.scheme` / `url.path` / `url.query` are observed on
 * native-iOS, flutter-iOS and flutter-Android under the same client `<HTTP method>` signal, are
 * marked `p: ["ios","android","flutter"]` in the field-guide catalogue, and were reported missing
 * on native-Android alone. `url.full` was already matched there, so the URL was being reported —
 * just never decomposed.
 *
 * Kept in `common` rather than in either instrumentation so the okhttp and httpurlconnection
 * extractors cannot drift on the omission rules documented in [putUrlParts].
 *
 * This type is in an `internal`-named package and is **not** part of the stable public API.
 */
object UrlPartsAttributes {
    /**
     * Writes whichever of the three parts are meaningful for a request that has a real URL.
     * Callers are expected to have resolved a URL already; this does not attempt to detect a
     * missing one.
     *
     * - `url.scheme` is written when non-blank.
     * - `url.path` is always written, falling back to `/` when the URL carries no path. An
     *   origin-form request for the root resource has a path, and it is `/` — emitting nothing
     *   there would make the root indistinguishable from an unparsed URL.
     * - `url.query` is omitted entirely when absent or empty rather than written as `""`.
     *   Semconv makes it conditionally required, so an empty string would assert "a query was
     *   present and blank", which is a different claim from "there was no query".
     *
     * `url.query` is redacted with the same sanitizer and the same parameter set the upstream
     * `HttpClientAttributesExtractor` applies to `url.full`, so the two agree. That extractor
     * calls `stripSensitiveData()` before writing `url.full`, replacing userinfo and the values of
     * [HttpConstants.SENSITIVE_QUERY_PARAMETERS] (`AWSAccessKeyId`, `Signature`, `sig`,
     * `X-Goog-Signature`) with `REDACTED`. Writing the raw query here would have put the
     * plaintext secret on the same span as the redacted copy — a redaction bypass, not merely a
     * future concern. `url.scheme` and `url.path` need no equivalent: userinfo lives in the
     * authority and the sensitive parameters live in the query, neither of which reaches them.
     *
     * Known gap: a consumer that overrides the set via
     * `Experimental.setSensitiveQueryParameters` changes what `url.full` redacts, but the
     * configured set is write-only upstream — there is no getter — so the override cannot be read
     * back here and `url.query` would still be redacted against the default set. Nothing in this
     * SDK calls that API; if it ever does, the override has to be threaded through to this helper
     * at the same time.
     */
    @JvmStatic
    fun putUrlParts(
        attributes: AttributesBuilder,
        scheme: String?,
        path: String?,
        query: String?,
    ) {
        scheme?.takeIf { it.isNotBlank() }?.let { attributes.put(UrlAttributes.URL_SCHEME, it) }
        attributes.put(UrlAttributes.URL_PATH, path?.takeIf { it.isNotBlank() } ?: "/")
        query
            ?.takeIf { it.isNotBlank() }
            ?.let { UrlQuerySanitizer.redactQueryString(it, HttpConstants.SENSITIVE_QUERY_PARAMETERS) }
            ?.let { attributes.put(UrlAttributes.URL_QUERY, it) }
    }
}
