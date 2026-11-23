package com.maintenance.assetlog

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.maintenance.assetlog.core.databasePassword
import com.maintenance.assetlog.data.LogDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@HiltAndroidApp
class AssetApplication : Application()

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LogDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, LogDatabase::class.java, "maintenance.db")
            .openHelperFactory(SupportOpenHelperFactory(databasePassword(context)))
            .build()
    }
}
