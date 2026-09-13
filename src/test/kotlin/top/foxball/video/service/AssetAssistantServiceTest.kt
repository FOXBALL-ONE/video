package top.foxball.video.service

import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.AiModelProviderConfig
import top.foxball.video.entity.jdbc.AiTemplate
import top.foxball.video.entity.jdbc.AiTemplateVersion
import top.foxball.video.entity.jdbc.AssetStatus
import top.foxball.video.entity.jdbc.CharacterProfile
import top.foxball.video.entity.jdbc.Episode
import top.foxball.video.entity.jdbc.EpisodeAsset
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.PropProfile
import top.foxball.video.entity.jdbc.SceneProfile
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.repository.CharacterProfileRepository
import top.foxball.video.repository.EpisodeAssetRepository
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.PropProfileRepository
import top.foxball.video.repository.SceneProfileRepository

class AssetAssistantServiceTest {
    @Test
    fun `applyPatches writes common fields and existing profile fields`() {
        val fixture = Fixture()
        val character = EpisodeAsset(id = 1, projectId = 7, episodeId = 3, name = "林夏", type = EpisodeAssetType.CHARACTER, status = AssetStatus.DRAFT)
        val scene = EpisodeAsset(id = 2, projectId = 7, episodeId = 3, name = "雨夜街道", type = EpisodeAssetType.SCENE_BACKGROUND)
        fixture.assets(character, scene)
        val characterProfile = CharacterProfile(projectId = 7, episodeId = 3, episodeAssetId = 1, hairstyle = "齐肩短发")
        fixture.characters[1L] = characterProfile
        fixture.scenes[2L] = SceneProfile(projectId = 7, episodeId = 3, episodeAssetId = 2, location = "外景")

        val result = assertNotNull(
            fixture.service.applyPatches(
                7,
                9,
                3,
                listOf(
                    AssetAssistantService.ApplyItem(1, "identity_title", "助理研究员"),
                    AssetAssistantService.ApplyItem(1, "face_description", "圆脸，单眼皮"),
                    AssetAssistantService.ApplyItem(1, "status", "READY"),
                    AssetAssistantService.ApplyItem(2, "location", "外景 · 老街"),
                ),
            ),
        )

        assertEquals(4, result.applied)
        assertEquals(0, result.rejected)
        assertEquals("助理研究员", characterProfile.identityTitle)
        assertEquals("圆脸，单眼皮", characterProfile.faceDescription)
        assertEquals(AssetStatus.READY, character.status)
        assertEquals("外景 · 老街", fixture.scenes.getValue(2L).location)
        assertEquals("齐肩短发", characterProfile.hairstyle)
        Mockito.verify(fixture.characterProfileRepository).save(characterProfile)
        Mockito.verify(fixture.sceneProfileRepository).save(fixture.scenes.getValue(2L))
        Mockito.verify(fixture.episodeAssetRepository).save(character)
    }

    @Test
    fun `applyPatches creates the profile row for a type that has none yet`() {
        val fixture = Fixture()
        val prop = EpisodeAsset(id = 5, projectId = 7, episodeId = 3, name = "旧相机", type = EpisodeAssetType.PROP)
        fixture.assets(prop)

        val result = assertNotNull(
            fixture.service.applyPatches(7, 9, 3, listOf(AssetAssistantService.ApplyItem(5, "plot_function", "推动真相揭露"))),
        )

        assertEquals(1, result.applied)
        val captor = ArgumentCaptor.forClass(PropProfile::class.java)
        Mockito.verify(fixture.propProfileRepository).save(captor.capture())
        assertEquals(7L, captor.value.projectId)
        assertEquals(3L, captor.value.episodeId)
        assertEquals(5L, captor.value.episodeAssetId)
        assertEquals("推动真相揭露", captor.value.plotFunction)
    }

