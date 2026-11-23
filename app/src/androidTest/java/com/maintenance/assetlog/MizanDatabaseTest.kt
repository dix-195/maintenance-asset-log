package com.maintenance.assetlog

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maintenance.assetlog.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MizanDatabaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun migratesVersionOneWithExistingMaintenance() = runBlocking {
        val file = "mizan-migration-test.db"
        context.deleteDatabase(file)
        val schema = instrumentation.context.assets.open("com.maintenance.assetlog.data.LogDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        System.loadLibrary("sqlcipher")
        val key = ByteArray(32) { (it + 1).toByte() }
        val old = SupportOpenHelperFactory(key.copyOf()).create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indexes = entity.getJSONArray("indices")
                        for (i in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(i).getString("createSql")
                            .replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    db.execSQL("INSERT INTO Asset (id,name,category,maker,model,serial,purchased,specs,notes,archived) VALUES (1,'السيارة','سيارة','','','','','','',0)")
                    db.execSQL("INSERT INTO Maintenance (assetId,type,part,date,cost,currency,technician,notes,explicitNext,cycle,status) VALUES (1,'زيت','','2026-01-01',1200,'SAR','','','','oil','مكتملة')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        old.writableDatabase
        old.close()
        val modern = Room.databaseBuilder(context, LogDatabase::class.java, file)
            .openHelperFactory(SupportOpenHelperFactory(key)).build()
        try {
            val repository = LogRepository(modern)
            repository.ensureCatalog()
            assertEquals("السيارة", repository.queries.findAsset(1)?.name)
            assertEquals(1200L, repository.snapshot().history.single().cost)
            assertTrue(repository.snapshot().operations.isEmpty())
            assertTrue(repository.snapshot().services.any { it.name == "تغيير زيت المحرك" })
        } finally {
            modern.close()
            context.deleteDatabase(file)
        }
    }

    @Test fun updatesPaymentIdempotentlyAndRetainsHistoryOnArchive() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java).build()
        try {
            val repository = LogRepository(db)
            repository.ensureCatalog()
            val custom = Section(name = "السفر")
            repository.save(custom)
            val row = Operation(sectionId = custom.id, title = "تذكرة", amount = 50, currency = "SAR",
                state = PaymentState.DUE, date = "2026-01-01", recurrence = "Y")
            repository.save(row)
            val paid = row.copy(state = PaymentState.PAID, paidOn = "2026-01-02")
            repository.save(paid)
            repository.save(paid.copy(amount = 60))
            repository.ledger.archive(custom.id, true)
            val snapshot = repository.snapshot()
            assertEquals(2, snapshot.operations.size)
            assertEquals(60L, snapshot.operations.single { it.state == PaymentState.PAID }.amount)
            assertTrue(snapshot.sections.single { it.id == custom.id }.archived)
            repository.clearAll()
            repository.restore(snapshot)
            assertEquals(snapshot, repository.snapshot())
            repository.ledger.deleteOperation(row.id)
            assertEquals(1, repository.snapshot().operations.size)
        } finally {
            db.close()
        }
    }
}
