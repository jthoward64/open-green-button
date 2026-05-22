package org.opengb.utility

/**
 * In-memory lookup over the configured utilities. Loaded once at startup from `utilities.conf`
 * via Hoplite; not mutable at runtime (server restart picks up changes).
 */
class UtilityRegistry(profiles: List<UtilityProfile>) {
    private val byId: Map<String, UtilityProfile> = profiles.associateBy { it.id }

    init {
        require(byId.size == profiles.size) {
            val dups = profiles.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            "Duplicate utility id in configuration: $dups"
        }
    }

    operator fun get(id: String): UtilityProfile? = byId[id]

    fun require(id: String): UtilityProfile = byId[id] ?: throw UnknownUtilityException(id)

    fun all(): Collection<UtilityProfile> = byId.values

    fun summary(): List<UtilitySummary> = byId.values.map { UtilitySummary(it.id, it.displayName) }
}

data class UtilitySummary(val id: String, val displayName: String)

class UnknownUtilityException(val utilityId: String) :
    NoSuchElementException("No utility configured with id '$utilityId'")
