package space.diomentia.ehzbackend.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory

/**
 * Pure-unit coverage for [CredentialsStore] — does not boot a Spring context.
 *
 * The env-override branch is normally driven by `System.getenv(EHZ_API_KEY)`,
 * and Java does not allow mutating the process env at runtime; we use the
 * `envOverride()` protected seam so tests can drive all three branches
 * (env, existing file, first-boot generation) without a JVM fork.
 */
class CredentialsStoreTest {

    @Test
    fun `first boot generates a fresh key file with owner-only perms`() {
        val dir = createTempDirectory("creds-test-first-")
        val store = CredentialsStore(dir.toString())

        store.init()

        val file = dir.resolve(CredentialsStore.CREDENTIALS_FILE)
        assertThat(Files.exists(file)).isTrue()

        val lines = Files.readAllLines(file)
        val keyLine = lines.single { it.startsWith("EHZ_API_KEY=") }
        val fileKey = keyLine.substringAfter('=')
        assertThat(fileKey).isNotBlank()
        assertThat(store.apiKey).isEqualTo(fileKey)

        // Perms check is POSIX-only; skip the assertion gracefully on non-POSIX FS.
        try {
            val perms = Files.getPosixFilePermissions(file)
            assertThat(perms).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // non-POSIX filesystem — best-effort perms applied via java.io.File setters
        }
    }

    @Test
    fun `second boot reuses an existing key from the file`() {
        val dir = createTempDirectory("creds-test-existing-")
        val file = dir.resolve(CredentialsStore.CREDENTIALS_FILE)
        Files.writeString(file, "# comment\nEHZ_API_KEY=existing-key\n\n")

        val store = CredentialsStore(dir.toString())
        store.init()

        assertThat(store.apiKey).isEqualTo("existing-key")
    }

    @Test
    fun `env override takes precedence and skips file creation`() {
        val dir = createTempDirectory("creds-test-env-")
        val store = object : CredentialsStore(dir.toString()) {
            override fun envOverride(): String? = "env-injected-key"
        }

        store.init()

        assertThat(store.apiKey).isEqualTo("env-injected-key")
        // File must not be written when the env branch is taken — we don't
        // want a test run to spill credentials onto disk.
        assertThat(Files.exists(dir.resolve(CredentialsStore.CREDENTIALS_FILE))).isFalse()
    }
}
