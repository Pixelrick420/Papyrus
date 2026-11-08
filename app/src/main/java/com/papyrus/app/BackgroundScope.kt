package com.papyrus.app

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A scope for work that must outlive any screen. What a coroutine in it throws goes to [report] and no
 * further: left to itself the scope hands it to the thread's uncaught-exception handler, which ends the
 * process, so a full disk under the background indexer would take the reader down with it. One failure
 * ends only its own job; the scope and the jobs beside it carry on.
 */
internal fun backgroundScope(report: (Throwable) -> Unit): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> report(failure) })
