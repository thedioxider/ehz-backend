package space.diomentia.ehzbackend.catalog

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
 * Serves `report-config.yaml` (field specs + templates) with a
 * bundled-default → on-disk-override precedence:
 *
 *   1. If `{ehz.config-dir}/report-config.yaml` exists as a regular file,
 *      its bytes are served verbatim — the operator can drop an updated
 *      config into the host-mounted `config/` directory without rebuilding.
 *   2. Otherwise, the bundled `classpath:seed/report_conf.yaml` is served.
 *
 * Serving raw bytes (as opposed to parse → re-emit) preserves the exact YAML
 * the operator authored, including comments and key order.
 */
@RestController
@RequestMapping("/api/config")
class ReportConfigController(
    @Value("\${ehz.config-dir}") private val configDir: String,
) {

    @GetMapping("/report-config", produces = [AreasController.MEDIA_TYPE_YAML_VALUE])
    fun reportConfig(): ResponseEntity<ByteArray> {
        val override = Path.of(configDir, "report-config.yaml")
        val bytes = if (Files.isRegularFile(override)) {
            Files.readAllBytes(override)
        } else {
            ClassPathResource("seed/report_conf.yaml").inputStream.use { it.readAllBytes() }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(AreasController.MEDIA_TYPE_YAML_VALUE))
            .body(bytes)
    }
}
