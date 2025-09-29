package io.tolgee.batch

import io.mockk.every
import io.mockk.mockk
import io.tolgee.batch.data.BatchJobDto
import io.tolgee.component.UsingRedisProvider
import io.tolgee.configuration.tolgee.BatchProperties
import io.tolgee.model.batch.BatchJobStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import java.util.concurrent.ConcurrentHashMap

class BatchJobProjectLockingManagerConcurrentTest {
  
  private lateinit var batchJobService: BatchJobService
  private lateinit var batchProperties: BatchProperties
  private lateinit var redissonClient: RedissonClient
  private lateinit var usingRedisProvider: UsingRedisProvider
  private lateinit var lockManager: BatchJobProjectLockingManager

  private val projectId = 1L
  private val job1 = createMockJobDto(1L, projectId, BatchJobStatus.PENDING)
  private val job2 = createMockJobDto(2L, projectId, BatchJobStatus.PENDING)
  private val job3 = createMockJobDto(3L, projectId, BatchJobStatus.PENDING)
  private val job4 = createMockJobDto(4L, projectId, BatchJobStatus.PENDING)

  @BeforeEach
  fun setup() {
    batchJobService = mockk()
    batchProperties = BatchProperties()
    redissonClient = mockk()
    usingRedisProvider = mockk()

    // Use local storage for testing (no Redis)
    every { usingRedisProvider.areWeUsingRedis } returns false

    // Mock job service responses
    every { batchJobService.getJobDto(1L) } returns job1
    every { batchJobService.getJobDto(2L) } returns job2
    every { batchJobService.getJobDto(3L) } returns job3
    every { batchJobService.getJobDto(4L) } returns job4

    // Mock for getInitialJobIds - return empty for clean tests
    every { batchJobService.getAllIncompleteJobIds(projectId) } returns emptyList()

    lockManager = BatchJobProjectLockingManager(
      batchJobService = batchJobService,
      batchProperties = batchProperties,
      redissonClient = redissonClient,
      usingRedisProvider = usingRedisProvider
    )

    // Clear any existing locks
    lockManager.getMap().clear()
  }

  @Test
  fun `should enforce single job limit when maxConcurrentJobsPerProject = 1`() {
    // Given: Default limit of 1 job per project
    batchProperties.maxConcurrentJobsPerProject = 1

    // When: First job tries to lock
    val firstLockResult = lockManager.canLockJobForProject(job1.id)

    // Then: First job should succeed
    assertThat(firstLockResult).isTrue()
    assertThat(lockManager.getLockedJobsForProject(projectId)).containsExactly(job1.id)

    // When: Second job tries to lock
    val secondLockResult = lockManager.canLockJobForProject(job2.id)

    // Then: Second job should be rejected
    assertThat(secondLockResult).isFalse()
    assertThat(lockManager.getLockedJobsForProject(projectId)).containsExactly(job1.id)
  }

  @Test
  fun `should allow multiple jobs when maxConcurrentJobsPerProject = 2`() {
    // Given: Limit of 2 jobs per project
    batchProperties.maxConcurrentJobsPerProject = 2

    // When: First job locks
    val firstLockResult = lockManager.canLockJobForProject(job1.id)
    assertThat(firstLockResult).isTrue()

    // When: Second job locks
    val secondLockResult = lockManager.canLockJobForProject(job2.id)
    assertThat(secondLockResult).isTrue()

    // Then: Both jobs should be locked
    assertThat(lockManager.getLockedJobsForProject(projectId)).containsExactlyInAnyOrder(job1.id, job2.id)

    // When: Third job tries to lock
    val thirdLockResult = lockManager.canLockJobForProject(job3.id)

    // Then: Third job should be rejected (exceeds limit of 2)
    assertThat(thirdLockResult).isFalse()
    assertThat(lockManager.getLockedJobsForProject(projectId)).containsExactlyInAnyOrder(job1.id, job2.id)
  }

  @Test
  fun `should allow three jobs when maxConcurrentJobsPerProject = 3`() {
    // Given: Limit of 3 jobs per project
    batchProperties.maxConcurrentJobsPerProject = 3

    // When: Lock three jobs sequentially
    assertThat(lockManager.canLockJobForProject(job1.id)).isTrue()
    assertThat(lockManager.canLockJobForProject(job2.id)).isTrue()
    assertThat(lockManager.canLockJobForProject(job3.id)).isTrue()

    // Then: All three jobs should be locked
    assertThat(lockManager.getLockedJobsForProject(projectId))
      .containsExactlyInAnyOrder(job1.id, job2.id, job3.id)

    // When: Fourth job tries to lock
    val fourthLockResult = lockManager.canLockJobForProject(job4.id)

    // Then: Fourth job should be rejected (exceeds limit of 3)
    assertThat(fourthLockResult).isFalse()
    assertThat(lockManager.getLockedJobsForProject(projectId))
      .containsExactlyInAnyOrder(job1.id, job2.id, job3.id)
  }

