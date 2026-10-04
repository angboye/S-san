package com.example.sscan.di

import android.content.Context
import androidx.room.Room
import com.example.sscan.core.auth.MockSmsService
import com.example.sscan.core.auth.SmsService
import com.example.sscan.data.local.AppDatabase
import com.example.sscan.data.local.dao.ScanHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 提供 Room 数据库及相关 DAO 的实例。
 *
 * 使用 @Provides 方法（而非 @Binds），因为这些是具体类的实例化。
 */
@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(AppDatabase.MIGRATION_3_4)
            // 保留兜底：真正的 3->4 走上面的 Migration；未覆盖的 schema 变化不能直接崩溃。
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideScanHistoryDao(
        appDatabase: AppDatabase
    ): ScanHistoryDao {
        return appDatabase.scanHistoryDao()
    }

    /**
     * 当前注入 Mock 短信服务（验证码固定为 123456）。
     * 接入真实短信 API 后：
     *   1. 将返回类型改为 RealSmsService(...)；
     *   2. 无需修改 LoginViewModel / LoginActivity。
     */
    @Provides
    @Singleton
    fun provideSmsService(mock: MockSmsService): SmsService {
        return mock
    }
}
