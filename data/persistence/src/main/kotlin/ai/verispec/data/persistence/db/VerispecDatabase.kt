package ai.verispec.data.persistence.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RunEntity::class, IterationEntity::class, BundleIndexEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VerispecDatabase : RoomDatabase() {
    abstract fun runs(): RunDao
    abstract fun iterations(): IterationDao
    abstract fun bundleIndex(): BundleIndexDao

    companion object {
        const val NAME = "verispec.db"
    }
}
