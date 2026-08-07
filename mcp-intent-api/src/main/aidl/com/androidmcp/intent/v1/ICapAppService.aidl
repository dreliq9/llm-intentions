package com.androidmcp.intent.v1;

import com.androidmcp.intent.v1.ICapAppCallback;

/**
 * CapApp Protocol v1 Binder surface.
 *
 * Descriptor/list calls are small; execution is asynchronous so slow tools never hold a
 * Binder thread for the lifetime of the tool call.
 */
interface ICapAppService {
    String getDescriptorJson();
    void listTools(ICapAppCallback callback);
    void execute(String requestId, String toolName, String argumentsJson, ICapAppCallback callback);
}
