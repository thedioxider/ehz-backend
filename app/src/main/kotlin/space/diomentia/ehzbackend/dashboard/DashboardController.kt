package space.diomentia.ehzbackend.dashboard

import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import space.diomentia.ehzbackend.ApiContract
import space.diomentia.ehzbackend.bootstrap.CredentialsStore
import space.diomentia.ehzbackend.reports.ReportRepository
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

/**
 * Operator-facing dashboard, served only on the management context (port 8080,
 * 127.0.0.1-only). The management server is localhost-bound in
 * `application.yml`, which is the entire access-control story here — anyone who
 * can load this page can already `cat {ehz.data-dir}/credentials.txt` on the
 * same host, so the API key is shown verbatim. Masking would be theater.
 *
 * Registration onto the management context is done by
 * [DashboardManagementContextConfiguration]; the main application's
 * `@ComponentScan` explicitly excludes the `dashboard` subpackage
 * (see [space.diomentia.ehzbackend.EhzBackendApplication]) so the `@Controller`
 * annotation here doesn't also land `GET /` on the internal API port.
 *
 * Health is probed directly rather than via injected `HealthIndicator` beans —
 * three tiny checks, no boilerplate, no lifecycle dependency on the actuator
 * autoconfigure order.
 */
@Controller
class DashboardController(
    private val dataSource: DataSource,
    private val reportRepository: ReportRepository,
    private val credentialsStore: CredentialsStore,
    private val apiPort: Int,
    private val dataDir: String,
    private val buildProperties: BuildProperties?,
) {

    @GetMapping("/")
    fun index(model: Model, response: HttpServletResponse): String {
        // The rendered page embeds the plaintext API key verbatim. Disabling
        // all caches here keeps it out of the browser's disk cache and any
        // intermediate proxy caches — localhost-only binding protects against
        // network leaks, but a cached copy on disk would still survive.
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        response.setHeader("Pragma", "no-cache")

        val now = Instant.now()
        val photosRoot = Path.of(dataDir).toAbsolutePath().resolve("photos")
        val dataDirPath = Path.of(dataDir).toAbsolutePath()

        val freeDiskBytes = probeFreeBytes(dataDirPath)
        val mostRecent = reportRepository.findTopByOrderByReceivedAtDesc()?.receivedAt

        model.addAttribute("serverIp", firstNonLoopbackIpv4() ?: "unknown")
        model.addAttribute("apiPort", apiPort)
        model.addAttribute("build", buildProperties?.version ?: "dev")
        model.addAttribute("contract", ApiContract.VERSION)
        model.addAttribute(
            "health",
            DashboardHealth(
                dbUp = probeDb(),
                photosDirWritable = probeWritable(photosRoot),
                freeDiskBytes = freeDiskBytes,
                freeDiskHuman = formatBytes(freeDiskBytes),
            ),
        )
        model.addAttribute(
            "reportCounts",
            DashboardReportCounts(
                total = reportRepository.count(),
                last24h = reportRepository.countByReceivedAtAfter(now.minusSeconds(24 * 3600)),
                last7d = reportRepository.countByReceivedAtAfter(now.minusSeconds(7 * 24 * 3600)),
                mostRecent = mostRecent,
                mostRecentHuman = formatInstant(mostRecent),
            ),
        )
        model.addAttribute("apiKey", credentialsStore.apiKey)
        return "dashboard"
    }

    private fun probeDb(): Boolean = try {
        dataSource.connection.use { it.isValid(1) }
    } catch (_: Exception) {
        false
    }

    private fun probeWritable(dir: Path): Boolean {
        return try {
            // `photos/` may not exist yet on a fresh install — a writable parent
            // data-dir is equivalent here, since PhotoStorage.createDirectories
            // would succeed on first ingest.
            val target = if (Files.exists(dir)) dir else dir.parent ?: return false
            Files.isWritable(target)
        } catch (_: Exception) {
            false
        }
    }

    private fun probeFreeBytes(dir: Path): Long = try {
        val target = if (Files.exists(dir)) dir else dir.parent ?: return -1L
        Files.getFileStore(target).usableSpace
    } catch (_: Exception) {
        -1L
    }

    /**
     * Base-1024 byte formatting for operator display. `-1` (our "could not
     * stat" sentinel from [probeFreeBytes]) renders as a dash. Values under 1
     * KiB render as raw bytes — anything larger picks the largest unit that
     * keeps the number under 1024 and shows one decimal.
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return "%.1f %s".format(value, units[unitIndex])
    }

    /**
     * Render an [Instant] as `yyyy-MM-dd HH:mm UTC`. `null` (empty reports
     * table) renders as a dash.
     */
    private fun formatInstant(instant: Instant?): String =
        if (instant == null) "—" else INSTANT_FORMATTER.format(instant)

    /**
     * First non-loopback, non-link-local IPv4 on any active interface. Returns
     * null if the host has only loopback interfaces (common in CI containers).
     */
    private fun firstNonLoopbackIpv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces() ?: return null
        } catch (_: Exception) {
            return null
        }
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is InetAddress
                    && !addr.isLoopbackAddress
                    && !addr.isLinkLocalAddress
                    && addr.address.size == 4
                ) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    private companion object {
        private val INSTANT_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}

/** Boolean/bytes snapshot shown under the "Health" section of the dashboard. */
data class DashboardHealth(
    val dbUp: Boolean,
    val photosDirWritable: Boolean,
    /** -1 when the data dir could not be stat'd. */
    val freeDiskBytes: Long,
    /** Operator-facing rendering of [freeDiskBytes] (base-1024 units, or "—"). */
    val freeDiskHuman: String,
)

/** Counts + freshness shown under the "Reports" section of the dashboard. */
data class DashboardReportCounts(
    val total: Long,
    val last24h: Long,
    val last7d: Long,
    /** null on an empty reports table. */
    val mostRecent: Instant?,
    /** Operator-facing rendering of [mostRecent] (`yyyy-MM-dd HH:mm UTC`, or "—"). */
    val mostRecentHuman: String,
)
