package space.diomentia.ehzbackend.bootstrap

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the LAN-facing API key used by the Android companion app.
 *
 * Resolution order at startup:
 *   1. If the environment variable `EHZ_API_KEY` is set, use it verbatim — this
 *      is the ops/test escape hatch and skips any file I/O. The file is not
 *      written, so a test run never pollutes an operator's data dir.
 *   2. Otherwise read `{ehz.data-dir}/credentials.txt`. The file holds
 *      `EHZ_API_KEY=<value>` lines (comments via `#` and blank lines
 *      ignored). Any other keys are tolerated for forward-compatibility.
 *   3. If the file is absent, generate a fresh 32-byte URL-safe base64 token
 *      (no padding), create the file with POSIX `rw-------` (600), and record
 *      `EHZ_API_KEY=<value>`. POSIX perms are best-effort — on a non-POSIX
 *      filesystem we fall back to the `java.io.File` setters so the file is
 *      still owner-read/write only.
 *
 * Runs in `@PostConstruct` (not `@EventListener(ApplicationReadyEvent)`) so
 * [LanIngestFilter] — which is built during context startup — can inject the
 * ready-to-use store without a race. The bean contract is "after construction,
 * `apiKey` is non-null and stable for the process lifetime".
 */
@Component
class CredentialsStore(
    @param:Value("\${ehz.data-dir}") private val dataDir: String,
) {
    private val log = LoggerFactory.getLogger(CredentialsStore::class.java)

    final lateinit var apiKey: String
        private set

    /**
     * Seam for tests. Production reads the process env directly; tests
     * override this to exercise the env-override branch without mutating the
     * real process env (which Java doesn't support).
     */
    protected open fun envOverride(): String? = System.getenv(ENV_VAR_NAME)

    @PostConstruct
    fun init() {
        val envOverride = envOverride()
        if (!envOverride.isNullOrBlank()) {
            apiKey = envOverride
            log.info("Using API key from {} env var", ENV_VAR_NAME)
            return
        }

        val dir = Path.of(dataDir)
        Files.createDirectories(dir)
        val file = dir.resolve(CREDENTIALS_FILE)

        if (Files.exists(file)) {
            val parsed = parse(Files.readAllLines(file))
            val existing = parsed[ENV_VAR_NAME]
            if (!existing.isNullOrBlank()) {
                apiKey = existing
                log.info("Loaded API key from {}", file)
                return
            }
            log.warn(
                "{} present but missing {} entry; generating a new key and overwriting",
                file, ENV_VAR_NAME,
            )
        }

        val generated = generateKey()
        writeCredentials(file, generated)
        apiKey = generated
        log.info("Generated new API key at {}", file)
    }

    private fun parse(lines: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun writeCredentials(file: Path, key: String) {
        val contents = "$ENV_VAR_NAME=$key\n"
        Files.writeString(file, contents)
        applyOwnerOnlyPerms(file)
    }

    private fun applyOwnerOnlyPerms(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            return
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem — fall through to the java.io.File best-effort path.
        } catch (e: Exception) {
            log.warn("Could not set POSIX perms on {}: {}", file, e.message)
        }
        val f = file.toFile()
        f.setReadable(false, false)
        f.setReadable(true, true)
        f.setWritable(false, false)
        f.setWritable(true, true)
    }

    private fun generateKey(): String {
        // SecureRandom.generateSeed is a blocking seed pull; fine at startup (one-shot).
        val bytes = SecureRandom().generateSeed(32)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val CREDENTIALS_FILE: String = "credentials.txt"
        const val ENV_VAR_NAME: String = "EHZ_API_KEY"
    }
}
