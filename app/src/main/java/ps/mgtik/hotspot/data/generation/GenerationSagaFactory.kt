package ps.mgtik.hotspot.data.generation

import ps.mgtik.hotspot.domain.generation.GenerationSaga
import javax.inject.Inject
import javax.inject.Singleton

/** يبني الساغا بالمنافذ الحقيقية (Room + الراوتر + Firestore). */
@Singleton
class GenerationSagaFactory @Inject constructor(
    private val store: RoomRunStore,
    private val router: RouterGateway,
    private val cloud: CloudGateway
) {
    fun create(): GenerationSaga = GenerationSaga(store, router, cloud)
}
