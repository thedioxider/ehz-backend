package space.diomentia.ehzbackend.catalog

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

/**
 * Upper bound on how large an on-disk `report-config.yaml` override may be
 * before the controller refuses to load it and falls back to the bundled
 * seed. 1 MiB is already far more than any realistic hand-authored
 * `report-config.yaml`, and it caps worst-case heap use if the config dir is
 * misconfigured or a giant file is dropped in by mistake.
 */
private const val MAX_OVERRIDE_BYTES: Long = 1L * 1024 * 1024

/**
 * Serves `report-config.yaml` (field specs + templates) with a
 * bundled-default → on-disk-override precedence:
 *
 *   1. If `{ehz.config-dir}/report-config.yaml` exists as a regular,
 *      non-symlink file no larger than [MAX_OVERRIDE_BYTES], its bytes are
 *      served verbatim — the operator can drop an updated config into the
 *      host-mounted `config/` directory without rebuilding.
 *   2. Otherwise, the bundled `classpath:seed/report_conf.yaml` is served.
 *
 * The symlink and size guards keep a misconfigured `EHZ_CONFIG_DIR` or a
 * mistakenly-placed giant file from OOM-ing the process or exfiltrating an
 * unrelated host file via a symlink into the config dir. A rejected override
 * is logged at WARN so the operator can spot the fallback.
 *
 * Serving raw bytes (as opposed to parse → re-emit) preserves the exact YAML
 * the operator authored, including comments and key order.
 */
@RestController
@RequestMapping("/api/config")
class ReportConfigController(
    @Value("\${ehz.config-dir}") private val configDir: String,
) {

    private val log = LoggerFactory.getLogger(ReportConfigController::class.java)

    @GetMapping("/report-config", produces = [AreasController.MEDIA_TYPE_YAML_VALUE])
    fun reportConfig(): ResponseEntity<ByteArray> {
        val override = Path.of(configDir, "report-config.yaml")
        val bytes = if (Files.isRegularFile(override) && isOverrideUsable(override)) {
            Files.readAllBytes(override)
        } else {
            ClassPathResource("seed/report_conf.yaml").inputStream.use { it.readAllBytes() }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(AreasController.MEDIA_TYPE_YAML_VALUE))
            .body(bytes)
    }

    /**
     * Returns `true` only if [override] is safe to load as-is. A symlink is
     * rejected outright (so the override dir can't point into `/etc/...` or
     * similar), and anything larger than [MAX_OVERRIDE_BYTES] is rejected so
     * a single oversized file can't blow up the heap via `readAllBytes`.
     */
    private fun isOverrideUsable(override: Path): Boolean {
        if (Files.isSymbolicLink(override)) {
            log.warn(
                "Ignoring symlinked report-config override at {} — serving bundled seed",
                override,
            )
            return false
        }
        val size = Files.size(override)
        if (size > MAX_OVERRIDE_BYTES) {
            log.warn(
                "Ignoring report-config override at {} ({} bytes > {} byte cap) — serving bundled seed",
                override,
                size,
                MAX_OVERRIDE_BYTES,
            )
            return false
        }
        return true
    }
}
