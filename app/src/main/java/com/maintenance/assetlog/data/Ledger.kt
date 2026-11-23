package com.maintenance.assetlog.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID

const val CAR = "car"
const val HOUSE = "house"
const val MOBILE = "mobile"
const val PERSONAL = "personal"
const val LEGACY = "legacy"

@Serializable
@Entity
data class Section(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String = "عام",
    val color: Long = 0xFF5A7D6C,
    val description: String = "",
    val archived: Boolean = false
)

@Serializable
@Entity(indices = [Index("sectionId")])
data class Service(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sectionId: String,
    val name: String,
    val type: String = "",
    val amount: Long? = null,
    val date: String = "",
    val recurrence: String = "",
    val remind: Boolean = true,
    val notes: String = ""
)

enum class PaymentState(val label: String) {
    EXPECTED("متوقعة"), DUE("مستحقة"), PAID("مدفوعة"), CANCELLED("ملغاة")
}

@Serializable
@Entity(indices = [Index("sectionId"), Index("paidOn"), Index("dueOn"), Index(value = ["previousId"], unique = true)])
data class Operation(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sectionId: String,
    val title: String,
    val amount: Long,
    val currency: String,
    val state: PaymentState,
    val date: String,
    val dueOn: String = "",
    val paidOn: String = "",
    val endsOn: String = "",
    val recurrence: String = "",
    val reminderDays: Int? = null,
    val remind: Boolean = true,
    val subject: String = "",
    val provider: String = "",
    val account: String = "",
    val method: String = "",
    val notes: String = "",
    val attachment: ByteArray? = null,
    val vehicleId: Long? = null,
    val odometer: Long? = null,
    val nextKm: Long? = null,
    val previousId: String? = null,
    val anchorDay: Int? = null,
    val mileageInterval: Long? = null
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM Section ORDER BY name") fun sections(): Flow<List<Section>>
    @Query("SELECT * FROM Service ORDER BY name") fun services(): Flow<List<Service>>
    @Query("""
        SELECT id,sectionId,title,amount,currency,state,date,dueOn,paidOn,endsOn,recurrence,
        reminderDays,remind,subject,provider,account,method,notes,NULL AS attachment,vehicleId,
        odometer,nextKm,previousId,anchorDay,mileageInterval
        FROM Operation ORDER BY date DESC,id DESC
    """) fun operations(): Flow<List<Operation>>
    @Query("SELECT * FROM Operation WHERE id=:id") fun operation(id: String): Flow<Operation?>
    @Query("SELECT * FROM Operation WHERE id=:id") suspend fun find(id: String): Operation?
    @Query("SELECT * FROM Operation WHERE previousId=:id") suspend fun successor(id: String): Operation?
    @Query("SELECT * FROM Section ORDER BY id") suspend fun exportSections(): List<Section>
    @Query("SELECT * FROM Service ORDER BY id") suspend fun exportServices(): List<Service>
    @Query("SELECT * FROM Operation ORDER BY id") suspend fun exportOperations(): List<Operation>
    @Upsert suspend fun save(section: Section)
    @Upsert suspend fun save(service: Service)
    @Upsert suspend fun save(operation: Operation)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seedSections(sections: List<Section>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seedServices(services: List<Service>)
    @Query("UPDATE Section SET archived=:archived WHERE id=:id") suspend fun archive(id: String, archived: Boolean)
    @Query("DELETE FROM Service WHERE id=:id") suspend fun deleteService(id: String)
    @Query("DELETE FROM Operation WHERE id=:id") suspend fun deleteOperation(id: String)
    @Query("DELETE FROM Operation") suspend fun clearOperations()
    @Query("DELETE FROM Service") suspend fun clearServices()
    @Query("DELETE FROM Section") suspend fun clearSections()
}
