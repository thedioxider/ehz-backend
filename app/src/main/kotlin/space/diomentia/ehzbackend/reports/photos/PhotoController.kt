package space.diomentia.ehzbackend.reports.photos

import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.util.UUID

/**
 * Streams report photos from disk.
 *
 * URL layout mirrors the on-disk layout chosen by [PhotoStorage]:
 *     `/api/photos/{reportId}/{siteId}/{specId}.webp`
 * (where `{specId}` is free-form client input — the `:.+` regex allows
 * characters like `_` that Spring would otherwise truncate before the dot).
 *
 * `PhotoStorage.resolve(...)` applies the path-traversal guard: any resolved
 * path outside `{ehz.data-dir}/photos/` is rejected. We catch its
 * [IllegalArgumentException] and return 404 rather than 400, so a prober
 * can't distinguish "traversal attempt" from "file not found".
 *
 * `FileSystemResource` lets Spring set `Content-Length` for us (no streaming
 * surprises on a LAN with flaky clients).
 */
@RestController
class PhotoController(
    private val photoStorage: PhotoStorage,
) {

    @GetMapping("/api/photos/{reportId}/{siteId}/{specId:.+}.webp")
    fun get(
        @PathVariable reportId: UUID,
        @PathVariable siteId: UUID,
        @PathVariable specId: String,
    ): ResponseEntity<FileSystemResource> {
        val relative = "photos/$reportId/$siteId/$specId.webp"
        val path = try {
            photoStorage.resolve(relative)
        } catch (_: IllegalArgumentException) {
            return ResponseEntity.notFound().build()
        }
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build()

        val resource = FileSystemResource(path)
        return ResponseEntity.ok()
            .contentType(IMAGE_WEBP)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$specId.webp\"")
            .body(resource)
    }

    companion object {
        // Spring's MediaType constants don't include image/webp out of the box.
        private val IMAGE_WEBP: MediaType = MediaType.parseMediaType("image/webp")
    }
}
