package com.mirage.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mirage.android.R
import com.mirage.engine.llm.StreamError
import com.mirage.engine.llm.StreamErrorCode

/** 一条调用失败该显示什么。正文、结算、压缩三处共用同一套说法。 */
@Composable
fun errorText(error: StreamError): String = stringResource(
    when (error.code) {
        StreamErrorCode.Unauthorized -> R.string.error_unauthorized
        StreamErrorCode.Forbidden -> R.string.error_forbidden
        StreamErrorCode.RateLimited -> R.string.error_rate_limited
        StreamErrorCode.QuotaExhausted -> R.string.error_quota
        StreamErrorCode.ModelUnavailable -> R.string.error_model_unavailable
        StreamErrorCode.BadRequest -> R.string.error_bad_request
        StreamErrorCode.ServerError -> R.string.error_server
        StreamErrorCode.IdleTimeout -> R.string.error_idle_timeout
        StreamErrorCode.NetworkFailure -> R.string.error_network
        StreamErrorCode.MalformedPayload -> R.string.error_malformed
        StreamErrorCode.Cancelled -> R.string.error_cancelled
        StreamErrorCode.Unknown -> R.string.error_unknown
    },
)
