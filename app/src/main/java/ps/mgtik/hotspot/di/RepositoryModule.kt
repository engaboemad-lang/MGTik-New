package ps.mgtik.hotspot.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ps.mgtik.hotspot.data.generation.GenerationSchedulerImpl
import ps.mgtik.hotspot.data.router.RouterStateManager
import ps.mgtik.hotspot.data.repository.NotificationPublisherImpl
import ps.mgtik.hotspot.domain.repository.INotificationPublisher
import ps.mgtik.hotspot.data.repository.*
import ps.mgtik.hotspot.domain.repository.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionHistoryRepository(impl: SessionHistoryRepositoryImpl): ISessionHistoryRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): IAuthRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): IUserRepository

    @Binds
    @Singleton
    abstract fun bindAuditRepository(impl: AuditRepositoryImpl): IAuditRepository

    @Binds
    @Singleton
    abstract fun bindMgtikRepository(impl: MgtikRepositoryImpl): IMgtikRepository

    @Binds
    @Singleton
    abstract fun bindOrderRepository(impl: OrderRepositoryImpl): IOrderRepository

    @Binds
    @Singleton
    abstract fun bindOwnerRepository(impl: OwnerRepositoryImpl): IOwnerRepository

    @Binds
    @Singleton
    abstract fun bindOfflineScheduler(impl: OfflineSchedulerImpl): IOfflineScheduler

    @Binds
    @Singleton
    abstract fun bindGenerationScheduler(impl: GenerationSchedulerImpl): IGenerationScheduler

    @Binds
    @Singleton
    abstract fun bindRouterRepository(impl: RouterRepositoryImpl): IRouterRepository

    @Binds
    @Singleton
    abstract fun bindSessionManager(impl: ps.mgtik.hotspot.data.session.SessionManagerImpl): ISessionManager

    @Binds
    @Singleton
    abstract fun bindRouterStateManager(impl: RouterStateManager): IRouterStateManager

    @Binds
    @Singleton
    abstract fun bindBillingRepository(impl: BillingRepositoryImpl): IBillingRepository

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): IHistoryRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): IProfileRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): ISettingsRepository

    @Binds
    @Singleton
    abstract fun bindTemplateRepository(impl: TemplateRepositoryImpl): ITemplateRepository

    @Binds
    @Singleton
    abstract fun bindGenerationRepository(impl: GenerationRepositoryImpl): IGenerationRepository

    @Binds
    @Singleton
    abstract fun bindNotificationRepository(impl: NotificationRepositoryImpl): INotificationRepository

    @Binds
    @Singleton
    abstract fun bindNotificationPublisher(impl: NotificationPublisherImpl): INotificationPublisher

    @Binds
    @Singleton
    abstract fun bindSubscriptionRepository(impl: SubscriptionRepositoryImpl): ISubscriptionRepository

    @Binds
    @Singleton
    abstract fun bindActiveSessionRepository(impl: ActiveSessionRepositoryImpl): IActiveSessionRepository

    @Binds
    @Singleton
    abstract fun bindOfflineCommandRepository(impl: OfflineCommandRepositoryImpl): IOfflineCommandRepository
}
