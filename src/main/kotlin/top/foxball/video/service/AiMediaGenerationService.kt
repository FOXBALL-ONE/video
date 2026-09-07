package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import top.foxball.video.ai.AiGeneratedImage
import top.foxball.video.ai.AiImageEditCommand
import top.foxball.video.ai.AiImageGenerationCommand
import top.foxball.video.ai.AiImageInput
import top.foxball.video.ai.AiImageResponseFormat
import top.foxball.video.ai.AiImageContentDetector
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.ai.AiProtocolException
import top.foxball.video.ai.AiResultDownloader
import top.foxball.video.ai.AiVideoGenerationCommand
import top.foxball.video.ai.AiVideoStatus
import top.foxball.video.handler.ParamErrorException
import top.foxball.video.handler.ForbiddenException
import top.foxball.video.handler.ResourceNotFoundException
import java.util.Base64
import java.util.UUID

/** 面向 HTTP 管理接口的图片/视频调用门面；生成结果统一落入现有文件服务存储。 */
@Service
class AiMediaGenerationService(
    private val aiInvocationService: AiInvocationService,
    private val fileService: FileService,
    private val assetService: AssetService,
    private val taskCenterService: TaskCenterService,
) {
    private val log = LoggerFactory.getLogger(AiMediaGenerationService::class.java)

    fun generateImage(
        ownerId: Long,
        providerConfigId: Long,
        model: String,
        prompt: String,
        count: Int?,
        size: String?,
        quality: String?,
    ): Mono<List<FileDetails>> {
        return aiInvocationService.generateImage(
            AiImageGenerationCommand(
                providerConfigId = providerConfigId,
                model = model.trim(),
                prompt = prompt,
                count = count,
                size = size,
                quality = quality,
                responseFormat = AiImageResponseFormat.B64_JSON,
            ),
        ).flatMap { result ->
            if (result.images.isEmpty()) Mono.error(AiProtocolException("图片服务未返回图片"))
            else storeImages(ownerId, providerConfigId, result.images, "ai-image")
        }
    }

    fun generateVideo(
        ownerId: Long,
        providerConfigId: Long,
        model: String,
        prompt: String,
        size: String,
        seconds: Int,
        inputReference: MultipartFile?,
    ): Mono<String> {
        val reference = inputReference?.let {
            AiImageInput(it.originalFilename ?: "reference-image", it.contentType ?: "image/png", it.bytes)
        }
        return aiInvocationService.createVideo(
            AiVideoGenerationCommand(providerConfigId, model.trim(), prompt, size, seconds, reference),
        ).switchIfEmpty(Mono.error(AiProtocolException("视频服务未返回任务"))).map { it.id }
    }

    fun editImage(
        ownerId: Long,
        providerConfigId: Long,
        model: String,
        prompt: String,
        images: List<MultipartFile>,
        mask: MultipartFile?,
        count: Int?,
        size: String?,
    ): Mono<List<FileDetails>> {
        val inputs = images.map { file ->
            AiImageInput(file.originalFilename ?: "image", file.contentType ?: "image/png", file.bytes)
        }
        val maskInput = mask?.let { file ->
            AiImageInput(file.originalFilename ?: "mask", file.contentType ?: "image/png", file.bytes)
        }
        return aiInvocationService.editImage(
            AiImageEditCommand(
                providerConfigId = providerConfigId,
                model = model.trim(),
                prompt = prompt,
                images = inputs,
                mask = maskInput,
                count = count,
                size = size,
                responseFormat = AiImageResponseFormat.B64_JSON,
            ),
        ).flatMap { result ->
            if (result.images.isEmpty()) Mono.error(AiProtocolException("图片编辑服务未返回图片"))
            else storeImages(ownerId, providerConfigId, result.images, "ai-image-edit")
        }
    }

    /** 按数据库中的资产描述生成图片；调用方不需要提交提示词。 */
    fun generateAssetImage(
        ownerId: Long,
        projectId: Long,
        episodeId: Long,
        assetId: Long,
        providerConfigId: Long,
        model: String,
        target: String,
        count: Int?,
        size: String?,
    ): Mono<List<FileDetails>> =
        requireProjectWriteAccess(projectId, ownerId).doOnSuccess {
            log.info("资产图片生成权限校验通过：userId={} projectId={} episodeId={} assetId={} target={}", ownerId, projectId, episodeId, assetId, target)
        }.doOnError { exception ->
            log.error("资产图片生成权限校验失败：userId={} projectId={} episodeId={} assetId={} target={} exceptionType={} message={}", ownerId, projectId, episodeId, assetId, target, exception.javaClass.name, exception.message, exception)
        }.flatMap {
        Mono.fromCallable {
            buildAssetImageRequest(projectId, episodeId, assetId, target).also { request ->
                log.info("资产图片提示词构造完成：projectId={} episodeId={} assetId={} target={} normalizedTarget={} referenceCount={}", projectId, episodeId, assetId, target, request.target, request.referenceFileIds.size)
            }
        }.subscribeOn(Schedulers.boundedElastic()).flatMap { request ->
            log.info("开始调用图片模型：userId={} providerConfigId={} model={} projectId={} episodeId={} assetId={} target={} count={} size={}", ownerId, providerConfigId, model, projectId, episodeId, assetId, request.target, count ?: 1, size ?: DEFAULT_IMAGE_SIZE)
            generateImage(ownerId, providerConfigId, model, request.prompt, count, size?.ifBlank { DEFAULT_IMAGE_SIZE } ?: DEFAULT_IMAGE_SIZE, null)
                .doOnSuccess { files -> files?.let { value -> log.info("图片模型及文件保存完成：projectId={} episodeId={} assetId={} target={} fileCount={} fileIds={}", projectId, episodeId, assetId, request.target, value.size, value.map { it.file.id }) } }
                .doOnError { exception -> log.error("图片模型或文件保存失败：projectId={} episodeId={} assetId={} target={} exceptionType={} message={}", projectId, episodeId, assetId, request.target, exception.javaClass.name, exception.message, exception) }
        }
        }

    /** 按数据库中的资产描述和已保存参考图执行图生图。 */
    fun editAssetImage(
        ownerId: Long,
        projectId: Long,
        episodeId: Long,
        assetId: Long,
        providerConfigId: Long,
        model: String,
        target: String,
        count: Int?,
        size: String?,
    ): Mono<List<FileDetails>> =
        requireProjectWriteAccess(projectId, ownerId).flatMap {
        Mono.fromCallable {
            val request = buildAssetImageRequest(projectId, episodeId, assetId, target)
            val inputs = request.referenceFileIds.map { fileId ->
                val uuid = runCatching { UUID.fromString(fileId) }
                    .getOrElse { throw ParamErrorException("资产参考图文件 ID 不正确") }
                val content = fileService.readOwned(ownerId, uuid)
                AiImageInput(
                    content.originalFilename.ifBlank { "asset-reference.png" },
                    content.contentType?.takeIf { it.startsWith("image/") } ?: "image/png",
                    content.bytes,
                )
            }
            if (inputs.isEmpty()) throw ParamErrorException("资产尚无已保存参考图，无法执行图生图")
            AiImageEditCommand(
                providerConfigId = providerConfigId,
                model = model.trim(),
                prompt = request.prompt,
                images = inputs,
                count = count,
                size = size?.ifBlank { DEFAULT_IMAGE_SIZE } ?: DEFAULT_IMAGE_SIZE,
                responseFormat = AiImageResponseFormat.B64_JSON,
            )
        }.subscribeOn(Schedulers.boundedElastic()).flatMap { command ->
            aiInvocationService.editImage(command).flatMap { result ->
                if (result.images.isEmpty()) Mono.error(AiProtocolException("图片编辑服务未返回图片"))
                else storeImages(ownerId, providerConfigId, result.images, "ai-image-edit")
            }
        }
        }

    private fun requireProjectWriteAccess(projectId: Long, userId: Long): Mono<Unit> {
        if (!taskCenterService.canWriteProject(projectId, userId)) {
            return Mono.error(ForbiddenException("无权编辑该项目"))
        }
        return Mono.just(Unit)
    }

    fun pollVideo(providerConfigId: Long, videoId: String) =
        aiInvocationService.getVideo(providerConfigId, videoId)
            .switchIfEmpty(Mono.error(AiProtocolException("视频服务未返回状态")))

    fun downloadVideo(ownerId: Long, providerConfigId: Long, videoId: String): Mono<FileDetails> =
        pollVideo(providerConfigId, videoId).flatMap { status ->
            if (status.status != AiVideoStatus.COMPLETED) {
                Mono.error(ParamErrorException("视频尚未生成完成"))
            } else {
                aiInvocationService.downloadVideo(providerConfigId, videoId)
                    .switchIfEmpty(Mono.error(AiProtocolException("视频服务未返回内容")))
                    .flatMap { content ->
                        Mono.fromCallable {
                            fileService.storeGenerated(ownerId, content.filename, content.contentType, content.bytes)
                        }.subscribeOn(Schedulers.boundedElastic())
                    }
            }
        }

    private fun storeImages(
        ownerId: Long,
        providerConfigId: Long,
        images: List<AiGeneratedImage>,
        filenamePrefix: String,
    ): Mono<List<FileDetails>> {
        val stored = mutableListOf<FileDetails>()
        val batchTimestamp = System.currentTimeMillis()
        return Flux.fromIterable(images)
            .index()
            .concatMap { indexed ->
                val index = indexed.t1
                val image = indexed.t2
                log.info("开始处理模型图片结果：providerConfigId={} index={} hasBase64={} hasUrl={}", providerConfigId, index, !image.base64Data.isNullOrBlank(), !image.url.isNullOrBlank())
                downloadImage(providerConfigId, image)
                    .flatMap { downloaded ->
                        log.info("模型图片解析完成：providerConfigId={} index={} contentType={} bytes={}", providerConfigId, index, downloaded.contentType, downloaded.bytes.size)
                        val extension = extensionFor(downloaded.contentType)
                        Mono.fromCallable {
                            fileService.storeGenerated(
                                ownerId,
                                "$filenamePrefix-$batchTimestamp-$index.$extension",
                                downloaded.contentType,
                                downloaded.bytes,
                            )
                        }.subscribeOn(Schedulers.boundedElastic()).doOnSuccess { details ->
                            details?.let { value -> log.info("模型图片已保存到文件库：providerConfigId={} index={} fileId={} filename={} bytes={}", providerConfigId, index, value.file.id, value.file.originalFilename, value.file.sizeBytes) }
                        }
                    }
                    .doOnNext(stored::add)
            }
            .collectList()
            .doOnSuccess { files -> files?.let { value -> log.info("模型图片批量处理完成：providerConfigId={} fileCount={} fileIds={}", providerConfigId, value.size, value.map { it.file.id }) } }
            .onErrorResume { exception ->
                log.error("模型图片处理流程异常，开始清理已保存文件：providerConfigId={} storedFileIds={} exceptionType={} message={}", providerConfigId, stored.map { it.file.id }, exception.javaClass.name, exception.message, exception)
                cleanupStoredFiles(ownerId, stored).then(Mono.error(exception))
            }
    }

    private fun downloadImage(providerConfigId: Long, image: AiGeneratedImage): Mono<AiResultDownloader.DownloadedAiImage> {
        if (!image.base64Data.isNullOrBlank()) {
            return Mono.fromCallable {
                val raw = image.base64Data.trim()
                val value = raw.substringAfter(",", raw)
                val bytes = try {
                    Base64.getMimeDecoder().decode(value)
                } catch (exception: IllegalArgumentException) {
                    throw AiProtocolException("模型返回的图片数据不是合法 base64")
                }
                if (bytes.isEmpty()) throw AiProtocolException("模型返回的图片数据为空")
                if (bytes.size > MAX_IMAGE_BYTES) throw AiProtocolException("模型图片超过大小限制")
                AiResultDownloader.DownloadedAiImage(AiImageContentDetector.detect(bytes), bytes)
            }.subscribeOn(Schedulers.boundedElastic())
        }
        return image.url?.let { aiInvocationService.downloadImage(providerConfigId, it) }
            ?: Mono.error(AiProtocolException("图片服务未返回可保存的图片数据"))
    }

    private fun extensionFor(contentType: String): String = when (contentType.substringBefore(';').lowercase()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> throw AiProtocolException("模型返回了不支持的图片格式")
    }

    private fun cleanupStoredFiles(ownerId: Long, stored: List<FileDetails>): Mono<Void> {
        if (stored.isEmpty()) return Mono.empty()
        return Mono.fromRunnable<Unit> {
            stored.asReversed().forEach { details ->
                runCatching { fileService.delete(ownerId, details.file.id) }
            }
        }.subscribeOn(Schedulers.boundedElastic()).then()
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val DEFAULT_IMAGE_SIZE = "2K"
        val CHARACTER_TARGETS = setOf("FRONT", "SIDE", "BACK", "MERGED", "EXPRESSION")
        val SCENE_TARGETS = setOf("MAIN", "EAST", "SOUTH", "WEST", "NORTH", "LONG_SHOT", "FULL_SHOT", "MEDIUM_SHOT", "CLOSE_SHOT", "MORNING", "AFTERNOON", "NIGHT")
    }

    private data class AssetImageRequest(
        val target: String,
        val prompt: String,
        val referenceFileIds: List<String>,
    )

    private fun buildAssetImageRequest(projectId: Long, episodeId: Long, assetId: Long, rawTarget: String): AssetImageRequest {
        val target = normalizeAssetImageTarget(rawTarget)
        val character = assetService.loadCharacterProfile(projectId, episodeId, assetId)
        if (character != null) {
            if (target !in CHARACTER_TARGETS) throw ParamErrorException("角色图片目标不正确")
            val profile = character.profile
            val prompt = listOf(
                "角色资产：${character.asset.name}",
                "目标视图：${targetLabel(target)}",
                character.asset.description,
                profile.identityTitle,
                profile.roleTag,
                profile.gender,
                profile.ageStage,
                profile.ageNote,
                profile.faceDescription,
                profile.costumeDescription,
                profile.hairstyle,
                profile.costumeRef,
                profile.accessoryRef,
            ).filterNotNull().filter(String::isNotBlank).joinToString("；")
            return AssetImageRequest(
                target,
                "$prompt。请保持角色身份、脸部特征、服装和色彩连续，输出${targetLabel(target)}参考图。",
                listOfNotNull(profile.threeViewMergedFileId, profile.threeViewFrontFileId, profile.threeViewSideFileId, profile.threeViewBackFileId),
            )
        }

        val scene = assetService.loadSceneProfile(projectId, episodeId, assetId)
        if (scene != null) {
            if (target !in SCENE_TARGETS) throw ParamErrorException("场景图片目标不正确")
            val profile = scene.profile
            val prompt = listOf(
                "场景资产：${scene.asset.name}",
                "目标视图：${targetLabel(target)}",
                scene.asset.description,
                profile.identityTitle,
                profile.location,
                profile.timeNote,
                profile.atmosphere,
            ).filterNotNull().filter(String::isNotBlank).joinToString("；")
            return AssetImageRequest(
                target,
                "$prompt。保持场景空间关系、材质、光线和色彩连续，输出${targetLabel(target)}参考图。",
                listOfNotNull(profile.mainImageFileId, profile.viewEastFileId, profile.viewSouthFileId, profile.viewWestFileId, profile.viewNorthFileId),
            )
        }

        val prop = assetService.loadPropProfile(projectId, episodeId, assetId)
        if (prop != null) {
            if (target != "CONCEPT") throw ParamErrorException("道具图片目标不正确")
            val profile = prop.profile
            val prompt = listOf(
                "道具资产：${prop.asset.name}",
                "目标视图：道具概念图",
                prop.asset.description,
                profile.identityTitle,
                profile.category,
                profile.plotFunction,
            ).filterNotNull().filter(String::isNotBlank).joinToString("；")
            return AssetImageRequest(
                target,
                "$prompt。保持道具形制、材质、磨损和剧情连续性，输出道具概念图。",
                listOfNotNull(profile.conceptImageFileId),
            )
        }
        throw ResourceNotFoundException("资产不存在或类型不支持")
    }

    private fun normalizeAssetImageTarget(rawTarget: String): String = when (rawTarget.trim().lowercase()) {
        "front" -> "FRONT"
        "side" -> "SIDE"
        "back" -> "BACK"
        "merged" -> "MERGED"
        "expression" -> "EXPRESSION"
        "main" -> "MAIN"
        "east" -> "EAST"
        "south" -> "SOUTH"
        "west" -> "WEST"
        "north" -> "NORTH"
        "longshot", "long_shot" -> "LONG_SHOT"
        "fullshot", "full_shot" -> "FULL_SHOT"
        "mediumshot", "medium_shot" -> "MEDIUM_SHOT"
        "closeshot", "close_shot" -> "CLOSE_SHOT"
        "morning" -> "MORNING"
        "afternoon" -> "AFTERNOON"
        "night" -> "NIGHT"
        "concept" -> "CONCEPT"
        else -> throw ParamErrorException("图片生成目标不正确")
    }

    private fun targetLabel(target: String): String = when (target) {
        "FRONT" -> "正面视图"
        "SIDE" -> "侧面视图"
        "BACK" -> "背面视图"
        "MERGED" -> "三视图合成"
        "EXPRESSION" -> "表情参考图"
        "MAIN" -> "主视觉"
        "EAST" -> "东面视图"
        "SOUTH" -> "南面视图"
        "WEST" -> "西面视图"
        "NORTH" -> "北面视图"
        "LONG_SHOT" -> "远景"
        "FULL_SHOT" -> "全景"
        "MEDIUM_SHOT" -> "中景"
        "CLOSE_SHOT" -> "近景"
        "MORNING" -> "晨光"
        "AFTERNOON" -> "午后光照"
        "NIGHT" -> "夜晚光照"
        "CONCEPT" -> "道具概念图"
        else -> target
    }

}
