package space.diomentia.ehzbackend.dashboard

import org.springframework.beans.factory.annotation.Value
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
    @param:Value("\${ehz.public-api-port:19081}") private val apiPort: Int,
    @param:Value("\${ehz.data-dir}") private val dataDir: String,
    private val buildProperties: BuildProperties?,
) {

    @GetMapping("/")
    fun index(model: Model): String {
        val now = Instant.now()
        val photosRoot = Path.of(dataDir).toAbsolutePath().resolve("photos")
        val dataDirPath = Path.of(dataDir).toAbsolutePath()

        model.addAttribute("serverIp", firstNonLoopbackIpv4() ?: "unknown")
        model.addAttribute("apiPort", apiPort)
        model.addAttribute("build", buildProperties?.version ?: "dev")
        model.addAttribute("contract", ApiContract.VERSION)
        model.addAttribute(
            "health",
            DashboardHealth(
                dbUp = probeDb(),
                photosDirWritable = probeWritable(photosRoot),
                freeDiskBytes = probeFreeBytes(dataDirPath),
            ),
        )
        model.addAttribute(
            "reportCounts",
            DashboardReportCounts(
                total = reportRepository.count(),
                last24h = reportRepository.countByReceivedAtAfter(now.minusSeconds(24 * 3600)),
                last7d = reportRepository.countByReceivedAtAfter(now.minusSeconds(7 * 24 * 3600)),
                mostRecent = reportRepository.findTopByOrderByReceivedAtDesc()?.receivedAt,
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
}

/** Boolean/bytes snapshot shown under the "Health" section of the dashboard. */
data class DashboardHealth(
    val dbUp: Boolean,
    val photosDirWritable: Boolean,
    /** -1 when the data dir could not be stat'd. */
    val freeDiskBytes: Long,
)

/** Counts + freshness shown under the "Reports" section of the dashboard. */
data class DashboardReportCounts(
    val total: Long,
    val last24h: Long,
    val last7d: Long,
    /** null on an empty reports table. */
    val mostRecent: Instant?,
)
