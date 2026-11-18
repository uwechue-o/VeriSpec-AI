package ai.verispec.data.runtime.executorch.di

import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.port.InferenceBackend
import ai.verispec.data.runtime.api.di.RuntimeKey
import ai.verispec.data.runtime.executorch.ExecuTorchBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class ExecuTorchModule {
    @Binds @IntoMap @RuntimeKey(RuntimeId.EXECUTORCH)
    abstract fun executorch(impl: ExecuTorchBackend): InferenceBackend
}
