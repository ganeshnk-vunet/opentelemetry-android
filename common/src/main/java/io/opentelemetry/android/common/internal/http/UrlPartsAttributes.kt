/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.http

import io.opentelemetry.api.common.AttributesBuilder
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
     * Values are written as-is, with no redaction. The span already carries the identical
     * characters in `url.full`, so splitting them out exposes nothing that was not being sent
     * already — but if query redaction is ever introduced it has to be applied to `url.full`
     * at the same time, or the redacted copy sits next to the unredacted original.
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
        query?.takeIf { it.isNotBlank() }?.let { attributes.put(UrlAttributes.URL_QUERY, it) }
    }
}