    @Test
    fun `applyPatches rejects unknown fields, stale writes and no-op writes`() {
        val fixture = Fixture()
        val prop = EpisodeAsset(id = 5, projectId = 7, episodeId = 3, name = "旧相机", type = EpisodeAssetType.PROP, description = "黑色机身")
        fixture.assets(prop)
        fixture.props[5L] = PropProfile(projectId = 7, episodeId = 3, episodeAssetId = 5, category = "道具")

        val result = assertNotNull(
            fixture.service.applyPatches(
                7,
                9,
                3,
                listOf(
                    AssetAssistantService.ApplyItem(5, "face_description", "道具没有这个字段"),
                    AssetAssistantService.ApplyItem(5, "category", "道具"),
                    AssetAssistantService.ApplyItem(5, "description", "银色机身", "其他人改之前的旧值"),
                    AssetAssistantService.ApplyItem(5, "description", "黑色机身"),
                    AssetAssistantService.ApplyItem(404, "description", "不存在"),
                ),
            ),
        )

        assertEquals(0, result.applied)
        assertEquals(2, result.unchanged)
        assertEquals(3, result.rejected)
        assertEquals(
            listOf("FIELD_NOT_ALLOWED", "UNCHANGED", "ASSET_FIELD_CHANGED", "UNCHANGED", "ASSET_NOT_FOUND"),
            result.items.map { it.code },
        )
        // 陈旧值被拒绝时回传服务端当前取值，前端据此刷新后再让用户确认。
        assertEquals("黑色机身", result.items[2].currentValue)
        assertEquals("黑色机身", prop.description)
        assertEquals("道具", fixture.props.getValue(5L).category)
        Mockito.verify(fixture.episodeAssetRepository, Mockito.never()).save(Mockito.any<EpisodeAsset>())
        Mockito.verify(fixture.propProfileRepository, Mockito.never()).save(Mockito.any<PropProfile>())
    }

    @Test
    fun `applyPatches refuses callers without write access`() {
        val fixture = Fixture()
        Mockito.`when`(fixture.taskCenterService.canWriteProject(7, 9)).thenReturn(false)
        fixture.assets(EpisodeAsset(id = 1, projectId = 7, episodeId = 3, name = "林夏", type = EpisodeAssetType.CHARACTER))

        assertNull(fixture.service.applyPatches(7, 9, 3, listOf(AssetAssistantService.ApplyItem(1, "description", "新的描述"))))
    }

    @Test
    fun `propose maps model patches onto server-side current values`() {
        val fixture = Fixture()
        val character = EpisodeAsset(id = 1, projectId = 7, episodeId = 3, name = "林夏", type = EpisodeAssetType.CHARACTER, description = "旧描述")
        val prop = EpisodeAsset(id = 2, projectId = 7, episodeId = 3, name = "旧相机", type = EpisodeAssetType.PROP)
        fixture.assets(character, prop)
        fixture.characters[1L] = CharacterProfile(projectId = 7, episodeId = 3, episodeAssetId = 1, hairstyle = "齐肩短发")
        fixture.modelReplies(
            """
            {
              "reply": "本集角色缺人脸描述，道具缺剧情作用。",
              "patches": [
                {"asset_id": 1, "field": "face_description", "value": "鹅蛋脸，细眉", "reason": "补充人脸描述"},
                {"asset_id": 1, "field": "hairstyle", "value": "齐肩短发", "reason": "与当前一致"},
                {"asset_id": 1, "field": "not_a_field", "value": "x", "reason": "未知字段"},
                {"asset_id": 2, "field": "plot_function", "value": "  推动真相揭露  ", "reason": "补全剧情作用"},
                {"asset_id": 99, "field": "description", "value": "不存在", "reason": "越界"}
              ],
              "warnings": ["角色缺少参考图"],
              "suggestions": ["检查本集资产缺口"]
            }
            """.trimIndent(),
        )

        val proposal = assertNotNull(
            fixture.service.propose(
                7,
                9,
                3,
                AssetAssistantService.MessageRequest(providerConfigId = 11, model = "gpt-x", message = "帮我检查本集资产"),
            ),
        )

        assertEquals(3, proposal.patches.size)
        val face = proposal.patches[0]
        assertEquals(1L, face.assetId)
        assertEquals("林夏", face.assetName)
        assertEquals("CHARACTER", face.assetType)
        assertEquals("人脸描述", face.fieldLabel)
        assertEquals("", face.currentValue)
        assertTrue(face.changed)
        assertFalse(proposal.patches[1].changed)
        assertEquals("齐肩短发", proposal.patches[1].currentValue)
        assertEquals("推动真相揭露", proposal.patches[2].value)
        assertTrue(proposal.warnings.any { it.contains("未知字段") }, proposal.warnings.toString())
        assertTrue(proposal.warnings.any { it.contains("不存在资产") }, proposal.warnings.toString())
        assertTrue(proposal.warnings.any { it.contains("角色缺少参考图") }, proposal.warnings.toString())
        assertEquals(listOf("检查本集资产缺口"), proposal.suggestions)
        assertEquals(2, proposal.context.assetCount)
        assertEquals(mapOf("CHARACTER" to 1, "PROP" to 1), proposal.context.typeCounts)
        assertEquals(0, proposal.context.completeness)
        assertEquals("asset_list_assistant", proposal.templateKey)
    }

