package ps.mgtik.hotspot.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ps.mgtik.hotspot.data.local.db.MgtikDatabase
import ps.mgtik.hotspot.data.local.dao.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MgtikDatabase {
        return MgtikDatabase.getInstance(context)
    }

    @Provides
    fun provideProfileDao(db: MgtikDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideVoucherBatchDao(db: MgtikDatabase): VoucherBatchDao = db.voucherBatchDao()

    @Provides
    fun provideInvoiceDao(db: MgtikDatabase): InvoiceDao = db.invoiceDao()

    @Provides
    fun provideSubscriptionDao(db: MgtikDatabase): SubscriptionDao = db.subscriptionDao()

    @Provides
    fun provideLocalUserDao(db: MgtikDatabase): LocalUserDao = db.localUserDao()

    @Provides
    fun provideExecutionDao(db: MgtikDatabase): ExecutionDao = db.executionDao()

    @Provides
    fun provideOfflineCommandDao(db: MgtikDatabase): OfflineCommandDao = db.offlineCommandDao()

    @Provides
    fun provideTemplateDao(db: MgtikDatabase): TemplateDao = db.templateDao()

    @Provides
    fun provideAuditLogDao(db: MgtikDatabase): AuditLogDao = db.auditLogDao()

    @Provides
    fun provideGenerationDao(db: MgtikDatabase): GenerationDao = db.generationDao()

    @Provides
    fun provideDiscoveryDao(db: MgtikDatabase): DiscoveryDao = db.discoveryDao()

    @Provides
    fun provideArchivedUserDao(db: MgtikDatabase): ArchivedUserDao = db.archivedUserDao()
}
