package ai.verispec.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "verispec_settings")

data class Settings(
    val defaultIterations: Int = 30,
    val defaultWarmup: Int = 3,
    val defaultThreads: Int = 4,
    /** Logit projections are opt-in: they add bytes and are only needed for divergence math. */
    val includeLogitProjection: Boolean = true,
    /** SPKI SHA-256 hex of trusted attestation roots. Empty = self-consistency check only. */
    val trustedRootSpki: Set<String> = emptySet(),
)

/** DataStore-backed operator settings. Never part of the evidence. */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    val settings: Flow<Settings> = context.settingsDataStore.data.map { p ->
        Settings(
            defaultIterations = p[KEY_ITERATIONS] ?: Settings().defaultIterations,
            defaultWarmup = p[KEY_WARMUP] ?: Settings().defaultWarmup,
            defaultThreads = p[KEY_THREADS] ?: Settings().defaultThreads,
            includeLogitProjection = p[KEY_PROJECTION] ?: Settings().includeLogitProjection,
            trustedRootSpki = p[KEY_ROOTS] ?: emptySet(),
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.settingsDataStore.edit { p ->
            val current = Settings(
                defaultIterations = p[KEY_ITERATIONS] ?: Settings().defaultIterations,
                defaultWarmup = p[KEY_WARMUP] ?: Settings().defaultWarmup,
                defaultThreads = p[KEY_THREADS] ?: Settings().defaultThreads,
                includeLogitProjection = p[KEY_PROJECTION] ?: Settings().includeLogitProjection,
                trustedRootSpki = p[KEY_ROOTS] ?: emptySet(),
            )
            val next = transform(current)
            p[KEY_ITERATIONS] = next.defaultIterations
            p[KEY_WARMUP] = next.defaultWarmup
            p[KEY_THREADS] = next.defaultThreads
            p[KEY_PROJECTION] = next.includeLogitProjection
            p[KEY_ROOTS] = next.trustedRootSpki
        }
    }

    private companion object {
        val KEY_ITERATIONS = intPreferencesKey("default_iterations")
        val KEY_WARMUP = intPreferencesKey("default_warmup")
        val KEY_THREADS = intPreferencesKey("default_threads")
        val KEY_PROJECTION = booleanPreferencesKey("include_logit_projection")
        val KEY_ROOTS = stringSetPreferencesKey("trusted_root_spki")
    }
}
