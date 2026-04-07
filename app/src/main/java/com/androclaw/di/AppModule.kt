package com.androclaw.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.androclaw.api.ClaudeApiService
import com.androclaw.api.ClaudeResponseConverterFactory
import com.androclaw.api.MessageAdapter
import com.androclaw.db.AndroClawDatabase
import com.androclaw.db.ConversationDao
import com.androclaw.db.MemoryDao
import com.androclaw.db.MessageDao
import com.androclaw.db.NoteDao
import com.androclaw.db.ScheduleDao
import com.androclaw.db.SkillDao
import com.androclaw.utils.Constants
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(MessageAdapter())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(Constants.CLAUDE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(ClaudeResponseConverterFactory()) // Must be before Moshi
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideClaudeApiService(retrofit: Retrofit): ClaudeApiService =
        retrofit.create(ClaudeApiService::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AndroClawDatabase =
        AndroClawDatabase.create(context)

    @Provides
    @Singleton
    fun provideMessageDao(database: AndroClawDatabase): MessageDao =
        database.messageDao()

    @Provides
    @Singleton
    fun provideConversationDao(database: AndroClawDatabase): ConversationDao =
        database.conversationDao()

    @Provides
    @Singleton
    fun provideMemoryDao(database: AndroClawDatabase): MemoryDao =
        database.memoryDao()

    @Provides
    @Singleton
    fun provideNoteDao(database: AndroClawDatabase): NoteDao =
        database.noteDao()

    @Provides
    @Singleton
    fun provideSkillDao(database: AndroClawDatabase): SkillDao =
        database.skillDao()

    @Provides
    @Singleton
    fun provideScheduleDao(database: AndroClawDatabase): ScheduleDao =
        database.scheduleDao()

    @Provides
    @Singleton
    @Named("encrypted")
    fun provideEncryptedPrefs(@ApplicationContext context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            "androclaw_secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Provides
    @Singleton
    @Named("regular")
    fun provideSharedPrefs(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