  @Test
  fun `should allow relocking same job without counting against limit`() {
    // Given: Limit of 1 job per project
    batchProperties.maxConcurrentJobsPerProject = 1

    // When: Job locks initially
    assertThat(lockManager.canLockJobForProject(job1.id)).isTrue()

    // When: Same job tries to lock again
    val relockResult = lockManager.canLockJobForProject(job1.id)

    // Then: Same job should be allowed (doesn't count against limit)
    assertThat(relockResult).isTrue()
    assertThat(lockManager.getLockedJobsForProject(projectId)).containsExactly(job1.id)
  }

  @Test
  fun `should allow new job after unlocking when at limit`() {
    // Given: Limit of 2 jobs per project
    batchProperties.maxConcurrentJobsPerProject = 2

    // When: Lock maximum number of jobs
    assertThat(lockManager.canLockJobForProject(job1.id)).isTrue()
    assertThat(lockManager.canLockJobForProject(job2.id)).isTrue()
    assertThat(lockManager.canLockJobForProject(job3.id)).isFalse() // Should be rejected

    // When: Unlock one job
    lockManager.unlockJobForProject(projectId, job1.id)

    // Then: New job should now be allowed
    assertThat(lockManager.canLockJobForProject(job3.id)).isTrue()
    assertThat(lockManager.getLockedJobsForProject(projectId))
      .containsExactlyInAnyOrder(job2.id, job3.id)
  }

  @Test
  fun `should cleanup completed jobs and allow new locks`() {
    // Given: Limit of 1 job per project
    batchProperties.maxConcurrentJobsPerProject = 1

    // Mock job1 as completed, job2 as pending
    val completedJob = createMockJobDto(1L, projectId, BatchJobStatus.SUCCESS)
    every { batchJobService.getJobDto(1L) } returns completedJob

    // When: Manually add completed job to locks (simulating stale lock)
    lockManager.getMap()[projectId] = setOf(job1.id)

    // When: New job tries to lock (this should trigger cleanup)
    val lockResult = lockManager.canLockJobForProject(job2.id)

    // Then: Completed job should be cleaned up and new job allowed
    assertThat(lockResult).isTrue()
    assertThat(lockManager.getLockedJobsForProject(projectId)).containsExactly(job2.id)
  }

  @Test
  fun `should handle different projects independently`() {
    // Given: Limit of 1 job per project
    batchProperties.maxConcurrentJobsPerProject = 1
    val project2Id = 2L
    val job5 = createMockJobDto(5L, project2Id, BatchJobStatus.PENDING)
    every { batchJobService.getJobDto(5L) } returns job5
    every { batchJobService.getAllIncompleteJobIds(project2Id) } returns emptyList()

    // When: Lock one job per project
    assertThat(lockManager.canLockJobForProject(job1.id)).isTrue()
    assertThat(lockManager.canLockJobForProject(job5.id)).isTrue()

    // Then: Each project should have one locked job
    assertThat(lockManager.getLockedJobsForProject(projectId)).containsExactly(job1.id)
    assertThat(lockManager.getLockedJobsForProject(project2Id)).containsExactly(job5.id)

    // When: Try to add second job to first project
    assertThat(lockManager.canLockJobForProject(job2.id)).isFalse()

    // Then: First project limit should be enforced, second project unaffected
    assertThat(lockManager.getLockedJobsForProject(projectId)).containsExactly(job1.id)
    assertThat(lockManager.getLockedJobsForProject(project2Id)).containsExactly(job5.id)
  }

  private fun createMockJobDto(id: Long, projectId: Long, status: BatchJobStatus): BatchJobDto {
    return BatchJobDto(
      id = id,
      projectId = projectId,
      authorId = 1L,
      target = emptyList<Any>(),
      totalItems = 0,
      totalChunks = 0,
      chunkSize = 1000,
      status = status,
      type = io.tolgee.batch.data.BatchJobType.MACHINE_TRANSLATE,
      params = null,
      maxPerJobConcurrency = 1,
      jobCharacter = io.tolgee.batch.JobCharacter.FAST,
      hidden = false,
      debouncingKey = null,
      createdAt = System.currentTimeMillis(),
      lastDebouncingEvent = null,
      debounceDurationInMs = null,
      debounceMaxWaitTimeInMs = null
    )
  }
}