package top.foxball.video.service

import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.entity.jdbc.AiModelProviderConfig
import top.foxball.video.entity.jdbc.AiTemplate
import top.foxball.video.entity.jdbc.AiTemplateVersion
import top.foxball.video.entity.jdbc.CharacterProfile
import top.foxball.video.entity.jdbc.EpisodeAsset
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ProductionTaskType
import top.foxball.video.entity.jdbc.TemplateCategory
import top.foxball.video.entity.jdbc.TemplateScopeType
import top.foxball.video.entity.jdbc.TemplateVersionStatus
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.repository.ProductionTaskRepository

class CharacterThreeViewMergeTaskServiceTest {
    @Test
    fun `submit snapshots template and source files`() {
        val fixture = Fixture()
        val frontId = UUID.randomUUID().toString()
        val sideId = UUID.randomUUID().toString()
        val backId = UUID.randomUUID().toString()
        val profile = CharacterProfile(
            projectId = 1,
            episodeId = 2,
            episodeAssetId = 3,
            threeViewFrontFileId = frontId,
            threeViewSideFileId = sideId,
            threeViewBackFileId = backId,
        )
        Mockito.`when`(fixture.taskCenterService.canWriteProject(1, 9)).thenReturn(true)
        Mockito.`when`(fixture.assetService.loadCharacterProfile(1, 2, 3)).thenReturn(
            AssetService.CharacterProfileView(
                EpisodeAsset(id = 3, projectId = 1, episodeId = 2, name = "角色", type = EpisodeAssetType.CHARACTER),
                profile,
            ),
        )
        Mockito.`when`(fixture.providerRepository.findById(4)).thenReturn(
            Optional.of(AiModelProviderConfig(id = 4, enabled = true, model = mapOf("api_type" to "IMAGE"))),
        )
        val template = AiTemplate(id = 11, key = "character_three_view_merge", displayName = "三视图合并", scopeType = TemplateScopeType.GLOBAL)
        val version = AiTemplateVersion(id = 12, templateId = 11, status = TemplateVersionStatus.PUBLISHED, contentHash = "sha256:test")
        Mockito.`when`(fixture.templateCatalog.resolve("character_three_view_merge", 1, 9, "zh-CN")).thenReturn(
            TemplateCatalogService.ResolvedTemplate(template, version),
        )
        Mockito.`when`(fixture.taskRepository.save(Mockito.any(ProductionTask::class.java))).thenAnswer { invocation ->
            invocation.getArgument<ProductionTask>(0).also { it.id = 77 }
        }

        try {
            val submitted = requireNotNull(fixture.service.submit(1, 9, 2, 3, 4, "image-model", "merge-once"))

            assertEquals(false, submitted.existing)
            assertEquals(ProductionTaskType.IMAGE_EDIT, submitted.task.taskType)
            assertEquals(listOf(frontId, sideId, backId), submitted.task.inputPayload["source_file_ids"])
            assertEquals(12L, submitted.task.inputPayload["prompt_version_id"])
            assertEquals("sha256:test", submitted.task.inputPayload["template_hash"])
            assertEquals(3L, submitted.task.inputPayload["episode_asset_id"])
        } finally {
            fixture.service.shutdown()
        }
    }

    @Test
    fun `submit returns matching idempotent merge task`() {
        val fixture = Fixture()
        val frontId = UUID.randomUUID().toString()
        val sideId = UUID.randomUUID().toString()
        val backId = UUID.randomUUID().toString()
        val existing = ProductionTask(
            id = 77,
            projectId = 1,
            createdBy = 9,
            episodeId = 2,
            taskType = ProductionTaskType.IMAGE_EDIT,
            status = ProductionTaskStatus.QUEUED,
            providerConfigId = 4,
            modelName = "image-model",
            idempotencyKey = "merge-once",
            inputPayload = mapOf(
                "prompt_key" to "character_three_view_merge",
                "episode_asset_id" to 3,
                "source_file_ids" to listOf(frontId, sideId, backId),
            ),
        )
        Mockito.`when`(fixture.taskCenterService.canWriteProject(1, 9)).thenReturn(true)
        Mockito.`when`(fixture.assetService.loadCharacterProfile(1, 2, 3)).thenReturn(
            AssetService.CharacterProfileView(
                EpisodeAsset(id = 3, projectId = 1, episodeId = 2, name = "角色", type = EpisodeAssetType.CHARACTER),
                CharacterProfile(
                    projectId = 1,
                    episodeId = 2,
                    episodeAssetId = 3,
                    threeViewFrontFileId = frontId,
                    threeViewSideFileId = sideId,
                    threeViewBackFileId = backId,
                ),
            ),
        )
        Mockito.`when`(fixture.providerRepository.findById(4)).thenReturn(
            Optional.of(AiModelProviderConfig(id = 4, enabled = true, model = mapOf("api_type" to "IMAGE"))),
        )
        Mockito.`when`(
            fixture.taskRepository.findFirstByProjectIdAndEpisodeIdAndTaskTypeAndIdempotencyKey(
                1, 2, ProductionTaskType.IMAGE_EDIT, "merge-once",
            ),
        ).thenReturn(existing)

        try {
            val submitted = requireNotNull(fixture.service.submit(1, 9, 2, 3, 4, "image-model", "merge-once"))

            assertEquals(true, submitted.existing)
            assertSame(existing, submitted.task)
            Mockito.verifyNoInteractions(fixture.templateCatalog)
        } finally {
            fixture.service.shutdown()
        }
    }

    @Test
    fun `stored image editing rejects non image files before invoking ai`() {
        val aiInvocationService = Mockito.mock(AiInvocationService::class.java)
        val fileService = Mockito.mock(FileService::class.java)
        val fileId = UUID.randomUUID()
        Mockito.`when`(fileService.readForInternalUse(fileId)).thenReturn(
            StoredFileContent("reference.txt", "text/plain; charset=UTF-8", "not-an-image".toByteArray()),
        )
        val service = AiMediaGenerationService(
            aiInvocationService,
            fileService,
            Mockito.mock(AssetService::class.java),
            Mockito.mock(TaskCenterService::class.java),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            service.editStoredImages(9, 4, "image-model", "merge", listOf(fileId.toString())).block()
        }

        assertEquals("第 1 张资产参考图不是图片文件", exception.message)
        Mockito.verifyNoInteractions(aiInvocationService)
    }

    private class Fixture {
        val taskRepository: ProductionTaskRepository = Mockito.mock(ProductionTaskRepository::class.java)
        val providerRepository: AiModelProviderConfigRepository = Mockito.mock(AiModelProviderConfigRepository::class.java)
        val taskCenterService: TaskCenterService = Mockito.mock(TaskCenterService::class.java)
        val assetService: AssetService = Mockito.mock(AssetService::class.java)
        val templateCatalog: TemplateCatalogService = Mockito.mock(TemplateCatalogService::class.java)
        val templateRenderer: TemplateRenderer = Mockito.mock(TemplateRenderer::class.java)
        val templateAgentRunner: TemplateAgentRunner = Mockito.mock(TemplateAgentRunner::class.java)
        val templateRunAuditService: TemplateRunAuditService = Mockito.mock(TemplateRunAuditService::class.java)
        val service = CharacterThreeViewMergeTaskService(
            taskRepository,
            providerRepository,
            taskCenterService,
            assetService,
            Mockito.mock(AiMediaGenerationService::class.java),
            templateCatalog,
            templateRenderer,
            templateAgentRunner,
            templateRunAuditService,
            Mockito.mock(FileService::class.java),
        )
    }
}
