package space.diomentia.ehzbackend.reports.photos

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
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

        // specId is free-form client input; ensure the resolved path stays
        // under the photos root so `../` segments can't escape.
        val resolved = target.normalize().toAbsolutePath()
        val root = photosRoot.normalize().toAbsolutePath()
        require(resolved.startsWith(root)) { "path escapes photos root: $resolved" }

        Files.createDirectories(resolved.parent)

        // Write to a sibling .tmp then atomically rename so a crash mid-write
        // can't leave a torn half-file at the final path.
        val tmp = resolved.resolveSibling("${resolved.fileName}.tmp")
        Files.write(
            tmp,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        try {
            Files.move(tmp, resolved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, resolved, StandardCopyOption.REPLACE_EXISTING)
        }
        return relative
    }

    /**
     * Resolve a previously-persisted relative path back to an absolute [Path].
     * Used by the future GET /api/photos endpoint (Step 4).
     */
    fun resolve(relativePath: String): Path {
        val resolved = dataDirPath.resolve(relativePath).normalize().toAbsolutePath()
        val root = photosRoot.normalize().toAbsolutePath()
        require(resolved.startsWith(root)) { "path escapes photos root: $resolved" }
        return resolved
    }

    /** Exposed for diagnostics; not intended as API surface. */
    fun photosRoot(): Path = photosRoot
}
