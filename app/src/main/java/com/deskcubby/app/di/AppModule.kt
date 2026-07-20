package com.deskcubby.app.di

import android.content.Context
import androidx.room.Room
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.BrowserRecordDao
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DateRecordDao
import com.deskcubby.app.data.local.FlashThoughtDao
import com.deskcubby.app.data.local.SavedPoemDao
import com.deskcubby.app.data.local.ThoughtCategoryDao
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
            )
            .build()

    @Provides fun provideFlashThoughtDao(db: AppDatabase): FlashThoughtDao = db.flashThoughtDao()
    @Provides fun provideThoughtCategoryDao(db: AppDatabase): ThoughtCategoryDao = db.thoughtCategoryDao()
    @Provides fun provideBrowserRecordDao(db: AppDatabase): BrowserRecordDao = db.browserRecordDao()
    @Provides fun provideDiaryIndexDao(db: AppDatabase): DiaryIndexDao = db.diaryIndexDao()
    @Provides fun provideDateRecordDao(db: AppDatabase): DateRecordDao = db.dateRecordDao()
    @Provides fun provideSavedPoemDao(db: AppDatabase): SavedPoemDao = db.savedPoemDao()
}
