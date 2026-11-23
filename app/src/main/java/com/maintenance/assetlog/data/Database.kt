package com.maintenance.assetlog.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index("name"), Index("category")])
@kotlinx.serialization.Serializable
data class Asset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val category: String, val maker: String = "", val model: String = "",
    val serial: String = "", val purchased: String = "", val price: Long? = null,
    val specs: String = "", val notes: String = "", val photo: ByteArray? = null,
    val archived: Boolean = false, val odometer: Long? = null
)

@Entity(
    foreignKeys = [ForeignKey(entity = Asset::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index("date"), Index("cycle")]
)
@kotlinx.serialization.Serializable
data class Maintenance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long, val type: String, val part: String = "", val date: String,
    val odometer: Long? = null, val cost: Long = 0, val currency: String = "SAR",
    val technician: String = "", val notes: String = "",
    val months: Int? = null, val intervalKm: Long? = null, val explicitNext: String = "",
    val cycle: String, val status: String = "مكتملة"
)

@Entity(foreignKeys = [ForeignKey(entity = Asset::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)], indices = [Index("assetId"), Index("expires")])
@kotlinx.serialization.Serializable
data class Part(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long, val name: String, val warranty: String = "", val expires: String = "",
    val notes: String = ""
)

data class AssetItem(val id: Long, val name: String, val category: String, val archived: Boolean, val odometer: Long?)
data class CostTotal(val currency: String, val total: Long)

private const val FILTERED_HISTORY = """
    FROM Maintenance m JOIN Asset a ON m.assetId=a.id
    WHERE (:type='' OR m.type=:type) AND (:date='' OR instr(m.date,:date)=1)
    AND (:search='' OR instr(lower(a.name),lower(:search))>0 OR instr(lower(m.type),lower(:search))>0
        OR instr(lower(m.part),lower(:search))>0 OR instr(lower(m.notes),lower(:search))>0)
"""

@Dao
interface LogDao {
    @Query("SELECT id,name,category,archived,odometer FROM Asset ORDER BY name, id")
    fun assets(): Flow<List<AssetItem>>
    @Query("SELECT * FROM Asset WHERE id=:id") fun asset(id: Long): Flow<Asset?>
    @Query("SELECT * FROM Asset WHERE id=:id") suspend fun findAsset(id: Long): Asset?
    @Query("SELECT * FROM Asset ORDER BY id") suspend fun exportAssets(): List<Asset>
    @Query("SELECT * FROM Maintenance ORDER BY date DESC,id DESC LIMIT :limit") fun history(limit: Int): Flow<List<Maintenance>>
    @Query("""
        SELECT * FROM Maintenance WHERE assetId=:assetId
        ORDER BY date DESC,id DESC LIMIT :limit
    """) fun assetHistory(assetId: Long, limit: Int): Flow<List<Maintenance>>
    @Query("SELECT m.* " + FILTERED_HISTORY + " ORDER BY m.date DESC,m.id DESC LIMIT :limit")
    fun filteredHistory(search: String, date: String, type: String, limit: Int): Flow<List<Maintenance>>
    @Query("SELECT m.currency, SUM(m.cost) AS total " + FILTERED_HISTORY + " GROUP BY m.currency")
    fun filteredTotals(search: String, date: String, type: String): Flow<List<CostTotal>>
    @Query("SELECT DISTINCT type FROM Maintenance ORDER BY type") fun types(): Flow<List<String>>
    @Query("SELECT * FROM Maintenance WHERE id=:id") fun maintenance(id: Long): Flow<Maintenance?>
    @Query("SELECT * FROM Maintenance ORDER BY id") suspend fun exportHistory(): List<Maintenance>
    @Query("""
        SELECT m.* FROM Maintenance m JOIN Asset a ON a.id=m.assetId
        WHERE a.archived=0 AND (m.months IS NOT NULL OR m.intervalKm IS NOT NULL OR m.explicitNext!='')
        AND NOT EXISTS (SELECT 1 FROM Maintenance n WHERE n.cycle=m.cycle
            AND (n.date>m.date OR (n.date=m.date AND n.id>m.id)))
    """) fun cycles(): Flow<List<Maintenance>>
    @Query("SELECT * FROM Part WHERE assetId=:id ORDER BY name") fun parts(id: Long): Flow<List<Part>>
    @Query("SELECT * FROM Part ORDER BY id") suspend fun exportParts(): List<Part>
    @Query("SELECT p.* FROM Part p JOIN Asset a ON a.id=p.assetId WHERE a.archived=0 AND (p.warranty!='' OR p.expires!='')")
    suspend fun remindingParts(): List<Part>
    @Query("SELECT currency, SUM(cost) AS total FROM Maintenance GROUP BY currency") fun totals(): Flow<List<CostTotal>>
    @Query("SELECT currency, SUM(cost) AS total FROM Maintenance WHERE assetId=:assetId GROUP BY currency")
    fun assetTotals(assetId: Long): Flow<List<CostTotal>>

    @Upsert suspend fun save(asset: Asset): Long
    @Upsert suspend fun save(log: Maintenance): Long
    @Upsert suspend fun save(part: Part): Long
    @Query("DELETE FROM Asset WHERE id=:id") suspend fun deleteAsset(id: Long)
    @Query("DELETE FROM Maintenance WHERE id=:id") suspend fun deleteMaintenance(id: Long)
    @Query("DELETE FROM Part WHERE id=:id") suspend fun deletePart(id: Long)
    @Query("DELETE FROM Asset") suspend fun clear()
    @Query("UPDATE Asset SET archived=:archived WHERE id=:id") suspend fun archive(id: Long, archived: Boolean)
}

@Database(entities = [Asset::class, Maintenance::class, Part::class], version = 1)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logs(): LogDao
}
