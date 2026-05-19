package ai.xrav.xravscan.data.seed

import android.content.Context
import android.util.Log
import ai.xrav.xravscan.data.local.XRavScanDatabase
import ai.xrav.xravscan.data.local.entity.CidrRangeEntity
import ai.xrav.xravscan.data.local.entity.ProviderEntity
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Populates the Room database with the bundled provider list and their CIDR
 * ranges on first launch. Idempotent — calling [ensureSeeded] when the DB
 * already has providers is a cheap no-op.
 */
@Singleton
class ProviderSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: XRavScanDatabase,
) {

    private val json: Json = Json { ignoreUnknownKeys = true }

    /** Call from the application scope at boot. */
    suspend fun ensureSeeded() {
        val existing = db.providerDao().count()
        if (existing > 0) {
            Log.i(TAG, "providers already seeded ($existing rows) — skipping")
            return
        }

        val seed = loadSeedFile()
        if (seed.providers.isEmpty()) {
            Log.w(TAG, "seed_providers.json is empty")
            return
        }

        val providerEntities = seed.providers.map { it.toEntity() }
        val rangeEntities = mutableListOf<CidrRangeEntity>()
        for (p in seed.providers) {
            val seedFileName = p.seedFile ?: continue
            val cidrs = readSeedRanges(seedFileName)
            rangeEntities += cidrs.map { cidr ->
                CidrRangeEntity(
                    providerSlug = p.slug,
                    cidr = cidr,
                    source = "bundled:$seedFileName",
                )
            }
        }

        db.withTransaction {
            db.providerDao().upsertAll(providerEntities)
            // Insert in chunks to keep the SQLite statement size sane.
            rangeEntities.chunked(2_000).forEach { db.cidrRangeDao().insertAll(it) }
        }
        Log.i(
            TAG,
            "seeded ${providerEntities.size} providers and ${rangeEntities.size} CIDRs",
        )
    }

    private fun loadSeedFile(): SeedFile {
        return runCatching {
            context.assets.open("seed/seed_providers.json").use { stream ->
                json.decodeFromString(SeedFile.serializer(), stream.bufferedReader().readText())
            }
        }.getOrElse { e ->
            Log.e(TAG, "failed to load seed_providers.json", e)
            SeedFile()
        }
    }

    private fun readSeedRanges(seedFileName: String): List<String> {
        val path = "seed/ranges/$seedFileName"
        return runCatching {
            context.assets.open(path).bufferedReader().useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .toList()
            }
        }.getOrElse { e ->
            Log.w(TAG, "missing seed range file $path", e)
            emptyList()
        }
    }

    private fun SeedProvider.toEntity(): ProviderEntity = ProviderEntity(
        slug = slug,
        name = name,
        color = color,
        asnsCsv = asns.joinToString(","),
        enabled = true,
    )

    companion object {
        private const val TAG = "XRavScan.Seeder"
    }
}
