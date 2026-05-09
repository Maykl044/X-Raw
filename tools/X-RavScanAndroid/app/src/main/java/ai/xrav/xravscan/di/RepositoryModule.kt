package ai.xrav.xravscan.di

import ai.xrav.xravscan.data.repository.ProviderRepositoryImpl
import ai.xrav.xravscan.domain.repository.ProviderRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindProviderRepository(impl: ProviderRepositoryImpl): ProviderRepository
}
