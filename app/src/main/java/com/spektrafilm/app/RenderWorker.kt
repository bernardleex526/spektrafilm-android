package com.spektrafilm.app

import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single native worker thread (the engine is not re-entrant) with three
 * priorities: urgent jobs (load, export) run first in order, then only the
 * latest preview request, then background jobs (thumbnails) when idle.
 */
class RenderWorker : Thread("spektrafilm-render") {
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val urgent = ArrayDeque<() -> Unit>()
    private var preview: (() -> Unit)? = null
    private val background = ArrayDeque<() -> Unit>()
    private var running = true

    init {
        isDaemon = true
        start()
    }

    fun urgent(job: () -> Unit) = lock.withLock { urgent.addLast(job); wake.signal() }
    fun preview(job: () -> Unit) = lock.withLock { preview = job; wake.signal() }
    fun background(job: () -> Unit) = lock.withLock { background.addLast(job); wake.signal() }
    fun clearBackground() = lock.withLock { background.clear() }
    fun shutdown() = lock.withLock { running = false; wake.signal() }

    override fun run() {
        while (true) {
            val job = lock.withLock {
                while (running && urgent.isEmpty() && preview == null && background.isEmpty()) wake.await()
                if (!running) return
                urgent.removeFirstOrNull() ?: preview?.also { preview = null } ?: background.removeFirst()
            }
            try {
                job()
            } catch (t: Throwable) {
                Log.e("spektrafilm", "render job failed", t)
            }
        }
    }
}
