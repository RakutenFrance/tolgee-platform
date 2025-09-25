package io.tolgee.api.v2.controllers

import io.tolgee.batch.BatchJobProjectLockingManager
import io.tolgee.batch.BatchJobService
import io.tolgee.batch.data.BatchJobDto
import io.tolgee.batch.data.BatchJobType
import io.tolgee.development.testDataBuilder.data.AdministrationTestData
import io.tolgee.fixtures.andIsOk
import io.tolgee.fixtures.andIsUnauthorized
import io.tolgee.model.UserAccount
import io.tolgee.model.batch.BatchJobStatus
import io.tolgee.testing.AuthorizedControllerTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.util.concurrent.ConcurrentHashMap

class ProjectBatchLockControllerTest : AuthorizedControllerTest() {

  @MockBean
  private lateinit var batchJobProjectLockingManager: BatchJobProjectLockingManager

  @MockBean  
  private lateinit var batchJobService: BatchJobService

  lateinit var testData: AdministrationTestData

  @BeforeEach
  fun createData() {
    testData = AdministrationTestData()
    testDataService.saveTestData(testData.root)
    userAccount = testData.admin
  }

  @Test
  fun `GET project-batch-locks returns unauthorized without super auth`() {
    // Test without admin user
    userAccount = testData.user
    performAuthGet("/v2/administration/project-batch-locks")
      .andIsUnauthorized
  }

  @Test
  fun `GET project-batch-locks returns locks with super auth`() {
    val testLocks = ConcurrentHashMap<Long, Long?>().apply {
      put(1L, 123L)  // Project 1 locked to job 123
      put(2L, 0L)    // Project 2 explicitly unlocked  
      put(3L, null)  // Project 3 uninitialized
    }
    
    whenever(batchJobProjectLockingManager.getMap()).thenReturn(testLocks)
    
    // Mock job info for locked job
    val mockJobDto = BatchJobDto(
      id = 123L,
      projectId = 1L,
      authorId = 1L,
      target = emptyList(),
      totalItems = 100,
      totalChunks = 10,
      chunkSize = 10,
      status = BatchJobStatus.RUNNING,
      type = BatchJobType.MACHINE_TRANSLATE,
      params = null,
      maxPerJobConcurrency = 1,
      jobCharacter = io.tolgee.batch.JobCharacter.FAST,
      hidden = false,
      debouncingKey = null,
      createdAt = System.currentTimeMillis()
    )
    
    whenever(batchJobService.getJobDto(123L)).thenReturn(mockJobDto)

    performAuthGet("/v2/administration/project-batch-locks")
      .andIsOk
  }

  @Test
  fun `PUT clear project lock works with super auth`() {
    val testLocks = ConcurrentHashMap<Long, Long?>()
    whenever(batchJobProjectLockingManager.getMap()).thenReturn(testLocks)

    performAuthPut("/v2/administration/project-batch-locks/123/clear", null)
      .andIsOk
  }

  @Test
  fun `DELETE project lock works with super auth`() {
    val testLocks = ConcurrentHashMap<Long, Long?>().apply {
      put(123L, 456L)
    }
    whenever(batchJobProjectLockingManager.getMap()).thenReturn(testLocks)

    performAuthDelete("/v2/administration/project-batch-locks/123")
      .andIsOk
  }
}