package com.maintenance.assetlog.data

import androidx.room.withTransaction
import com.maintenance.assetlog.domain.*
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepository @Inject constructor(private val database: LogDatabase) {
    val queries = database.logs()

    suspend fun save(asset: Asset, correction: Boolean) = database.withTransaction {
        validate(asset)
        validateOdometer(queries.findAsset(asset.id)?.odometer, asset.odometer, correction)
        queries.save(asset)
    }

    suspend fun save(entry: Maintenance) = database.withTransaction {
        validate(entry)
        val asset = checkNotNull(queries.findAsset(entry.assetId))
        queries.save(entry)
        if (entry.odometer != null && entry.odometer > (asset.odometer ?: -1)) {
            queries.save(asset.copy(odometer = entry.odometer))
        }
    }

    suspend fun save(part: Part) {
        validate(part)
        queries.save(part)
    }

    suspend fun snapshot() = database.withTransaction {
        Snapshot(queries.exportAssets(), queries.exportHistory(), queries.exportParts())
    }

    suspend fun restore(snapshot: Snapshot) {
        snapshot.assets.forEach { validate(it); require(it.id > 0) }
        snapshot.history.forEach { validate(it); require(it.id > 0) }
        snapshot.parts.forEach { validate(it); require(it.id > 0) }
        val ids = snapshot.assets.map { it.id }.toSet()
        require(ids.size == snapshot.assets.size)
        require(snapshot.history.distinctBy { it.id }.size == snapshot.history.size)
        require(snapshot.parts.distinctBy { it.id }.size == snapshot.parts.size)
        require(snapshot.history.all { it.assetId in ids } && snapshot.parts.all { it.assetId in ids })
        database.withTransaction {
            queries.clear()
            snapshot.assets.forEach { queries.save(it) }
            snapshot.history.forEach { queries.save(it) }
            snapshot.parts.forEach { queries.save(it) }
        }
    }

    private fun validate(asset: Asset) {
        require(asset.name.isNotBlank() && asset.category.isNotBlank())
        require(asset.price == null || asset.price in 0..1_000_000_000_000)
        require(asset.odometer == null || asset.odometer >= 0)
        require(asset.photo == null || asset.photo.size <= 512_000)
        asset.purchased.asDate()
    }

    private fun validate(entry: Maintenance) {
        require(entry.type.isNotBlank() && entry.cycle.isNotBlank())
        require(entry.cost in 0..1_000_000_000_000)
        require(entry.currency.matches(Regex("[A-Z]{3}")))
        nextDate(LocalDate.parse(entry.date), entry.months)
        entry.explicitNext.asDate()
        nextOdometer(entry.odometer, entry.intervalKm)
    }

    private fun validate(part: Part) {
        require(part.name.isNotBlank())
        part.warranty.asDate()
        part.expires.asDate()
    }
}

@kotlinx.serialization.Serializable
data class Snapshot(val assets: List<Asset>, val history: List<Maintenance>, val parts: List<Part>)
fun String.asDate(): LocalDate? = takeIf(String::isNotBlank)?.let(LocalDate::parse)

fun Maintenance.due(today: LocalDate, currentOdometer: Long?, days: Int): Due =
    calculateDue(today, explicitNext.asDate() ?: nextDate(LocalDate.parse(date), months),
        currentOdometer, nextOdometer(odometer, intervalKm), days)
