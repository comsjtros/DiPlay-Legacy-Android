package com.shilapi.xcertplay.media

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStatsTest {
    @Test fun oneSecondReportSeparatesLatencyStagesAndQueuePressure() {
        var now = 1_000_000_000L
        val stats = VideoStats(label = " test", nanoTime = { now }, log = { _, _ -> })
        stats.onReceived(size = 1200, arrivalNs = now - 5_000_000L)
        stats.onQueueWait(12_000_000L)
        stats.onInputWait(22_000_000L)
        stats.onInputSubmit(3_000_000L)
        stats.onDecodeLatency(90_000_000L)
        stats.onSurfaceFrameRendered(16_000_000L)
        stats.onQueueSnapshot(frames = 3, oldestAgeNs = 40_000_000L)
        stats.onDropped(2)
        now += 1_000_000_000L

        val report = stats.logIfDue()
        assertNotNull(report)
        assertTrue(report!!.contains("ingress p50=5ms p90=5ms p95=5ms"))
        assertTrue(report.contains("queue p50=12ms p90=12ms p95=12ms"))
        assertTrue(report.contains("inputWait p50=22ms p90=22ms p95=22ms"))
        assertTrue(report.contains("submit p50=3ms p90=3ms p95=3ms"))
        assertTrue(report.contains("decode p50=90ms p90=90ms p95=90ms"))
        assertTrue(report.contains("surface p50=16ms p90=16ms p95=16ms"))
        assertTrue(report.contains("dropped=2 qPeak=3 qAgePeak=40ms"))
    }
}
