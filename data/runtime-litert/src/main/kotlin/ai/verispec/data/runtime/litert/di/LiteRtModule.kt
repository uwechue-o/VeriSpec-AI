package ai.verispec.data.runtime.litert.di

import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.port.InferenceBackend
import ai.verispec.data.runtime.api.di.RuntimeKey
import ai.verispec.data.runtime.litert.LiteRtBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class LiteRtModule {
    @Binds @IntoMap @RuntimeKey(RuntimeId.LITERT)
    abstract fun litert(impl: LiteRtBackend): InferenceBackend
}
