package ai.xrav.xravscan.di

import ai.xrav.xravscan.BuildConfig
import ai.xrav.xravscan.data.remote.UpdateClientFactory
import ai.xrav.xravscan.data.remote.api.BgpViewService
import ai.xrav.xravscan.data.remote.api.BunnyEdgeApi
import android.content.Context
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BGPVIEW_BASE_URL = "https://api.bgpview.io/"
    private const val BUNNY_BASE_URL = "https://api.bunny.net/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun baseOkHttpBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }
        return builder
    }

    /** Plain client used by the live scanner (no DoH — uses the local network). */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()

    /**
     * Update-path client factory. Uses DNS-over-HTTPS via Cloudflare and
     * tries to pin to a non-VPN network so DB updates work even when the
     * user is on a VPN or behind a censoring resolver.
     */
    @Provides
    @Singleton
    fun provideUpdateClientFactory(
        @ApplicationContext context: Context,
    ): UpdateClientFactory = UpdateClientFactory(context, baseOkHttpBuilder())

    @Provides
    @Singleton
    @Named("bgpview")
    fun provideBgpViewRetrofit(
        factory: UpdateClientFactory,
        json: Json,
    ): Retrofit {
        val mediaType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BGPVIEW_BASE_URL)
            .client(factory.dohClient)
            .addConverterFactory(json.asConverterFactory(mediaType))
            .build()
    }

    @Provides
    @Singleton
    @Named("bunny")
    fun provideBunnyRetrofit(
        factory: UpdateClientFactory,
        json: Json,
    ): Retrofit {
        val mediaType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BUNNY_BASE_URL)
            .client(factory.dohClient)
            .addConverterFactory(json.asConverterFactory(mediaType))
            .build()
    }

    @Provides
    @Singleton
    fun provideBgpViewService(@Named("bgpview") retrofit: Retrofit): BgpViewService =
        retrofit.create(BgpViewService::class.java)

    @Provides
    @Singleton
    fun provideBunnyEdgeApi(@Named("bunny") retrofit: Retrofit): BunnyEdgeApi =
        retrofit.create(BunnyEdgeApi::class.java)
}
