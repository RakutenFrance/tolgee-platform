package io.tolgee.batch

import io.mockk.every
import io.mockk.mockk
import io.tolgee.Metrics
import io.tolgee.component.UsingRedisProvider
import io.tolgee.configuration.tolgee.BatchProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.redisson.api.RMap
import org.redisson.api.RedissonClient

class BatchJobProjectLockingManagerMigrationTest {

  private lateinit var batchJobService: BatchJobService
  private lateinit var batchProperties: BatchProperties
  private lateinit var redissonClient: RedissonClient
  private lateinit var usingRedisProvider: UsingRedisProvider
  private lateinit var metrics: Metrics
  private lateinit var mockNewFormatMap: RMap<Long, Set<Long>>
  private lateinit var mockOldFormatMap: RMap<Long, Long?>
  private lateinit var lockManager: BatchJobProjectLockingManager

  @BeforeEach
  fun setup() {
    batchJobService = mockk()
    batchProperties = BatchProperties()
    redissonClient = mockk()
    usingRedisProvider = mockk()
    metrics = mockk(relaxed = true)
    mockNewFormatMap = mockk()
    mockOldFormatMap = mockk()

    // Use Redis for testing migration
    every { usingRedisProvider.areWeUsingRedis } returns true

    // Mock empty incomplete jobs list
    every { batchJobService.getAllIncompleteJobIds(any()) } returns emptyList()

    lockManager = BatchJobProjectLockingManager(
      batchJobService = batchJobService,
      batchProperties = batchProperties,
      redissonClient = redissonClient,
      usingRedisProvider = usingRedisProvider,
      metrics = metrics
    )
  }

  @Test
  fun `should read new format successfully`() {
    // Given: Redis contains new format (Set<Long>)
    every { redissonClient.getMap<Long, Set<Long>>("project_batch_job_locks") } returns mockNewFormatMap
    every { mockNewFormatMap[1L] } returns setOf(123L, 456L)

    // When: Getting locked jobs
    val lockedJobs = lockManager.getLockedJobsForProject(1L)

    // Then: Should return the set as-is
    assertThat(lockedJobs).containsExactlyInAnyOrder(123L, 456L)
  }

  @Test
  fun `should fallback to old format when new format fails`() {
    // Given: New format throws exception, old format contains single job ID
    every { redissonClient.getMap<Long, Set<Long>>("project_batch_job_locks") } returns mockNewFormatMap
    every { mockNewFormatMap[1L] } throws RuntimeException("Deserialization failed")
    every { redissonClient.getMap<Long, Long?>("project_batch_job_locks") } returns mockOldFormatMap
    every { mockOldFormatMap[1L] } returns 123L

    // When: Getting locked jobs
    val lockedJobs = lockManager.getLockedJobsForProject(1L)

    // Then: Should return set with single job ID from old format
    assertThat(lockedJobs).containsExactly(123L)
  }

  @Test
  fun `should convert old format null to empty set`() {
    // Given: New format fails, old format contains null (uninitialized)
    every { redissonClient.getMap<Long, Set<Long>>("project_batch_job_locks") } returns mockNewFormatMap
    every { mockNewFormatMap[1L] } throws RuntimeException("Deserialization failed")
    every { redissonClient.getMap<Long, Long?>("project_batch_job_locks") } returns mockOldFormatMap
    every { mockOldFormatMap[1L] } returns null

    // When: Getting locked jobs
    val lockedJobs = lockManager.getLockedJobsForProject(1L)

    // Then: Should return empty set
    assertThat(lockedJobs).isEmpty()
  }

  @Test
  fun `should convert old format 0L to empty set`() {
    // Given: New format fails, old format contains 0L (explicitly unlocked)
    every { redissonClient.getMap<Long, Set<Long>>("project_batch_job_locks") } returns mockNewFormatMap
    every { mockNewFormatMap[1L] } throws RuntimeException("Deserialization failed")
    every { redissonClient.getMap<Long, Long?>("project_batch_job_locks") } returns mockOldFormatMap
    every { mockOldFormatMap[1L] } returns 0L

    // When: Getting locked jobs
    val lockedJobs = lockManager.getLockedJobsForProject(1L)

    // Then: Should return empty set
    assertThat(lockedJobs).isEmpty()
  }

  @Test
  fun `should handle both old format failures gracefully`() {
    // Given: Both new and old format access fail
    every { redissonClient.getMap<Long, Set<Long>>("project_batch_job_locks") } returns mockNewFormatMap
    every { mockNewFormatMap[1L] } throws RuntimeException("New format failed")
    every { redissonClient.getMap<Long, Long?>("project_batch_job_locks") } returns mockOldFormatMap
    every { mockOldFormatMap[1L] } throws RuntimeException("Old format failed")

    // When: Getting locked jobs
    val lockedJobs = lockManager.getLockedJobsForProject(1L)

    // Then: Should return empty set as fallback
    assertThat(lockedJobs).isEmpty()
  }

  @Test
  fun `should handle new format returning null`() {
    // Given: New format returns null (uninitialized)
    every { redissonClient.getMap<Long, Set<Long>>("project_batch_job_locks") } returns mockNewFormatMap
    every { mockNewFormatMap[1L] } returns null

    // When: Getting locked jobs
    val lockedJobs = lockManager.getLockedJobsForProject(1L)

    // Then: Should return empty set
    assertThat(lockedJobs).isEmpty()
  }
}