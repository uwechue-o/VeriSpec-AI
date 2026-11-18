package ai.verispec.data.runtime.mediapipe.di

import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.port.InferenceBackend
import ai.verispec.data.runtime.api.di.RuntimeKey
import ai.verispec.data.runtime.mediapipe.MediaPipeBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaPipeModule {
    @Binds @IntoMap @RuntimeKey(RuntimeId.MEDIAPIPE)
    abstract fun mediapipe(impl: MediaPipeBackend): InferenceBackend
}
