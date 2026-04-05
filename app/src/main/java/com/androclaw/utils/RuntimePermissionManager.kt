package com.androclaw.utils

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bridges permission requests from background tool execution to the foreground Activity.
 *
 * Flow:
 * 1. Tool handler calls RuntimePermissionManager.request(permissions)
 * 2. This suspends the coroutine and posts a request to the queue
 * 3. ChatActivity observes the queue, launches the system permission dialog
 * 4. On result, ChatActivity calls completeRequest() which resumes the coroutine
 * 5. Tool handler receives the grant results and proceeds or returns an error
 */
object RuntimePermissionManager {

    data class PermissionRequest(
        val permissions: List<String>,
        val toolName: String,
        val deferred: CompletableDeferred<Map<String, Boolean>>
    )

    private val pendingRequests = ConcurrentLinkedQueue<PermissionRequest>()

    /**
     * Called by tool handlers. Suspends until the Activity processes the request.
     * Returns a map of permission -> granted.
     */
    suspend fun request(permissions: List<String>, toolName: String): Map<String, Boolean> {
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pendingRequests.add(PermissionRequest(permissions, toolName, deferred))
        return deferred.await()
    }

    /**
     * Called by the Activity to poll for pending requests.
     */
    fun pollRequest(): PermissionRequest? = pendingRequests.poll()

    /**
     * Called by the Activity after the system dialog returns.
     */
    fun completeRequest(request: PermissionRequest, results: Map<String, Boolean>) {
        request.deferred.complete(results)
    }

    /**
     * Check if all requested permissions were granted.
     */
    fun allGranted(results: Map<String, Boolean>): Boolean =
        results.isNotEmpty() && results.values.all { it }
}
