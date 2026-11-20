package ps.mgtik.hotspot.ui.smoke

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class SmokeViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) : ViewModel() {

    private val _log = MutableStateFlow("لم يبدأ الاختبار")
    val log: StateFlow<String> = _log.asStateFlow()

    fun run(email: String, password: String) {
        viewModelScope.launch {
            try {
                _log.value = "جاري تسجيل الدخول..."
                auth.signInWithEmailAndPassword(email, password).await()
                _log.value = "الدخول نجح (uid: ${auth.currentUser?.uid}). جاري الكتابة في Firestore..."
                val ref = db.collection("smoke_test").document("ping")
                ref.set(mapOf("ok" to true, "at" to System.currentTimeMillis())).await()
                val snap = ref.get().await()
                _log.value = "نجح كل شيء: Auth + Firestore (ok=${snap.getBoolean("ok")})"
            } catch (e: Exception) {
                _log.value = "فشل: ${e::class.simpleName}: ${e.message}"
            }
        }
    }
}