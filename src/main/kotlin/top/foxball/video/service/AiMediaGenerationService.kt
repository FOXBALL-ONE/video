package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.foxball.video.ai.AiImageGenerationCommand
import top.foxball.video.ai.AiImageInput
import top.foxball.video.ai.AiImageResponseFormat
import top.foxball.video.ai.AiImageEditCommand
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.ai.AiVideoGenerationCommand

/** 面向 HTTP 管理接口的图片/视频调用门面；生成结果统一落入现有文件服务存储。 */
@Service
class AiMediaGenerationService(
    private val aiInvocationService: AiInvocationService,
    private val fileService: FileService,
) {
    fun generateImage(
        ownerId: Long,
        providerConfigId: Long,
        model: String,
        prompt: String,
        count: Int?,
        size: String?,
        quality: String?,
    ): List<FileDetails> {
        val result = aiInvocationService.generateImage(
            AiImageGenerationCommand(
                providerConfigId = providerConfigId,
                model = model.trim(),
                prompt = prompt,
                count = count,
                size = size,
                quality = quality,
                responseFormat = AiImageResponseFormat.B64_JSON,
            ),
        ).block() ?: error("图片服务未返回结果")
        if (result.images.isEmpty()) error("图片服务未返回图片")
        return result.images.mapIndexed { index, image ->
            val downloaded = if (!image.base64Data.isNullOrBlank()) {
                top.foxball.video.ai.AiResultDownloader.DownloadedAiImage("image/png", java.util.Base64.getDecoder().decode(image.base64Data))
            } else {
                image.url?.let { aiInvocationService.downloadImage(providerConfigId, it).block() }
                    ?: error("图片服务未返回可保存的图片数据")
            }
            val extension = when (downloaded.contentType.substringAfter('/', "png").substringBefore(';')) {
                "jpeg" -> "jpg"
                "webp" -> "webp"
                else -> "png"
            }
            fileService.storeGenerated(ownerId, "ai-image-${System.currentTimeMillis()}-$index.$extension", downloaded.contentType, downloaded.bytes)
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
    ): String {
        val reference = inputReference?.let {
            AiImageInput(it.originalFilename ?: "reference-image", it.contentType ?: "image/png", it.bytes)
        }
        val created = aiInvocationService.createVideo(
            AiVideoGenerationCommand(providerConfigId, model.trim(), prompt, size, seconds, reference),
        ).block() ?: error("视频服务未返回任务")
        return created.id
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
    ): List<FileDetails> {
        val inputs = images.map { file ->
            AiImageInput(file.originalFilename ?: "image", file.contentType ?: "image/png", file.bytes)
        }
        val maskInput = mask?.let { file -> AiImageInput(file.originalFilename ?: "mask", file.contentType ?: "image/png", file.bytes) }
        val result = aiInvocationService.editImage(
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
        ).block() ?: error("图片编辑服务未返回结果")
        if (result.images.isEmpty()) error("图片编辑服务未返回图片")
        return result.images.mapIndexed { index, image ->
            val downloaded = if (!image.base64Data.isNullOrBlank()) {
                top.foxball.video.ai.AiResultDownloader.DownloadedAiImage("image/png", java.util.Base64.getDecoder().decode(image.base64Data))
            } else {
                image.url?.let { aiInvocationService.downloadImage(providerConfigId, it).block() }
                    ?: error("图片编辑服务未返回可保存的图片数据")
            }
            fileService.storeGenerated(ownerId, "ai-image-edit-${System.currentTimeMillis()}-$index.png", downloaded.contentType, downloaded.bytes)
        }
    }

    fun pollVideo(providerConfigId: Long, videoId: String) =
        aiInvocationService.getVideo(providerConfigId, videoId).block() ?: error("视频服务未返回状态")

    fun downloadVideo(ownerId: Long, providerConfigId: Long, videoId: String): FileDetails {
        val status = pollVideo(providerConfigId, videoId)
        if (status.status != top.foxball.video.ai.AiVideoStatus.COMPLETED) {
            error("视频尚未生成完成")
        }
        val content = aiInvocationService.downloadVideo(providerConfigId, videoId).block() ?: error("视频服务未返回内容")
        return fileService.storeGenerated(ownerId, content.filename, content.contentType, content.bytes)
    }
}
