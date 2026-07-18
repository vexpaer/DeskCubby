package com.deskcubby.app.di

import android.content.Context
import androidx.room.Room
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.BrowserRecordDao
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.FlashThoughtDao
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
        Room.databaseBuilder(context, AppDatabase::class.java, "deskcubby.db").build()

    @Provides fun provideFlashThoughtDao(db: AppDatabase): FlashThoughtDao = db.flashThoughtDao()
    @Provides fun provideBrowserRecordDao(db: AppDatabase): BrowserRecordDao = db.browserRecordDao()
    @Provides fun provideDiaryIndexDao(db: AppDatabase): DiaryIndexDao = db.diaryIndexDao()
}
