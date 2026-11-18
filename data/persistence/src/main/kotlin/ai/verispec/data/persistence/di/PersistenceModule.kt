package ai.verispec.data.persistence.di

import ai.verispec.core.domain.port.EvidenceRepository
import ai.verispec.data.persistence.RoomEvidenceRepository
import ai.verispec.data.persistence.RoomRunRepository
import ai.verispec.data.persistence.RunRepository
import ai.verispec.data.persistence.db.BundleIndexDao
import ai.verispec.data.persistence.db.IterationDao
import ai.verispec.data.persistence.db.RunDao
import ai.verispec.data.persistence.db.VerispecDatabase
import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PersistenceModule {
    @Binds abstract fun evidenceRepository(impl: RoomEvidenceRepository): EvidenceRepository
    @Binds abstract fun runRepository(impl: RoomRunRepository): RunRepository

    companion object {
        @Provides @Singleton
        fun database(@ApplicationContext context: Context): VerispecDatabase =
            Room.databaseBuilder(context, VerispecDatabase::class.java, VerispecDatabase.NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        @Provides fun runDao(db: VerispecDatabase): RunDao = db.runs()
        @Provides fun iterationDao(db: VerispecDatabase): IterationDao = db.iterations()
        @Provides fun bundleIndexDao(db: VerispecDatabase): BundleIndexDao = db.bundleIndex()
    }
}
