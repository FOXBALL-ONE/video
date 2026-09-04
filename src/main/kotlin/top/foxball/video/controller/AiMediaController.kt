package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import top.foxball.video.service.AiMediaGenerationService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
/** 图片和视频 AI 生成接口；所有上游请求均由服务端发起。 */
class AiMediaController(
    private val service: AiMediaGenerationService,
    private val responseBuilder: ResponseBuilder,
) {
    @PostMapping("/api/ai/images/generations")
    fun generateImage(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("provider_config_id") providerConfigId: Long,
        @RequestParam("model") @NotBlank @Size(max = 200) model: String,
        @RequestParam("prompt") @NotBlank @Size(max = 200000) prompt: String,
        @RequestParam("count", required = false) @Min(1) @Max(10) count: Int?,
        @RequestParam("size", required = false) @Size(max = 30) size: String?,
        @RequestParam("quality", required = false) @Size(max = 30) quality: String?,
    ): ResponseEntity<Response> {
        data class FileData(
            val id: java.util.UUID,
            @param:JsonProperty("file_name") val fileName: String,
            @param:JsonProperty("content_type") val contentType: String?,
            @param:JsonProperty("size_bytes") val sizeBytes: Long,
            @param:JsonProperty("signed_download_url") val signedDownloadUrl: String,
            @param:JsonProperty("download_expires_at") val downloadExpiresAt: LocalDateTime,
        )
        data class ResponseData(val list: List<FileData>)
        val files = service.generateImage(userId, providerConfigId, model, prompt, count, size, quality)
        val rs = ResponseData(files.map { details ->
            FileData(details.file.id, details.file.originalFilename, details.file.contentType, details.file.sizeBytes, details.signedDownloadUrl, details.downloadExpiresAt)
        })
        return responseBuilder.created().data(rs).build()
    }

    @PostMapping("/api/ai/images/edits", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun editImage(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("provider_config_id") providerConfigId: Long,
        @RequestParam("model") @NotBlank @Size(max = 200) model: String,
        @RequestParam("prompt") @NotBlank @Size(max = 200000) prompt: String,
        @RequestPart("images") images: List<MultipartFile>,
        @RequestPart("mask", required = false) mask: MultipartFile?,
        @RequestParam("count", required = false) @Min(1) @Max(10) count: Int?,
        @RequestParam("size", required = false) @Size(max = 30) size: String?,
    ): ResponseEntity<Response> {
        data class FileData(
            val id: java.util.UUID,
            @param:JsonProperty("file_name") val fileName: String,
            @param:JsonProperty("content_type") val contentType: String?,
            @param:JsonProperty("size_bytes") val sizeBytes: Long,
            @param:JsonProperty("signed_download_url") val signedDownloadUrl: String,
            @param:JsonProperty("download_expires_at") val downloadExpiresAt: LocalDateTime,
        )
        data class ResponseData(val list: List<FileData>)
        val files = service.editImage(userId, providerConfigId, model, prompt, images, mask, count, size)
        val rs = ResponseData(files.map { details ->
            FileData(details.file.id, details.file.originalFilename, details.file.contentType, details.file.sizeBytes, details.signedDownloadUrl, details.downloadExpiresAt)
        })
        return responseBuilder.created().data(rs).build()
    }

    @PostMapping("/api/ai/videos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createVideo(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("provider_config_id") providerConfigId: Long,
        @RequestParam("model") @NotBlank @Size(max = 200) model: String,
        @RequestParam("prompt") @NotBlank @Size(max = 200000) prompt: String,
        @RequestParam("size", defaultValue = "1280x720") @Size(max = 30) size: String,
        @RequestParam("seconds", defaultValue = "8") @Min(1) @Max(20) seconds: Int,
        @RequestPart("input_reference", required = false) inputReference: MultipartFile?,
    ): ResponseEntity<Response> {
        data class ResponseData(
            @param:JsonProperty("video_id") val videoId: String,
            val status: String,
        )
        val videoId = service.generateVideo(userId, providerConfigId, model, prompt, size, seconds, inputReference)
        return responseBuilder.accepted().data(ResponseData(videoId, "QUEUED")).build()
    }

    @GetMapping("/api/ai/videos/{video_id}")
    fun getVideo(
        @PathVariable("video_id") videoId: String,
        @RequestParam("provider_config_id") providerConfigId: Long,
    ): ResponseEntity<Response> {
        data class ResponseData(
            @param:JsonProperty("video_id") val videoId: String,
            val model: String?,
            val status: String,
            val progress: Int?,
            @param:JsonProperty("created_at_epoch_seconds") val createdAtEpochSeconds: Long?,
            @param:JsonProperty("completed_at_epoch_seconds") val completedAtEpochSeconds: Long?,
            @param:JsonProperty("error_code") val errorCode: String?,
            @param:JsonProperty("error_message") val errorMessage: String?,
        )
        val result = service.pollVideo(providerConfigId, videoId)
        val rs = ResponseData(result.id, result.model, result.status.name, result.progress, result.createdAtEpochSeconds, result.completedAtEpochSeconds, result.errorCode, result.errorMessage)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/ai/videos/{video_id}/content")
    fun saveVideoContent(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("video_id") videoId: String,
        @RequestParam("provider_config_id") providerConfigId: Long,
    ): ResponseEntity<Response> {
        data class FileData(
            val id: java.util.UUID,
            @param:JsonProperty("file_name") val fileName: String,
            @param:JsonProperty("content_type") val contentType: String?,
            @param:JsonProperty("size_bytes") val sizeBytes: Long,
            @param:JsonProperty("signed_download_url") val signedDownloadUrl: String,
            @param:JsonProperty("download_expires_at") val downloadExpiresAt: LocalDateTime,
        )
        val file = service.downloadVideo(userId, providerConfigId, videoId)
        val rs = FileData(file.file.id, file.file.originalFilename, file.file.contentType, file.file.sizeBytes, file.signedDownloadUrl, file.downloadExpiresAt)
        return responseBuilder.created().data(rs).build()
    }
}
