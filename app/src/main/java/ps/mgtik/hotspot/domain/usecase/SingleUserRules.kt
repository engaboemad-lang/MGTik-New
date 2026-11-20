package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.NewNormalUser
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OwnerStatus

/**
 * SingleUserRules - قواعد نقية (بلا Android/Firebase) لإنشاء مستخدم فردي من شاشة إدارة المستخدمين (S16).
 *
 * يغطي: التحقق من مدخلات المستخدم العادي، وحسم مرجع المالك الافتراضي «admin» من مالكين موجودين فعلاً
 * (لا يُنشأ مالك ولا وكيل لهذا الغرض، ولا يُخزَّن الاسم النصي وحده).
 */
object SingleUserRules {

    /** الاسم/المعرّف الافتراضي لمالك المستخدم الفردي. */
    const val ADMIN_OWNER_KEY = "admin"

    const val ERR_USERNAME_INVALID = "ERR_USER_CREATE_USERNAME_INVALID"
    const val ERR_PASSWORD_INVALID = "ERR_USER_CREATE_PASSWORD_INVALID"
    const val ERR_PROFILE_REQUIRED = "ERR_USER_CREATE_PROFILE_REQUIRED"
    /** الاسم محجوز لمستخدم مُدار أو مشترك أو أرشيف أو حساب موجود على الراوتر. */
    const val ERR_USERNAME_TAKEN = "ERR_USER_CREATE_USERNAME_TAKEN"
    const val ERR_ADMIN_OWNER_MISSING = "ERR_USER_CREATE_ADMIN_OWNER_MISSING"
    const val ERR_ADMIN_OWNER_AMBIGUOUS = "ERR_USER_CREATE_ADMIN_OWNER_AMBIGUOUS"
    const val ERR_ADMIN_OWNER_INACTIVE = "ERR_USER_CREATE_ADMIN_OWNER_INACTIVE"

    // نفس قاعدة اسم دخول الاشتراكات: مسار واحد لقواعد الأسماء، لا قاعدة ثانية تتعارض معها
    private val USERNAME = Regex("^[A-Za-z0-9._@-]{3,32}$")

    fun isUsernameValid(username: String): Boolean = USERNAME.matches(username)
    fun isPasswordValid(password: String): Boolean = password.length >= 4 && password.none { it.isWhitespace() }

    /** رمز أول خطأ في مدخلات إنشاء مستخدم عادي فردي، أو null. */
    fun validateNew(d: NewNormalUser): String? {
        if (!isUsernameValid(d.username)) return ERR_USERNAME_INVALID
        if (!isPasswordValid(d.password)) return ERR_PASSWORD_INVALID
        if (d.profileId.isBlank() || d.routerProfileName.isBlank()) return ERR_PROFILE_REQUIRED
        return null
    }

    sealed interface AdminOwner {
        data class Found(val owner: OwnerAccount) : AdminOwner
        data class Error(val code: String) : AdminOwner
    }

    /**
     * يحسم مرجع «admin» من المالكين الموجودين: المطابقة بمعرّف الوثيقة أولاً، ثم بالاسم إن كانت مطابقة واحدة فقط.
     * غياب المالك أو غموضه (أكثر من مطابقة) أو كونه غير نشط ⇒ خطأ صريح، لا تخمين ولا إنشاء.
     */
    fun resolveAdminOwner(owners: List<OwnerAccount>): AdminOwner {
        val byId = owners.filter { it.ownerId.equals(ADMIN_OWNER_KEY, ignoreCase = true) }
        val matches = when {
            byId.size == 1 -> byId
            byId.size > 1 -> return AdminOwner.Error(ERR_ADMIN_OWNER_AMBIGUOUS)
            else -> owners.filter { it.name.trim().equals(ADMIN_OWNER_KEY, ignoreCase = true) }
        }
        val owner = when (matches.size) {
            0 -> return AdminOwner.Error(ERR_ADMIN_OWNER_MISSING)
            1 -> matches.single()
            else -> return AdminOwner.Error(ERR_ADMIN_OWNER_AMBIGUOUS)
        }
        return if (owner.status == OwnerStatus.ACTIVE) AdminOwner.Found(owner) else AdminOwner.Error(ERR_ADMIN_OWNER_INACTIVE)
    }
}
