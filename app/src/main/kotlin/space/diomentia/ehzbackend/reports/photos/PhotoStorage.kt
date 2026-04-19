package space.diomentia.ehzbackend.reports.photos

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID

/**
 * Filesystem-backed photo storage for ingested report fields.
 *
 * Layout under `ehz.data-dir`:
 *     {dataDir}/photos/{reportId}/{siteId}/{specId}.webp
 *
 * Only the relative path `photos/{reportId}/{siteId}/{specId}.webp` is
 * persisted into `report_fields.value`; the data-dir prefix stays in config
 * so the same DB rows work across different hosts/volumes.
 *
 * Concern (v1): if a DB transaction rolls back after a photo write, the
 * orphaned file/directory is not cleaned up. Acceptable for v1 — re-ingestion
 * with the same report id overwrites; a housekeeping pass can prune later.
 */
@Component
class PhotoStorage(
    @Value("\${ehz.data-dir}") dataDir: String,
) {
    private val dataDirPath: Path = Paths.get(dataDir).toAbsolutePath().normalize()
    private val photosRoot: Path = dataDirPath.resolve("photos")

    /**
     * Decode [base64] as standard (non-URL-safe) base64 and write to disk.
     * Returns the relative path to persist in `report_fields.value`.
     */
    fun save(reportId: UUID, siteId: UUID, specId: String, base64: String): String {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid base64 for photo field '$specId' on site $siteId: ${e.message}",
                e,
            )
        }

        val relative = "photos/$reportId/$siteId/$specId.webp"
        val target = dataDirPath.resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(
            target,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return relative
    }

    /**
     * Resolve a previously-persisted relative path back to an absolute [Path].
     * Used by the future GET /api/photos endpoint (Step 4).
     */
    fun resolve(relativePath: String): Path = dataDirPath.resolve(relativePath).normalize()

    /** Exposed for diagnostics; not intended as API surface. */
    fun photosRoot(): Path = photosRoot
}
