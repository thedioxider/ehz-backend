package space.diomentia.ehzbackend

import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Low-cost, always-available probes for diagnostics and compatibility checks.
 *
 * `/api/ping` is a liveness heartbeat for the Android app's first-boot QR-pair
 * flow — constant 200 JSON, no auth, no DB access. Cheap enough for the
 * discovery service to exercise on a schedule.
 *
 * `/api/info` surfaces the two-part version (build semver for humans, contract
 * integer for machines) plus the commit SHA so a client talking to an unknown
 * deployment can decide whether to upgrade the wire contract handling.
 *
 * [BuildProperties] is populated from `META-INF/build-info.properties`, which
 * the Spring Boot Gradle plugin writes via the `springBoot { buildInfo() }`
 * directive in `app/build.gradle.kts`. The parameter is nullable because that
 * resource may be absent in some slice-test contexts; the endpoint falls back
 * to `"dev"` for build and `""` for commit.
 */
@RestController
class InfoController(
    private val buildProperties: BuildProperties?,
) {

    @GetMapping("/api/ping")
    fun ping(): Map<String, Boolean> = mapOf("pong" to true)

    @GetMapping("/api/info")
    fun info(): Map<String, Any> = mapOf(
        "build" to (buildProperties?.version ?: "dev"),
        "contract" to ApiContract.VERSION,
        "commit" to (buildProperties?.get("commit") ?: ""),
    )
}
