package io.tolgee.batch

import io.tolgee.batch.data.BatchJobDto
import io.tolgee.component.UsingRedisProvider
import io.tolgee.configuration.tolgee.BatchProperties
import io.tolgee.util.Logging
import io.tolgee.util.logger
import org.redisson.api.RMap
import org.redisson.api.RedissonClient
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Manages concurrent job execution limits per project.
 * Supports configurable number of concurrent jobs per project (default: 1 for backward compatibility).
 *
 * This class handles project-level job locking and concurrency control.
 */
@Component
class BatchJobProjectLockingManager(
  private val batchJobService: BatchJobService,
  private val batchProperties: BatchProperties,
  @Lazy
  private val redissonClient: RedissonClient,
  private val usingRedisProvider: UsingRedisProvider,
) : Logging {
  companion object {
    private val localProjectLocks by lazy {
      ConcurrentHashMap<Long, Set<Long>>()
    }
  }

  fun canLockJobForProject(batchJobId: Long): Boolean {
    val jobDto = batchJobService.getJobDto(batchJobId)
    if (!jobDto.type.exclusive) {
      return true
    }
    return tryLockJobForProject(jobDto)
  }

  private fun tryLockJobForProject(jobDto: BatchJobDto): Boolean {
    logger.debug("Trying to lock job ${jobDto.id} for project ${jobDto.projectId}")
    return if (usingRedisProvider.areWeUsingRedis) {
      tryLockWithRedisson(jobDto)
    } else {
      tryLockLocal(jobDto)
    }
  }

  fun unlockJobForProject(
    projectId: Long?,
    jobId: Long,
  ) {
    projectId ?: return
    getMap().compute(projectId) { _, lockedJobIds ->
      logger.debug("Unlocking job: $jobId for project $projectId")
      val currentJobs = lockedJobIds ?: emptySet()
      if (currentJobs.contains(jobId)) {
        val updatedJobs = currentJobs - jobId
        logger.debug("Unlocked job: $jobId for project $projectId. Remaining jobs: $updatedJobs")
        return@compute if (updatedJobs.isEmpty()) emptySet() else updatedJobs
      }
      logger.debug("Job: $jobId for project $projectId is not locked")
      return@compute currentJobs
    }
  }

  fun getMap(): ConcurrentMap<Long, Set<Long>> {
    if (usingRedisProvider.areWeUsingRedis) {
      return getRedissonProjectLocks()
    }
    return localProjectLocks
  }

  private fun tryLockWithRedisson(batchJobDto: BatchJobDto): Boolean {
    val projectId = batchJobDto.projectId ?: return true
    val computed =
      getRedissonProjectLocks().compute(projectId) { _, value ->
        computeFnBody(batchJobDto, value)
      }
    return computed?.contains(batchJobDto.id) ?: false
  }

  fun getLockedJobsForProject(projectId: Long): Set<Long> {
    if (usingRedisProvider.areWeUsingRedis) {
      return getRedissonProjectLocks()[projectId] ?: emptySet()
    }
    return localProjectLocks[projectId] ?: emptySet()
  }


  private fun tryLockLocal(toLock: BatchJobDto): Boolean {
    val projectId = toLock.projectId ?: return true
    val computed =
      localProjectLocks.compute(projectId) { _, value ->
        val newLocked = computeFnBody(toLock, value)
        logger.debug("While trying to lock ${toLock.id} for project ${toLock.projectId} new lock value is $newLocked")
        newLocked
      }
    return computed?.contains(toLock.id) ?: false
  }

  private fun computeFnBody(
    toLock: BatchJobDto,
    currentValue: Set<Long>?,
  ): Set<Long> {
    val projectId =
      toLock.projectId
        ?: throw IllegalStateException(
          "Project id is required. " +
            "Locking for project should not happen for non-project jobs.",
        )

    val currentJobs = currentValue ?: emptySet()

    // Job is already locked
    if (currentJobs.contains(toLock.id)) {
      logger.debug("Job ${toLock.id} is already locked for project $projectId")
      return currentJobs
    }

    // Clean up completed jobs first
    val activeJobs = cleanupCompletedJobs(projectId, currentJobs)

    // Check if we can add another job
    if (activeJobs.size < batchProperties.maxConcurrentJobsPerProject) {
      logger.debug("Locking job ${toLock.id} for project $projectId. Active jobs before: $activeJobs")
      return activeJobs + toLock.id
    }

    // value for the project is not initialized yet (migration from old format)
    if (currentValue == null) {
      logger.debug("Getting initial locked state from DB state")
      val initialJobs = getInitialJobIds(projectId)
      logger.debug("Initial locked jobs $initialJobs for project $projectId")
      if (initialJobs.isEmpty() || initialJobs.size < batchProperties.maxConcurrentJobsPerProject) {
        logger.debug("Space available, locking ${toLock.id}")
        return initialJobs + toLock.id
      }
      return initialJobs
    }

    logger.debug(
      "Cannot lock job ${toLock.id} for project $projectId. Max concurrent jobs (${batchProperties.maxConcurrentJobsPerProject}) reached. Active jobs: $activeJobs"
    )
    return activeJobs
  }

  private fun cleanupCompletedJobs(projectId: Long, currentJobs: Set<Long>): Set<Long> {
    if (currentJobs.isEmpty()) return emptySet()

    val stillRunningJobs = currentJobs.filter { jobId ->
      isJobStillRunning(jobId)
    }.toSet()

    if (stillRunningJobs.size != currentJobs.size) {
      logger.debug("Cleaned up completed jobs for project $projectId. Before: $currentJobs, After: $stillRunningJobs")
    }

    return stillRunningJobs
  }

  private fun isJobStillRunning(jobId: Long): Boolean {
    return try {
      val jobDto = batchJobService.getJobDto(jobId)
      when (jobDto.status) {
        io.tolgee.model.batch.BatchJobStatus.PENDING,
        io.tolgee.model.batch.BatchJobStatus.RUNNING -> true
        else -> false
      }
    } catch (e: Exception) {
      logger.debug("Job $jobId not found or error checking status, considering as completed", e)
      false
    }
  }

  private fun getInitialJobIds(projectId: Long): Set<Long> {
    val jobs = batchJobService.getAllIncompleteJobIds(projectId)
    val result = mutableSetOf<Long>()

    // First priority: Find actually RUNNING jobs from database
    // This prevents phantom locks from PENDING jobs that have chunks in queue but aren't executing
    val runningJobs = jobs.filter { job ->
      val jobDto = batchJobService.getJobDto(job.jobId)
      jobDto.status == io.tolgee.model.batch.BatchJobStatus.RUNNING
    }.take(batchProperties.maxConcurrentJobsPerProject)

    if (runningJobs.isNotEmpty()) {
      result.addAll(runningJobs.map { it.jobId })
      logger.debug("Found RUNNING jobs $result for project $projectId")
    }

    // If we don't have enough RUNNING jobs, look for started jobs (fallback logic)
    if (result.size < batchProperties.maxConcurrentJobsPerProject) {
      val unlockedChunkCounts =
        batchJobService
          .getAllUnlockedChunksForJobs(jobs.map { it.jobId })
          .groupBy { it.batchJobId }.map { it.key to it.value.count() }.toMap()

      // Find jobs that have already started processing
      val startedJobs = jobs.filter { job ->
        !result.contains(job.jobId) && // Don't include already running jobs
        job.totalChunks != unlockedChunkCounts[job.jobId]
      }.take(batchProperties.maxConcurrentJobsPerProject - result.size)

      if (startedJobs.isNotEmpty()) {
        result.addAll(startedJobs.map { it.jobId })
        logger.debug("Found additional started jobs, total jobs for project $projectId: $result")
      }
    }

    if (result.isEmpty()) {
      logger.debug("No RUNNING or started jobs found for project $projectId, allowing new jobs to acquire locks")
    }

    return result
  }


  private fun getRedissonProjectLocks(): RMap<Long, Set<Long>> {
    return redissonClient.getMap("project_batch_job_locks")
  }

  fun getLockedJobIds(): Set<Long> {
    return getMap().values.flatten().toSet()
  }
}
