package space.diomentia.ehzbackend.devices

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Small transactional helper around `devices` upsert-on-sight. Exists as a
 * service (not a method on the filter) because servlet filters aren't proxied
 * by Spring's `@Transactional` AOP — the AOP post-processor only looks at
 * beans created through the normal container, and filters get registered via
 * the servlet-container path.
 *
 * Upsert is delegated to a native Postgres `INSERT ... ON CONFLICT DO UPDATE`
 * so two concurrent ingests from the same device can't race each other into
 * a PK-violation. The previous read-modify-write pattern let both concurrent
 * paths observe `null` and both attempt an insert; the loser would throw and
 * the filter would silently drop device attribution for that request.
 */
@Service
class DeviceTracker(
    private val deviceRepository: DeviceRepository,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun touch(id: UUID): Device {
        val now = Instant.now()
        deviceRepository.upsertTouch(id = id, name = null, now = now)
        // Flush the native @Modifying write and drop any stale managed entity
        // from the persistence context so the subsequent findById materializes
        // the row we just committed (otherwise JPA could return a cached copy
        // with a stale last_seen from before the native update).
        entityManager.flush()
        entityManager.clear()
        return deviceRepository.findById(id).orElseThrow {
            IllegalStateException("Device $id missing immediately after upsert")
        }
    }
}
