package space.diomentia.ehzbackend.reports

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType
import space.diomentia.ehzbackend.catalog.Route
import space.diomentia.ehzbackend.catalog.Section
import space.diomentia.ehzbackend.devices.Device
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "reports")
class Report(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    var device: Device? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    var route: Route,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    var section: Section,

    @Column(name = "inspector", nullable = false)
    var inspector: String,

    @Column(name = "start_time", nullable = false)
    var startTime: Instant,

    @Column(name = "end_time")
    var endTime: Instant? = null,

    // Populated by DB default (now()); read-only from the entity side.
    @Generated(event = [EventType.INSERT])
    @Column(name = "received_at", insertable = false, updatable = false)
    var receivedAt: Instant? = null,
)
