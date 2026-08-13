package com.deskcubby.app.di

import com.deskcubby.app.agent.AgentToolContributor
import com.deskcubby.app.agent.AgentApprovalGateway
import com.deskcubby.app.agent.AgentContextProvider
import com.deskcubby.app.agent.AgentModelClient
import com.deskcubby.app.agent.AgentPermissionManager
import com.deskcubby.app.agent.AgentReviewRepository
import com.deskcubby.app.agent.AgentReviewStore
import com.deskcubby.app.agent.AgentToolExecutionGateway
import com.deskcubby.app.agent.AgentToolExecutor
import com.deskcubby.app.agent.AgentWebService
import com.deskcubby.app.agent.BuiltInAgentToolContributor
import com.deskcubby.app.agent.DefaultAgentContextProvider
import com.deskcubby.app.agent.DefaultAgentWebService
import com.deskcubby.app.agent.OpenAiCompatibleAgentModelClient
import com.deskcubby.app.plugin.adapter.DiaryApiAdapter
import com.deskcubby.app.plugin.adapter.AppApiAdapter
import com.deskcubby.app.plugin.adapter.AiApiAdapter
import com.deskcubby.app.plugin.adapter.DeskCubbyDataApiAdapter
import com.deskcubby.app.plugin.adapter.FileApiAdapter
import com.deskcubby.app.plugin.adapter.VaultApiAdapter
import com.deskcubby.plugin.api.core.api.AppAPI
import com.deskcubby.plugin.api.core.api.AIAPI
import com.deskcubby.plugin.api.core.api.DeskCubbyDataAPI
import com.deskcubby.plugin.api.core.api.DiaryAPI
import com.deskcubby.plugin.api.core.api.FileAPI
import com.deskcubby.plugin.api.core.api.VaultAPI
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds
    @Singleton
    abstract fun bindDiaryApi(implementation: DiaryApiAdapter): DiaryAPI

    @Binds
    @Singleton
    abstract fun bindVaultApi(implementation: VaultApiAdapter): VaultAPI

    @Binds
    @Singleton
    abstract fun bindDeskCubbyDataApi(implementation: DeskCubbyDataApiAdapter): DeskCubbyDataAPI

    @Binds
    @Singleton
    abstract fun bindFileApi(implementation: FileApiAdapter): FileAPI

    @Binds
    @Singleton
    abstract fun bindAppApi(implementation: AppApiAdapter): AppAPI

    @Binds
    @Singleton
    abstract fun bindAiApi(implementation: AiApiAdapter): AIAPI

    @Binds
    @Singleton
    abstract fun bindAgentModelClient(implementation: OpenAiCompatibleAgentModelClient): AgentModelClient

    @Binds
    @Singleton
    abstract fun bindAgentContextProvider(implementation: DefaultAgentContextProvider): AgentContextProvider

    @Binds
    @Singleton
    abstract fun bindAgentApprovalGateway(implementation: AgentPermissionManager): AgentApprovalGateway

    @Binds
    @Singleton
    abstract fun bindAgentReviewStore(implementation: AgentReviewRepository): AgentReviewStore

    @Binds
    @Singleton
    abstract fun bindAgentToolExecutor(implementation: AgentToolExecutor): AgentToolExecutionGateway

    @Binds
    @Singleton
    abstract fun bindAgentWebService(implementation: DefaultAgentWebService): AgentWebService

    @Binds
    @IntoSet
    abstract fun bindBuiltInAgentTools(contributor: BuiltInAgentToolContributor): AgentToolContributor
}
