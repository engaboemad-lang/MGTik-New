package ps.mgtik.hotspot.data.offline

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * حمولات أوامر الطابور (§5.3): تُكتب من المستودعات عند skipQueue=false وتُقرأ من OfflineCommandDispatcher،
 * فهي مصدر واحد للصيغة على الجانبين (لا نصوص حرة تتفرق بين الكاتب والمنفّذ).
 *
 * التوافق مع أوامر قديمة محفوظة: أسماء الحقول مطابقة لما كانت تكتبه المستودعات سابقاً (ownerId, amountMinor,
 * recordUuid, reason, profileId, fields ...) وغير المعروف يُتجاهل. أوامر ENABLE/DISABLE_USER وARCHIVE_HISTORY_BATCH
 * وRETRY_EXECUTION حمولتها نص خام (المعرّف نفسه) كما كانت.
 */

@Serializable data class ProfileUpdatePayload(val profileId: String, val fields: JsonObject)
@Serializable data class ProfileDeletePayload(val profileId: String, val alsoDeleteFromRouter: Boolean = true, val force: Boolean = false)
@Serializable data class ProfileRenamePayload(val profileId: String, val newProfileName: String, val repointUsers: Boolean = true)

@Serializable data class UserArchivePayload(val recordUuid: String, val reason: String)

@Serializable data class OwnerBalancePayload(val ownerId: String, val amountMinor: Long)
@Serializable data class OwnerAdvancePayload(val ownerId: String, val amountMinor: Long, val notes: String? = null)

@Serializable data class RecordPaymentPayload(
    val invoiceId: String, val amountMinor: Long, val method: String,
    val reference: String? = null, val notes: String? = null
)
@Serializable data class VoidPaymentPayload(val paymentId: String, val reason: String)
@Serializable data class RefundPaymentPayload(val paymentId: String, val refundAmountMinor: Long)
@Serializable data class CancelInvoicePayload(val invoiceId: String, val force: Boolean = false, val resolution: String? = null)
@Serializable data class InvoiceDiscountPayload(val invoiceId: String, val discountType: String, val discountValue: Long)
@Serializable data class InvoiceUpdatePayload(val invoiceId: String, val fields: JsonObject)
@Serializable data class InvoiceIdPayload(val invoiceId: String)
@Serializable data class OwnerCreditPayload(val invoiceId: String, val amountMinor: Long)
@Serializable data class UnapplyCreditPayload(val invoiceId: String, val ledgerEntryId: String)
@Serializable data class PaymentUpdatePayload(val paymentId: String, val fields: JsonObject)

object CommandCodec {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(payload: String): T = json.decodeFromString(payload)

    /**
     * Map<String, Any?> ← JSON مع حفظ أنواع الأرقام (Gson كان يحوّل كل رقم إلى Double فيفسد حقول Long في Firestore).
     * يرفض القيم غير المدعومة (قوائم/خرائط/كائنات) بدل تخزين شيء لا يمكن إعادته كما كان.
     */
    fun fieldsToJson(fields: Map<String, Any?>): JsonObject = JsonObject(
        fields.mapValues { (key, value) ->
            when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Int, is Long, is Short, is Byte -> JsonPrimitive((value as Number).toLong())
                is Float, is Double -> JsonPrimitive((value as Number).toDouble())
                is JsonElement -> value
                else -> throw IllegalArgumentException("Unsupported queued field type for '$key': ${value::class.simpleName}")
            }
        }
    )

    fun jsonToFields(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, element) ->
        when (element) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.booleanOrNull
                element.longOrNull != null -> element.longOrNull
                element.doubleOrNull != null -> element.doubleOrNull
                else -> element.content
            }
            else -> element.toString()
        }
    }
}
