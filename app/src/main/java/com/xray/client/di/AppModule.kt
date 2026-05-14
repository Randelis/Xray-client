package com.xray.client.di

import com.xray.client.domain.repository.NodeRepository
import com.xray.client.data.NodeRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindNodeRepository(impl: NodeRepositoryImpl): NodeRepository
}
