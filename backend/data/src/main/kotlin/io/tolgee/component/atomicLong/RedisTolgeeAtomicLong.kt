package io.tolgee.component.atomicLong

import io.tolgee.component.RedisLatencyMetrics
import io.tolgee.util.TolgeeAtomicLong
import org.redisson.api.RAtomicLong

class RedisTolgeeAtomicLong(
  private val it: RAtomicLong,
  private val redisLatencyMetrics: RedisLatencyMetrics,
) : TolgeeAtomicLong {
  override fun addAndGet(delta: Long): Long {
    return redisLatencyMetrics.measure("atomiclong.addAndGet") {
      it.addAndGet(delta)
    }
  }

  override fun delete() {
    redisLatencyMetrics.measureVoid("atomiclong.delete") {
      it.delete()
    }
  }

  override fun get(): Long {
    return redisLatencyMetrics.measure("atomiclong.get") {
      it.get()
    }
  }
}
