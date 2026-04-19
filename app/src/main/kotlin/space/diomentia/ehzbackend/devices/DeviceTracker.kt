package space.diomentia.ehzbackend.devices

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
 */
@Service
class DeviceTracker(
    private val deviceRepository: DeviceRepository,
) {
    @Transactional
    fun touch(id: UUID): Device {
        val now = Instant.now()
        val existing = deviceRepository.findById(id).orElse(null)
        return if (existing != null) {
            existing.lastSeen = now
            deviceRepository.save(existing)
        } else {
            deviceRepository.save(
                Device(id = id, firstSeen = now, lastSeen = now),
            )
        }
    }
}
