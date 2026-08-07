package com.androidmcp.hub.relay

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object RelayNetwork {
    /** One shared client: OkHttp manages its own connection/thread pools. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
