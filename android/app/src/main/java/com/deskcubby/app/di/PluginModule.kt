package com.deskcubby.app.di

import com.deskcubby.app.plugin.AppPluginContextFactory
import com.deskcubby.plugin.api.core.Plugin
import com.deskcubby.plugin.api.core.PluginManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginModule {
    @Multibinds
    abstract fun plugins(): Set<Plugin>

    companion object {
        @Provides
        @Singleton
        fun providePluginManager(contextFactory: AppPluginContextFactory): PluginManager =
            PluginManager(contextFactory)
    }
}
