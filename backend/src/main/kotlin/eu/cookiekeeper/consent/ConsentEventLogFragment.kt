package eu.cookiekeeper.consent

import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Predicate
import java.time.Instant
import java.util.UUID

/**
 * Bounded, filterable query over a site's consent log. [from] is inclusive, [to] exclusive (a half-open
 * range composes cleanly for day/month buckets). [cursor] is the keyset anchor: when set, only rows strictly
 * older than that `(createdAt, eventId)` are returned. [limit] is already coerced to a sane page size by the
 * caller. [visitorId] is an exact match (indexed); [action]/[lang] are exact-string equality.
 */
data class ConsentLogQuery(
    val from: Instant? = null,
    val to: Instant? = null,
    val action: String? = null,
    val lang: String? = null,
    val visitorId: UUID? = null,
    val cursor: ConsentLogCursorPosition? = null,
    val limit: Int,
)

/**
 * Read-only, keyset-paginated search over the append-only [consent_events][ConsentEventEntity] table. Kept as a
 * Spring Data fragment (not a `JpaSpecificationExecutor`, which would drag `delete(Specification)` onto the
 * repository and break the type-level append-only guarantee). Newest-first by `(createdAt, eventId)`, which
 * doubles as the keyset key — so paging never uses OFFSET, rides the `(site_id, created_at)` index, and prunes
 * to the relevant monthly partitions rather than scanning the whole multi-year table.
 */
interface ConsentEventLogFragment {
    fun search(
        siteId: UUID,
        query: ConsentLogQuery,
    ): List<ConsentEventEntity>
}

/**
 * Criteria implementation of [ConsentEventLogFragment]. Predicates are added only for provided filters, so an
 * absent filter widens nothing. [EntityManager] is constructor-injected (Spring Data instantiates the `*Impl`
 * fragment as a bean), keeping the class immutable and free of field injection.
 */
class ConsentEventLogFragmentImpl(
    private val entityManager: EntityManager,
) : ConsentEventLogFragment {
    override fun search(
        siteId: UUID,
        query: ConsentLogQuery,
    ): List<ConsentEventEntity> {
        val cb = entityManager.criteriaBuilder
        val criteria = cb.createQuery(ConsentEventEntity::class.java)
        val root = criteria.from(ConsentEventEntity::class.java)

        val createdAt = root.get<Instant>("createdAt")
        val eventId = root.get<UUID>("eventId")

        val predicates = mutableListOf(cb.equal(root.get<UUID>("siteId"), siteId))
        query.from?.let { predicates += cb.greaterThanOrEqualTo(createdAt, it) }
        query.to?.let { predicates += cb.lessThan(createdAt, it) }
        query.action?.let { predicates += cb.equal(root.get<String>("action"), it) }
        query.lang?.let { predicates += cb.equal(root.get<String>("lang"), it) }
        query.visitorId?.let { predicates += cb.equal(root.get<UUID>("visitorId"), it) }
        // Keyset over the same axis we order by: rows strictly "older" than the cursor under (createdAt DESC,
        // eventId DESC). The eventId tiebreaker only matters for rows sharing an exact timestamp.
        query.cursor?.let { pos ->
            predicates +=
                cb.or(
                    cb.lessThan(createdAt, pos.createdAt),
                    cb.and(cb.equal(createdAt, pos.createdAt), cb.lessThan(eventId, pos.eventId)),
                )
        }

        criteria
            .select(root)
            .where(*predicates.toTypedArray<Predicate>())
            .orderBy(cb.desc(createdAt), cb.desc(eventId))

        return entityManager
            .createQuery(criteria)
            .setMaxResults(query.limit)
            .resultList
    }
}
