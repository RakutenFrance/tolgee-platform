package io.tolgee.configuration

import io.tolgee.component.LockingProvider
import io.tolgee.component.RedisLatencyMetrics
import io.tolgee.component.UsingRedisProvider
import io.tolgee.component.lockingProvider.RedissonLockingProvider
import io.tolgee.component.lockingProvider.SimpleLockingProvider
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LockingConfiguration(
  val usingRedisProvider: UsingRedisProvider,
  val applicationContext: ApplicationContext,
  val redisLatencyMetrics: RedisLatencyMetrics,
) {
  @Bean
  fun redisLockingProvider(): LockingProvider {
    if (usingRedisProvider.areWeUsingRedis) {
      return RedissonLockingProvider(
        applicationContext.getBean(RedissonClient::class.java),
        redisLatencyMetrics,
      )
    }
    return SimpleLockingProvider()
  }
}
