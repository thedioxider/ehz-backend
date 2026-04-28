package space.diomentia.ehzbackend.reports

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

/**
 * Composite-PK helper for [ReportField]. Referenced via [IdClass].
 * Field names and types must mirror the `@Id` fields on the entity.
 */
data class ReportFieldId(
    var site: UUID = UUID(0, 0),
    var specId: String = "",
) : Serializable

@Entity
@Table(name = "report_fields")
@IdClass(ReportFieldId::class)
class ReportField(
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    var site: Site,

    @Id
    @Column(name = "spec_id", nullable = false)
    var specId: String,

    @Column(name = "name", nullable = false)
    var name: String,

    // One of: text, bool, integer, decimal, photo, raw. Kept as String for now.
    @Column(name = "type", nullable = false)
    var type: String,

    @Column(name = "unit")
    var unit: String? = null,

    // For type=photo, stores a relative path under /data/photos/.
    @Column(name = "value")
    var value: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReportField) return false
        return specId == other.specId && site.id == other.site.id
    }

    override fun hashCode(): Int = 31 * (site.id?.hashCode() ?: 0) + specId.hashCode()
}
