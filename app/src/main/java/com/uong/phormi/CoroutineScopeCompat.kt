package com.uong.phormi

import kotlinx.coroutines.CoroutineScope

/** Small local compatibility helper for cancelling a service-owned coroutine scope. */
fun CoroutineScope.cancel() {
    coroutineContext[kotlinx.coroutines.Job]?.cancel()
}
