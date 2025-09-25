package io.tolgee.api.v2.controllers

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.tolgee.batch.BatchJobProjectLockingManager
import io.tolgee.batch.BatchJobService
import io.tolgee.model.batch.BatchJobStatus
import io.tolgee.openApiDocs.OpenApiSelfHostedExtension
import io.tolgee.security.authentication.RequiresSuperAuthentication
import io.tolgee.util.Logging
import io.tolgee.util.logger
import org.springframework.hateoas.CollectionModel
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for managing project batch job locks
 */
@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping("/v2/administration")
@Tag(
  name = "Server Administration",
  description = "**Only for self-hosted instances** \n\n" +
    "Management of project-level batch job locks for debugging and maintenance."
)
@OpenApiSelfHostedExtension
class ProjectBatchLockController(
  private val batchJobProjectLockingManager: BatchJobProjectLockingManager,
  private val batchJobService: BatchJobService,
) : Logging {

  @GetMapping("/project-batch-locks")
  @Operation(
    summary = "Get all project batch locks",
    description = "Returns current project batch job locks from Redis or local storage based on configuration"
  )
  @RequiresSuperAuthentication
  fun getProjectLocks(): CollectionModel<ProjectLockModel> {
    logger.debug("Retrieving all project batch locks")
    
    val locks = batchJobProjectLockingManager.getMap()
    val lockModels = locks.map { (projectId, lockedJobId) ->
      val lockStatus = when (lockedJobId) {
        null -> LockStatus.UNINITIALIZED
        0L -> LockStatus.UNLOCKED
        else -> LockStatus.LOCKED
      }
      
      val jobInfo = if (lockedJobId != null && lockedJobId > 0L) {
        try {
          val jobDto = batchJobService.getJobDto(lockedJobId)
          JobInfo(
            jobId = jobDto.id,
            status = jobDto.status,
            type = jobDto.type,
            createdAt = jobDto.createdAt
          )
        } catch (e: Exception) {
          logger.warn("Could not retrieve job info for locked job $lockedJobId in project $projectId", e)
          null
        }
      } else {
        null
      }
      
      ProjectLockModel(
        projectId = projectId,
        lockedJobId = lockedJobId,
        lockStatus = lockStatus,
        jobInfo = jobInfo
      )
    }
    
    logger.debug("Retrieved ${lockModels.size} project batch locks")
    return CollectionModel.of(lockModels)
  }

  @PutMapping("/project-batch-locks/{projectId}/clear")
  @Operation(
    summary = "Clear lock for specific project",
    description = "Sets the project lock to explicitly unlocked state (0L)"
  )
  @RequiresSuperAuthentication
  fun clearProjectLock(@PathVariable projectId: Long): ResponseEntity<Void> {
    logger.debug("Clearing batch lock for project $projectId")
    
    val lockMap = batchJobProjectLockingManager.getMap()
    val previousValue = lockMap.put(projectId, 0L)
    
    logger.info("Cleared batch lock for project $projectId (previous value: $previousValue)")
    return ResponseEntity.ok().build()
  }

  @DeleteMapping("/project-batch-locks/{projectId}")
  @Operation(
    summary = "Remove lock entry for specific project", 
    description = "Completely removes the project lock entry, returning it to uninitialized state"
  )
  @RequiresSuperAuthentication
  fun removeProjectLock(@PathVariable projectId: Long): ResponseEntity<Void> {
    logger.debug("Removing batch lock entry for project $projectId")
    
    val lockMap = batchJobProjectLockingManager.getMap()
    val removedValue = lockMap.remove(projectId)
    
    logger.info("Removed batch lock entry for project $projectId (removed value: $removedValue)")
    return ResponseEntity.ok().build()
  }
}

/**
 * Model representing a project batch lock
 */
data class ProjectLockModel(
  val projectId: Long,
  val lockedJobId: Long?,
  val lockStatus: LockStatus,
  val jobInfo: JobInfo?
)

/**
 * Information about the locked job
 */
data class JobInfo(
  val jobId: Long,
  val status: BatchJobStatus,
  val type: io.tolgee.batch.data.BatchJobType,
  val createdAt: Long?
)

/**
 * Status of the project lock
 */
enum class LockStatus {
  /** Lock is explicitly cleared (value = 0L) */
  UNLOCKED,
  /** Lock has never been initialized (value = null) */
  UNINITIALIZED, 
  /** Lock is held by a specific job (value = jobId) */
  LOCKED
}