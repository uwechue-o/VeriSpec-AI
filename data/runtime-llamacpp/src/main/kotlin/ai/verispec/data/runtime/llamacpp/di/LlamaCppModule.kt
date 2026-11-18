package ai.verispec.data.runtime.llamacpp.di

import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.port.InferenceBackend
import ai.verispec.data.runtime.api.di.RuntimeKey
import ai.verispec.data.runtime.llamacpp.LlamaCppBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class LlamaCppModule {
    @Binds @IntoMap @RuntimeKey(RuntimeId.LLAMA_CPP)
    abstract fun llamaCpp(impl: LlamaCppBackend): InferenceBackend
}