    @Test
    fun `propose falls back to built-in suggestions and asks for assets when the episode has none`() {
        val fixture = Fixture()
        fixture.assets(EpisodeAsset(id = 1, projectId = 7, episodeId = 3, name = "林夏", type = EpisodeAssetType.CHARACTER, status = AssetStatus.READY))
        fixture.modelReplies("""{"reply":"资产已就绪。","patches":[],"warnings":[],"suggestions":[]}""")

        val proposal = assertNotNull(
            fixture.service.propose(
                7,
                9,
                3,
                AssetAssistantService.MessageRequest(providerConfigId = 11, model = "gpt-x", message = "检查资产"),
            ),
        )

        assertEquals(100, proposal.context.completeness)
        assertTrue(proposal.suggestions.isNotEmpty())

        val empty = Fixture()
        empty.assets()
        val error = runCatching {
            empty.service.propose(
                7,
                9,
                3,
                AssetAssistantService.MessageRequest(providerConfigId = 11, model = "gpt-x", message = "检查资产"),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "应提示先添加资产：$error")
    }

    @Test
    fun `propose refuses callers without read access`() {
        val fixture = Fixture()
        Mockito.`when`(fixture.taskCenterService.canReadProject(7, 9)).thenReturn(false)

        assertNull(
            fixture.service.propose(
                7,
                9,
                3,
                AssetAssistantService.MessageRequest(providerConfigId = 11, model = "gpt-x", message = "检查资产"),
            ),
        )
    }

    private class Fixture {
        val episodeRepository: EpisodeRepository = Mockito.mock(EpisodeRepository::class.java)
        val episodeAssetRepository: EpisodeAssetRepository = Mockito.mock(EpisodeAssetRepository::class.java)
        val characterProfileRepository: CharacterProfileRepository = Mockito.mock(CharacterProfileRepository::class.java)
        val sceneProfileRepository: SceneProfileRepository = Mockito.mock(SceneProfileRepository::class.java)
        val propProfileRepository: PropProfileRepository = Mockito.mock(PropProfileRepository::class.java)
        val providerConfigRepository: AiModelProviderConfigRepository = Mockito.mock(AiModelProviderConfigRepository::class.java)
        val taskCenterService: TaskCenterService = Mockito.mock(TaskCenterService::class.java)
        val templateCatalog: TemplateCatalogService = Mockito.mock(TemplateCatalogService::class.java)
        val objectMapper = ObjectMapper()

        /** 渲染与输出校验使用真实实现：模板变量契约与模型输出契约在测试中同样被执行。 */
        val templateRenderer = TemplateRenderer(objectMapper, TemplateSchemaValidator(objectMapper))
        val templateSchemaValidator = TemplateSchemaValidator(objectMapper)
        val templateRunAuditService: TemplateRunAuditService = Mockito.mock(TemplateRunAuditService::class.java)
        val characters = mutableMapOf<Long, CharacterProfile>()
        val scenes = mutableMapOf<Long, SceneProfile>()
        val props = mutableMapOf<Long, PropProfile>()

        var modelReply: String = "{}"

        /**
         * 用默认 Answer 而不是参数匹配器：Mockito 的参数匹配器传入 Kotlin 非空参数时会被插入空值检查。
         */
        val templateAgentRunner: TemplateAgentRunner = Mockito.mock(
            TemplateAgentRunner::class.java,
            object : Answer<Any?> {
                override fun answer(invocation: InvocationOnMock): Any? = when (invocation.method.name) {
                    "chat" -> TemplateAgentRunner.ChatOutcome(modelReply, "id", "gpt-x", 100, 50, 1, listOf("asset_supervisor"), emptyList(), 1)
                    "toString" -> "TemplateAgentRunner"
                    "hashCode" -> System.identityHashCode(invocation.mock)
                    "equals" -> invocation.arguments.firstOrNull() === invocation.mock
                    else -> null
                }
            },
        )

        val service = AssetAssistantService(
            episodeRepository,
            episodeAssetRepository,
            characterProfileRepository,
            sceneProfileRepository,
            propProfileRepository,
            providerConfigRepository,
            taskCenterService,
            templateCatalog,
            templateRenderer,
            templateSchemaValidator,
            templateAgentRunner,
            templateRunAuditService,
            objectMapper,
        )

        init {
            Mockito.`when`(taskCenterService.canReadProject(7, 9)).thenReturn(true)
            Mockito.`when`(taskCenterService.canWriteProject(7, 9)).thenReturn(true)
            Mockito.`when`(episodeRepository.findByIdAndProjectId(3, 7))
                .thenReturn(Episode(id = 3, projectId = 7, episodeNo = 2, title = "雨夜来信"))
            Mockito.`when`(providerConfigRepository.findById(11))
                .thenReturn(Optional.of(AiModelProviderConfig(id = 11, provider = "openai", enabled = true)))
            Mockito.`when`(templateCatalog.resolve("asset_list_assistant", 7, 9, "zh-CN"))
                .thenReturn(TemplateCatalogService.ResolvedTemplate(AiTemplate(key = "asset_list_assistant"), templateVersion))
            Mockito.`when`(characterProfileRepository.findAllByProjectIdAndEpisodeId(7, 3)).thenAnswer { characters.values.toList() }
            Mockito.`when`(sceneProfileRepository.findAllByProjectIdAndEpisodeId(7, 3)).thenAnswer { scenes.values.toList() }
            Mockito.`when`(propProfileRepository.findAllByProjectIdAndEpisodeId(7, 3)).thenAnswer { props.values.toList() }
            Mockito.`when`(characterProfileRepository.save(Mockito.any<CharacterProfile>())).thenAnswer { it.getArgument<CharacterProfile>(0) }
            Mockito.`when`(sceneProfileRepository.save(Mockito.any<SceneProfile>())).thenAnswer { it.getArgument<SceneProfile>(0) }
            Mockito.`when`(propProfileRepository.save(Mockito.any<PropProfile>())).thenAnswer { it.getArgument<PropProfile>(0) }
        }

        fun assets(vararg items: EpisodeAsset) {
            Mockito.`when`(episodeAssetRepository.findAllByProjectIdAndEpisodeIdOrderByUpdatedAtDesc(7, 3)).thenReturn(items.toList())
        }

        fun modelReplies(text: String) {
            modelReply = text
        }

        private companion object {
            /** 与内置模板同构的精简版本：只保留服务调用路径需要覆盖的变量与输出契约。 */
            val templateVersion = AiTemplateVersion(
                id = 4,
                templateId = 1,
                contentHash = "sha256:test",
                messagesJson = """[
                  {"role":"system","content":"你是资产统筹助手。"},
                  {"role":"user","content":"<context>{{episode_context_json}}</context>\n<assets>{{asset_list_json}}</assets>\n<fields>{{field_catalog_json}}</fields>\n<history>{{conversation_json}}</history>\n<request>{{user_request}}</request>\n<language>{{output_language}}</language>"}
                ]""",
                variablesSchemaJson = """{"type":"object","properties":{"episode_context_json":{"type":"string","required":true,"maxLength":20000},"asset_list_json":{"type":"string","required":true,"maxLength":200000},"field_catalog_json":{"type":"string","required":true,"maxLength":20000},"conversation_json":{"type":"string","maxLength":20000,"default":"[]"},"user_request":{"type":"string","required":true,"maxLength":8000},"output_language":{"type":"string","enum":["zh-CN","en-US"],"default":"zh-CN"}},"required":["episode_context_json","asset_list_json","field_catalog_json","user_request"]}""",
                outputSchemaJson = """{"type":"object","properties":{"reply":{"type":"string","maxLength":8000},"patches":{"type":"array","maxItems":50,"items":{"type":"object"}},"warnings":{"type":"array","maxItems":50,"items":{"type":"string"}},"suggestions":{"type":"array","maxItems":8,"items":{"type":"string"}}},"required":["reply","patches","warnings","suggestions"]}""",
                generationPolicyJson = """{"temperature":0.2,"max_completion_tokens":6000,"max_input_tokens":60000,"response_format":"json_object"}""",
            )
        }
    }
}
