package com.androidmcp.intent.v1;

/** Asynchronous callback channel from a CapApp back to the authenticated Hub caller. */
oneway interface ICapAppCallback {
    void onTools(String toolsJson);
    void onResult(String requestId, String resultJson);
    void onError(String requestId, int code, String message);
}
