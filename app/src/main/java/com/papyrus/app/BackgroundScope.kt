package com.papyrus.app

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Failures go to [report] instead of the uncaught-exception handler, which would end the process
// (e.g. a full disk under the indexer). One failure ends only its own job.
internal fun backgroundScope(report: (Throwable) -> Unit): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> report(failure) })
