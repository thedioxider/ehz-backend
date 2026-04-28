package space.diomentia.ehzbackend.devices

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface DeviceRepository : JpaRepository<Device, UUID> {
    /**
     * Atomic "seen" upsert. Two concurrent ingests from the same device can't
     * race each other into a PK-violation (the prior read-modify-write pattern
     * was not safe under concurrency). On insert both `first_seen` and
     * `last_seen` are set to `now`; on conflict only `last_seen` advances.
     *
     * Used together with [JpaRepository.findById] inside the same transaction
     * — the upsert commits the row's current state; the subsequent read sees
     * our own write (JPA will hit the DB because the entity isn't yet in the
     * persistence context after a native `@Modifying` query).
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO devices (id, name, first_seen, last_seen)
            VALUES (:id, :name, :now, :now)
            ON CONFLICT (id) DO UPDATE SET last_seen = EXCLUDED.last_seen
        """,
        nativeQuery = true,
    )
    fun upsertTouch(
        @Param("id") id: UUID,
        @Param("name") name: String?,
        @Param("now") now: Instant,
    ): Int
}
