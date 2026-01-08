package io.tolgee.component

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * Utility for tracking Redis operation latency metrics.
 *
 * Wraps Redis operations with timing to expose per-operation latency metrics to Prometheus.
 * This helps identify slow Redis operations and troubleshoot performance issues.
 *
 * Exposed metrics:
 * - redis_operation_latency_seconds{operation="lock.acquire",result="success"}
 * - redis_operation_latency_seconds{operation="bucket.get",result="success"}
 * - redis_operation_latency_seconds{operation="map.compute",result="error"}
 *
 * @see <a href="https://micrometer.io/docs/concepts#_timers">Micrometer Timers</a>
 */
@Component
class RedisLatencyMetrics(
  private val meterRegistry: MeterRegistry,
) {

  /**
   * Wraps a Redis operation with timing and result tracking.
   *
   * @param operation The operation name (e.g., "lock.acquire", "bucket.set")
   * @param block The Redis operation to execute
   * @return The result of the operation
   * @throws Exception if the operation fails (after recording the error)
   */
  fun <T> measure(
    operation: String,
    block: () -> T,
  ): T {
    val timer = Timer.start(meterRegistry)
    var result = "success"
    try {
      return block()
    } catch (e: Exception) {
      result = "error"
      throw e
    } finally {
      timer.stop(
        Timer.builder("redis.operation.latency")
          .description("Redis operation latency in seconds")
          .tag("operation", operation)
          .tag("result", result)
          .register(meterRegistry),
      )
    }
  }

  /**
   * Wraps a Redis operation that doesn't return a value.
   */
  fun measureVoid(
    operation: String,
    block: () -> Unit,
  ) {
    measure(operation) {
      block()
      Unit
    }
  }
}
