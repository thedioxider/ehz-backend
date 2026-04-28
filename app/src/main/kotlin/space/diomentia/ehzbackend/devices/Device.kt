package space.diomentia.ehzbackend.devices

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "devices")
class Device(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @Column(name = "name")
    var name: String? = null,

    @Column(name = "first_seen", nullable = false)
    var firstSeen: Instant,

    @Column(name = "last_seen", nullable = false)
    var lastSeen: Instant,
)
