package com.dall06.karani.adapters.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object RateLimiter {
    private val requests = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()

    fun isAllowed(endpointId: String, limitRpm: Int): Boolean {
        if (limitRpm <= 0) return true
        val now = System.currentTimeMillis()
        val windowStart = now - 60000
        val times = requests.computeIfAbsent(endpointId) { CopyOnWriteArrayList() }
        times.removeIf { it < windowStart }
        if (times.size >= limitRpm) return false
        times.add(now)
        return true
    }
}
