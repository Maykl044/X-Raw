package ai.xrav.xravscan.data.seed

import ai.xrav.xravscan.db.XRavScanDb
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * One-shot first-launch seeder.
 *
 * Reads `seed_providers.json` + the per-provider `ranges/<seed_file>` text
 * files from the JAR resources and writes them into the SQLite database.
 * Re-runs are idempotent: if `providers` already has rows, the seeder is a
 * no-op; if a provider is added later, only the missing rows are inserted.
 */
class ProviderSeeder(private val db: XRavScanDb) {
    private val log = LoggerFactory.getLogger("XRavScan.Seeder")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun seedIfNeeded(): SeedReport {
        val existing = db.xRavScanDbQueries.countProviders().executeAsOne()
        if (existing > 0) {
            log.info("Skipping seed — {} providers already in DB", existing)
            return SeedReport(skipped = true, providers = existing.toInt(), cidrs = 0)
        }

        val seedJson = readResource("/seed/seed_providers.json")
            ?: error("seed/seed_providers.json missing from JAR resources")
        val parsed = json.decodeFromString(SeedFile.serializer(), seedJson)

        var providersInserted = 0
        var cidrsInserted = 0
        db.transaction {
            for (p in parsed.providers) {
                db.xRavScanDbQueries.upsertProvider(
                    slug = p.slug,
                    name = p.name,
                    color = p.color,
                    asns = p.asns.joinToString(","),
                    enabled = if (p.enabled) 1L else 0L,
                )
                providersInserted++
                val rangesText = p.seedFile?.let { readResource("/seed/ranges/$it") }
                if (rangesText.isNullOrBlank()) continue
                val providerId = db.xRavScanDbQueries.providerBySlug(p.slug).executeAsOne().id
                rangesText.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { cidr ->
                        val family = if (cidr.contains(":")) 6L else 4L
                        db.xRavScanDbQueries.insertCidr(providerId, cidr, family)
                        cidrsInserted++
                    }
            }
        }
        log.info("Seed complete — {} providers, {} CIDRs", providersInserted, cidrsInserted)
        return SeedReport(skipped = false, providers = providersInserted, cidrs = cidrsInserted)
    }

    private fun readResource(path: String): String? =
        ProviderSeeder::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
}

data class SeedReport(val skipped: Boolean, val providers: Int, val cidrs: Int)
