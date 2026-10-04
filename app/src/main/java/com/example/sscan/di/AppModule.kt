package com.example.sscan.di

import com.example.sscan.data.repository.ScanRepository
import com.example.sscan.data.repository.ScanRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 抽象模块：用于 interface 到实现类的绑定（@Binds）。
 * 抽象方法默认是 Hilt 的 binding 方法。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindScanRepository(
        repositoryImpl: ScanRepositoryImpl
    ): ScanRepository
}
