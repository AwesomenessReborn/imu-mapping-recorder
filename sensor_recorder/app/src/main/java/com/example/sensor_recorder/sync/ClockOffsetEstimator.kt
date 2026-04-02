// ClockOffsetEstimator.kt
package com.example.sensor_recorder.sync

import kotlin.math.sqrt

/**
 * Calculates the clock offset between a controller and a worker device
 * using a series of ping-pong message timestamps.
 */
class ClockOffsetEstimator {
    data class Sample(val offsetNs: Long, val rttNs: Long)
    data class Result(val offsetNs: Long, val stdDevNs: Long, val sampleCount: Int)

    private val samples = mutableListOf<Sample>()

    /**
     * Adds a new timestamp sample from a PING-PONG exchange.
     * @param tControllerSend The controller's timestamp when it sent the PING.
     * @param tWorkerRecv The worker's timestamp when it received the PING.
     * @param tWorkerReply The worker's timestamp when it sent the PONG.
     * @param tControllerRecv The controller's timestamp when it received the PONG.
     */
    fun addSample(tControllerSend: Long, tWorkerRecv: Long, tWorkerReply: Long, tControllerRecv: Long) {
        // Round-trip time as measured by the controller
        val rttNs = tControllerRecv - tControllerSend

        // Clock offset assuming symmetric network latency.
        // offset = worker_time - controller_time
        // worker_time = tWorkerRecv
        // controller_time at that moment = tControllerSend + (rtt / 2)
        val offsetNs = tWorkerRecv - (tControllerSend + tControllerRecv) / 2
        
        samples.add(Sample(offsetNs, rttNs))
    }

    /**
     * Calculates the final offset by averaging the collected samples
     * after discarding outliers.
     * @return A Result object containing the mean offset, standard deviation, and sample count,
     * or null if not enough valid samples are available.
     */
    fun estimate(): Result? {
        if (samples.size < 3) {
            return null // Not enough data for outlier rejection
        }

        // Sort by round-trip time to identify outliers
        val sortedSamples = samples.sortedBy { it.rttNs }

        // Discard the fastest and slowest RTT samples
        val robustSamples = sortedSamples.subList(1, sortedSamples.size - 1)

        if (robustSamples.isEmpty()) {
            return null
        }

        // Calculate the average offset from the remaining samples
        val meanOffset = robustSamples.map { it.offsetNs }.average()

        // Calculate the standard deviation of the offset
        val variance = robustSamples.map {
            val diff = it.offsetNs - meanOffset
            diff * diff
        }.average()
        val stdDev = sqrt(variance)

        return Result(
            offsetNs = meanOffset.toLong(),
            stdDevNs = stdDev.toLong(),
            sampleCount = robustSamples.size
        )
    }

    /**
     * Clears all collected samples.
     */
    fun clear() {
        samples.clear()
    }
}
