package com.application.bibleapp.data.remote

/**
 * A backend request that reached the server and got a non-2xx answer. Kept apart from network
 * failures (timeouts, no connection) so sync can tell "the server refused this one item" from
 * "the server couldn't be reached", which must fail the whole pass and retry (#80).
 */
class BackendHttpException(val status: Int, what: String) : Exception("$what request failed: $status")

/** A 4xx other than a timeout or rate limit: sending the same request again won't help. */
val Throwable.isRejectedByServer: Boolean
    get() = this is BackendHttpException && status in 400..499 && status != 408 && status != 429
