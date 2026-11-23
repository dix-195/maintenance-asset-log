package com.maintenance.assetlog.data

import androidx.room.withTransaction
import com.maintenance.assetlog.domain.*
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepository @Inject constructor(private val database: LogDatabase) {
    val queries = database.logs()
    val ledger = database.ledger()

    suspend fun ensureCatalog() = database.withTransaction {
        if (ledger.exportSections().isEmpty()) {
            ledger.seedSections(defaultSections)
            ledger.seedServices(defaultServices)
        }
    }

    suspend fun save(section: Section) {
        require(section.name.isNotBlank() && section.name.length <= 80)
        ledger.save(section)
    }

    suspend fun save(service: Service) = database.withTransaction {
        validate(service)
        require(ledger.exportSections().any { it.id == service.sectionId && !it.archived })
        ledger.save(service)
    }

    suspend fun save(operation: Operation) = database.withTransaction {
        validate(operation)
        require(ledger.exportSections().any { it.id == operation.sectionId })
        val previous = ledger.find(operation.id)
        val successor = ledger.successor(operation.id)
        val vehicle = operation.vehicleId?.let { queries.findAsset(it) }
        require(previous?.vehicleId == operation.vehicleId || operation.vehicleId == null || vehicle != null)
        val row = operation.copy(previousId = previous?.previousId,
            anchorDay = operation.anchorDay.takeIf { previous == null || previous.dueOn == operation.dueOn })
        ledger.save(row)
        if (vehicle != null && operation.odometer != null && operation.state == PaymentState.PAID &&
            operation.odometer > (vehicle.odometer ?: -1)) {
            queries.save(vehicle.copy(odometer = operation.odometer))
        }
        if (previous?.state != PaymentState.PAID && successor == null) {
            row.followUp()?.let { ledger.save(it) }
        }
    }

    suspend fun clearAll() = database.withTransaction {
        queries.clear()
        ledger.clearOperations()
        ledger.clearServices()
        ledger.clearSections()
        ensureCatalog()
    }

    private fun validate(service: Service) {
        require(service.id.isNotBlank() && service.name.isNotBlank() &&
            service.amount?.let { it in 0..1_000_000_000_000 } != false)
        nextOccurrence(service.date.asDate() ?: LocalDate.now(), service.recurrence)
    }

    private fun validate(operation: Operation) {
        require(operation.title.isNotBlank() && operation.amount in 0..1_000_000_000_000)
        require(operation.currency.matches(Regex("[A-Z]{3}")))
        require((operation.state == PaymentState.PAID) == operation.paidOn.isNotBlank())
        val date = LocalDate.parse(operation.date)
        operation.paidOn.asDate()
        operation.endsOn.asDate()
        require(operation.anchorDay == null || operation.anchorDay in 1..31)
        require(operation.mileageInterval == null || operation.mileageInterval > 0)
        nextOccurrence(operation.dueOn.asDate() ?: date, operation.recurrence)
        require(operation.odometer == null || operation.odometer >= 0)
        require(operation.nextKm == null || (operation.vehicleId != null &&
            operation.nextKm >= 0 && (operation.odometer == null ||
                operation.nextKm >= operation.odometer)))
        require(operation.reminderDays == null || operation.reminderDays in 0..365)
        require(operation.attachment == null || operation.attachment.size <= 512_000)
    }


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
        Snapshot(queries.exportAssets(), queries.exportHistory(), queries.exportParts(),
            ledger.exportSections(), ledger.exportServices(), ledger.exportOperations())
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
        snapshot.operations.forEach(::validate)
        snapshot.sections.forEach { require(it.id.isNotBlank() && it.name.isNotBlank()) }
        snapshot.services.forEach(::validate)
        val precursors = snapshot.operations.mapNotNull { it.previousId }
        require(precursors.distinct().size == precursors.size)
        val sectionIds = snapshot.sections.map { it.id }.toSet()
        require(snapshot.operations.all { it.sectionId in sectionIds } &&
            snapshot.services.all { it.sectionId in sectionIds })
        require(sectionIds.size == snapshot.sections.size &&
            snapshot.services.distinctBy { it.id }.size == snapshot.services.size &&
            snapshot.operations.distinctBy { it.id }.size == snapshot.operations.size)
        database.withTransaction {
            queries.clear()
            ledger.clearOperations()
            ledger.clearServices()
            ledger.clearSections()
            snapshot.sections.forEach { ledger.save(it) }
            snapshot.services.forEach { ledger.save(it) }
            snapshot.operations.forEach { ledger.save(it) }
            ensureCatalog()
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
        require(asset.manufactureYear == null || asset.manufactureYear in 1886..(LocalDate.now().year + 2))
        require(asset.plate == null || asset.plate.length <= 32)
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
data class Snapshot(
    val assets: List<Asset>, val history: List<Maintenance>, val parts: List<Part>,
    val sections: List<Section> = emptyList(), val services: List<Service> = emptyList(),
    val operations: List<Operation> = emptyList()
)
fun String.asDate(): LocalDate? = takeIf(String::isNotBlank)?.let(LocalDate::parse)

fun Maintenance.due(today: LocalDate, currentOdometer: Long?, days: Int): Due =
    calculateDue(today, explicitNext.asDate() ?: nextDate(LocalDate.parse(date), months),
        currentOdometer, nextOdometer(odometer, intervalKm), days)
