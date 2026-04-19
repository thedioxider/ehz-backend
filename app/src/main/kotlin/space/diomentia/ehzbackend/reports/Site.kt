package space.diomentia.ehzbackend.reports

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sites")
class Site(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    var report: Report,

    @Column(name = "position_km")
    var positionKm: Double? = null,

    @Column(name = "lat")
    var lat: Double? = null,

    @Column(name = "lng")
    var lng: Double? = null,

    @Column(name = "timestamp", nullable = false)
    var timestamp: Instant,

    @Column(name = "validation_id")
    var validationId: String? = null,

    @OneToMany(
        mappedBy = "site",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    var fields: MutableList<ReportField> = mutableListOf(),
)
