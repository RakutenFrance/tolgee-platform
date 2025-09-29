package io.tolgee

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue

@Component
class Metrics(
  private val meterRegistry: MeterRegistry,
) {
  fun registerJobQueue(queue: ConcurrentLinkedQueue<*>) {
    Gauge.builder("tolgee.batch.job.execution.queue.size", queue) { it.size.toDouble() }
      .description("Size of the queue of batch job executions")
      .register(meterRegistry)
  }

  val batchJobManagementFailureWithRetryCounter: Counter by lazy {
    Counter.builder("tolgee.batch.job.execution.management.failure.retried")
      .description("Total number of failures when trying to store data about batch job execution (retried)")
      .register(meterRegistry)
  }

  val batchJobManagementTotalFailureFailedCounter: Counter by lazy {
    Counter.builder("tolgee.batch.job.execution.management.failure.failed")
      .description("Total number of failures when trying to store data about batch job execution (execution failed)")
      .register(meterRegistry)
  }

  val bigMetaStoringTimer: Timer by lazy {
    Timer.builder("tolgee.big_meta.storing.timer")
      .description("Time spent storing big meta data (sync)")
      .register(meterRegistry)
  }

  val bigMetaStoringAsyncTimer: Timer by lazy {
    Timer.builder("tolgee.big_meta.storing-async.timer")
      .description("Time spent storing big meta data (async)")
      .register(meterRegistry)
  }

  val bigMetaDeletingAsyncTimer: Timer by lazy {
    Timer.builder("tolgee.big_meta.deleting-async.timer")
      .description("Time spent deleting big meta data (async)")
      .register(meterRegistry)
  }

  val bigMetaNewDistancesComputeTimer: Timer by lazy {
    Timer.builder("tolgee.big_meta.new_distances.compute.timer")
      .description("Time spent computing new distances for big meta data")
      .register(meterRegistry)
  }

  fun getBatchJobLockAcquiredCounter(projectId: Long): Counter {
    return Counter.builder("tolgee.batch.job.lock.acquired")
      .description("Total number of batch job locks successfully acquired per project")
      .tag("project_id", projectId.toString())
      .register(meterRegistry)
  }

  fun getBatchJobLockRejectedCounter(projectId: Long): Counter {
    return Counter.builder("tolgee.batch.job.lock.rejected")
      .description("Total number of batch job locks rejected due to limits per project")
      .tag("project_id", projectId.toString())
      .register(meterRegistry)
  }

  fun getBatchJobLockReleasedCounter(projectId: Long): Counter {
    return Counter.builder("tolgee.batch.job.lock.released")
      .description("Total number of batch job locks released per project")
      .tag("project_id", projectId.toString())
      .register(meterRegistry)
  }

  fun getBatchJobLockCleanupCounter(projectId: Long): Counter {
    return Counter.builder("tolgee.batch.job.lock.cleanup")
      .description("Total number of completed jobs cleaned up from project locks")
      .tag("project_id", projectId.toString())
      .register(meterRegistry)
  }

  fun registerProjectLockGauge(lockManager: () -> Map<Long, Set<Long>>) {
    Gauge.builder("tolgee.batch.job.lock.active_projects", lockManager) { lockMap ->
      lockMap().size.toDouble()
    }
      .description("Number of projects with active batch job locks")
      .register(meterRegistry)
  }

  fun registerTotalActiveLocksGauge(lockManager: () -> Map<Long, Set<Long>>) {
    Gauge.builder("tolgee.batch.job.lock.active_total", lockManager) { lockMap ->
      lockMap().values.sumOf { it.size }.toDouble()
    }
      .description("Total number of active batch job locks across all projects")
      .register(meterRegistry)
  }
}
