package ai.verispec.data.runtime.api.di

import ai.verispec.core.domain.model.RuntimeId
import dagger.MapKey

/** Multibinding key so every adapter registers itself into Map<RuntimeId, InferenceBackend>. */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
annotation class RuntimeKey(val value: RuntimeId)
