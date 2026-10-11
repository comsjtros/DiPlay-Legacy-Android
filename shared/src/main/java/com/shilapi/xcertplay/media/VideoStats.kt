package com.shilapi.xcertplay.media

import android.util.Log

/** One-second video counters and bounded latency samples; reports contain timing, never frame contents. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var dropped = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var maxQueueDepth = 0
    private var maxQueueAgeNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private var late = 0
    private var pacingDelayNanos = 0L
    private val ingress = LatencySamples()
    private val queue = LatencySamples()
    private val inputWait = LatencySamples()
    private val submit = LatencySamples()
    private val decode = LatencySamples()
    private val surface = LatencySamples()

    @Synchronized fun onReceived(size: Int, arrivalNs: Long = 0L) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        if (arrivalNs > 0L) ingress.add(now - arrivalNs)
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onQueueWait(ns: Long) { queue.add(ns) }
    @Synchronized fun onInputWait(ns: Long) { inputWait.add(ns) }
    @Synchronized fun onInputSubmit(ns: Long) { submit.add(ns) }
    @Synchronized fun onRendered() { rendered++ }
    @Synchronized fun onRecovery() { recoveries++ }
    @Synchronized fun onDropped(count: Int = 1) { dropped += count.coerceAtLeast(0) }
    @Synchronized fun onQueueSnapshot(frames: Int, oldestAgeNs: Long) {
        maxQueueDepth = maxOf(maxQueueDepth, frames)
        maxQueueAgeNs = maxOf(maxQueueAgeNs, oldestAgeNs)
    }

    /** Time from queueInputBuffer completion to dequeueing its output. */
    @Synchronized fun onDecodeLatency(ns: Long) { decode.add(ns) }
    /** Time from handing an output buffer to the Surface until the codec reports it rendered. */
    @Synchronized fun onSurfaceFrameRendered(ns: Long) {
        surface.add(ns)
        rendered++
    }
    @Synchronized fun onLate() { late++ }
    @Synchronized fun onPacingDelay(ns: Long) { pacingDelayNanos = ns }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0 && dropped == 0) {
            resetWindow(now)
            return null
        }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val delay = if (pacingDelayNanos > 0) " delay=%dms".format(pacingDelayNanos / 1_000_000) else ""
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d " +
            "recoveries=%d dropped=%d qPeak=%d qAgePeak=%dms " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries, dropped, maxQueueDepth,
            maxQueueAgeNs / 1_000_000, touchAvgMs, maxTouchLatencyNs / 1_000_000,
            touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + ingress.summary("ingress") + queue.summary("queue") + inputWait.summary("inputWait") +
            submit.summary("submit") + decode.summary("decode") + surface.summary("surface") +
            " late=$late" + delay
        TouchLatencyProbe.maxSendNs = 0
        Log.i(TAG, line)
        resetWindow(now)
        return line
    }

    private fun resetWindow(now: Long) {
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; dropped = 0; bytes = 0; maxArrivalGapNs = 0
        maxQueueDepth = 0; maxQueueAgeNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0; late = 0
        pacingDelayNanos = 0
        ingress.clear(); queue.clear(); inputWait.clear(); submit.clear(); decode.clear(); surface.clear()
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 1_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}

/** Fixed-capacity samples keep measurement allocation and sorting bounded on low-memory head units. */
private class LatencySamples(private val capacity: Int = 128) {
    private val values = LongArray(capacity)
    private var size = 0

    fun add(ns: Long) {
        if (size < capacity) values[size++] = (ns.coerceAtLeast(0L) / 1_000_000L)
    }

    fun summary(name: String): String {
        if (size == 0) return ""
        val sorted = values.copyOf(size).also { it.sort() }
        val p50 = sorted[(size - 1) * 50 / 100]
        val p90 = sorted[(size - 1) * 90 / 100]
        val p95 = sorted[(size - 1) * 95 / 100]
        return " $name p50=${p50}ms p90=${p90}ms p95=${p95}ms"
    }

    fun clear() { size = 0 }
}
