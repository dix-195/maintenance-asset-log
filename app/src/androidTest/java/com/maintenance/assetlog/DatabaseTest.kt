package com.maintenance.assetlog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maintenance.assetlog.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @Test fun assetLifecycleAndForeignKeys() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java).build()
        try {
            val repository = LogRepository(db)
            val id = repository.save(Asset(name = "سيارة", category = "مركبة", odometer = 100), false)
            val asset = checkNotNull(db.logs().findAsset(id))
            repository.save(asset.copy(name = "سيارة الأسرة"), false)
            assertEquals("سيارة الأسرة", db.logs().findAsset(id)?.name)
            val entry = Maintenance(assetId = id, type = "فحص", date = "2025-01-01", odometer = 200, cycle = "oil")
            repository.save(entry)
            repository.save(entry.copy(date = "2025-01-02", odometer = 220, months = 2))
            assertEquals(220L, db.logs().findAsset(id)?.odometer)
            val history = db.logs().assetHistory(id, 10).first()
            assertEquals(2, history.size)
            db.logs().deleteMaintenance(history.last().id)
            assertEquals(history.first().id, db.logs().cycles().first().single().id)
            db.logs().deleteAsset(id)
            assertTrue(db.logs().assetHistory(id, 10).first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun invalidRestorePreservesExistingData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java).build()
        try {
            val repository = LogRepository(db)
            repository.save(Asset(name = "مكيف", category = "جهاز"), false)
            val before = repository.snapshot()
            val bad = Snapshot(emptyList(), listOf(Maintenance(id = 1, assetId = 77, type = "فحص", date = "2025-01-01", cycle = "invalid")), emptyList())
            assertTrue(runCatching { repository.restore(bad) }.isFailure)
            assertEquals(before, repository.snapshot())
            repository.restore(Snapshot(emptyList(), emptyList(), emptyList()))
            assertTrue(repository.snapshot().assets.isEmpty())
            repository.restore(before)
            assertEquals(before, repository.snapshot())
        } finally {
            db.close()
        }
    }
}
