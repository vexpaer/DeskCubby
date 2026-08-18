package com.deskcubby.app.di

import android.content.Context
import androidx.room.Room
import com.deskcubby.app.data.local.AiChatDao
import com.deskcubby.app.data.local.AgentDao
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.BrowserRecordDao
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DateRecordDao
import com.deskcubby.app.data.local.FlashThoughtDao
import com.deskcubby.app.data.local.GameStateDao
import com.deskcubby.app.data.local.GameStatisticDao
import com.deskcubby.app.data.local.LegacyStatisticsMigrationDao
import com.deskcubby.app.data.local.PoetryCategoryDao
import com.deskcubby.app.data.local.SavedPoemDao
import com.deskcubby.app.data.local.StepStatisticsDao
import com.deskcubby.app.data.local.StructuredRecordDao
import com.deskcubby.app.data.local.ThoughtCategoryDao
import com.deskcubby.app.data.local.UsageStatisticsDao
import com.deskcubby.app.data.local.VaultItemDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "deskcubby.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                    AppDatabase.MIGRATION_12_13,
                    AppDatabase.MIGRATION_13_14,
                    AppDatabase.MIGRATION_14_15,
            )
            .build()

    @Provides fun provideFlashThoughtDao(db: AppDatabase): FlashThoughtDao = db.flashThoughtDao()
    @Provides fun provideThoughtCategoryDao(db: AppDatabase): ThoughtCategoryDao = db.thoughtCategoryDao()
    @Provides fun provideBrowserRecordDao(db: AppDatabase): BrowserRecordDao = db.browserRecordDao()
    @Provides fun provideDiaryIndexDao(db: AppDatabase): DiaryIndexDao = db.diaryIndexDao()
    @Provides fun provideDateRecordDao(db: AppDatabase): DateRecordDao = db.dateRecordDao()
    @Provides fun providePoetryCategoryDao(db: AppDatabase): PoetryCategoryDao = db.poetryCategoryDao()
    @Provides fun provideSavedPoemDao(db: AppDatabase): SavedPoemDao = db.savedPoemDao()
    @Provides fun provideAiChatDao(db: AppDatabase): AiChatDao = db.aiChatDao()
    @Provides fun provideAgentDao(db: AppDatabase): AgentDao = db.agentDao()
    @Provides fun provideVaultItemDao(db: AppDatabase): VaultItemDao = db.vaultItemDao()
    @Provides fun provideGameStateDao(db: AppDatabase): GameStateDao = db.gameStateDao()
    @Provides
    fun provideGameStatisticDao(db: AppDatabase): GameStatisticDao = db.gameStatisticDao()
    @Provides
    fun provideUsageStatisticsDao(db: AppDatabase): UsageStatisticsDao = db.usageStatisticsDao()

    @Provides
    fun provideStepStatisticsDao(db: AppDatabase): StepStatisticsDao = db.stepStatisticsDao()

    @Provides
    fun provideLegacyStatisticsMigrationDao(
        db: AppDatabase,
    ): LegacyStatisticsMigrationDao = db.legacyStatisticsMigrationDao()

    @Provides
    fun provideStructuredRecordDao(
        db: AppDatabase,
    ): StructuredRecordDao = db.structuredRecordDao()
}
