package ps.mgtik.hotspot.ui.dialogs

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import ps.mgtik.hotspot.ui.components.indicators.MgtikButtonLoadingContent
import ps.mgtik.hotspot.ui.screens.formatMoney
import java.time.LocalDate

/**
 * إنشاء اشتراك: يختار المستخدم المشترك والباقة من البيانات الفعلية؛ معرّف الباقة (profileId) هو
 * معرّف سجل الباقة الحقيقي وليس نصاً فارغاً. الخصم يُتحقَّق منه (موجب، خانتان عشريتان، أقل من السعر).
 */
@Composable
fun CreateSubscriptionDialog(
    users: List<LocalUserRecord>,
    profiles: List<Profile>,
    owners: List<OwnerAccount>,
    onDismiss: () -> Unit,
    onSubmit: (Subscription) -> Unit
) {
    var selectedUser by remember(users) { mutableStateOf(users.firstOrNull()) }
    var selectedProfile by remember(profiles) { mutableStateOf(profiles.firstOrNull()) }
    var discountText by remember { mutableStateOf("") }
    val currency = LocalCurrencySymbol.current

    // لا خصم (فارغ) = 0 صالح؛ غير ذلك يجب أن يُحلَّل ويكون أقل من السعر
    val discountMinor: Long? = if (discountText.isBlank()) 0L else OwnerInputValidator.parseAmountMinor(discountText)
    val price = selectedProfile?.priceMinor ?: 0L
    val discountOk = discountText.isBlank() || (discountMinor != null && discountMinor < price)
    val canSubmit = selectedUser != null && selectedProfile != null && discountOk

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Dimens.SpaceXL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Text(stringResource(R.string.sub_dialog_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

                Text(stringResource(R.string.sub_customer_label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (users.isEmpty()) {
                    Text(stringResource(R.string.sub_no_users), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    PickerList(items = users, key = { it.recordUuid }, label = { it.routerUsername }, isSelected = { it.recordUuid == selectedUser?.recordUuid }, onPick = { selectedUser = it })
                }

                Text(stringResource(R.string.sub_profile_label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (profiles.isEmpty()) {
                    Text(stringResource(R.string.sub_no_profiles), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    PickerList(
                        items = profiles,
                        key = { it.id },
                        label = { "${it.displayNameAr.ifBlank { it.linkedProfileName }} — ${formatMoney(it.priceMinor)} $currency" },
                        isSelected = { it.id == selectedProfile?.id },
                        onPick = { selectedProfile = it }
                    )
                }

                OutlinedTextField(
                    value = discountText,
                    onValueChange = { discountText = it },
                    label = { Text(stringResource(R.string.sub_discount_label)) },
                    isError = !discountOk,
                    supportingText = {
                        if (!discountOk) {
                            Text(stringResource(R.string.sub_discount_error))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                    Button(
                        enabled = canSubmit,
                        onClick = {
                            val user = selectedUser ?: return@Button
                            val profile = selectedProfile ?: return@Button
                            val discount = discountMinor ?: 0L
                            val today = LocalDate.now()
                            onSubmit(
                                Subscription(
                                    id = java.util.UUID.randomUUID().toString(),
                                    customerUuid = user.recordUuid,
                                    customerUsername = user.routerUsername,
                                    ownerId = user.ownerId,
                                    ownerName = owners.firstOrNull { it.ownerId == user.ownerId }?.name.orEmpty(),
                                    profileId = profile.id,
                                    profileName = profile.displayNameAr.ifBlank { profile.linkedProfileName },
                                    profileClassificationSnapshot = profile.classificationKey.orEmpty(),
                                    startDate = today.toString(),
                                    nextRenewal = today.plusMonths(1).toString(),
                                    billingCycle = "monthly",
                                    priceMinor = profile.priceMinor,
                                    discountMinor = discount,
                                    finalPriceMinor = (profile.priceMinor - discount).coerceAtLeast(0L)
                                )
                            )
                        },
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.sub_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> PickerList(
    items: List<T>,
    key: (T) -> Any,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
        items(items, key = key) { item ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onPick(item) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = isSelected(item), onClick = { onPick(item) })
                Text(label(item), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ConfirmationSubscriptionDialog(
    subscription: Subscription,
    isLoading: Boolean,
    @StringRes errorMessageResId: Int?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val currency = LocalCurrencySymbol.current
    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Dimens.SpaceXL), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                    Text(stringResource(R.string.sub_confirm_title), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                }

                HorizontalDivider()

                Text(stringResource(R.string.sub_customer_fmt, subscription.customerUsername), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.sub_profile_fmt, subscription.profileName), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.sub_dates_fmt, subscription.startDate, subscription.nextRenewal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                ) {
                    Column(modifier = Modifier.padding(Dimens.SpaceMD)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.sub_price_official), style = MaterialTheme.typography.bodySmall)
                            Text("${formatMoney(subscription.priceMinor)} $currency", fontWeight = FontWeight.Bold)
                        }
                        if (subscription.discountMinor > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.sub_discount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                Text("- ${formatMoney(subscription.discountMinor)} $currency", color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.sub_net_total), fontWeight = FontWeight.Black)
                            Text("${formatMoney(subscription.finalPriceMinor)} $currency", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }

                errorMessageResId?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss, enabled = !isLoading) { Text(stringResource(R.string.sub_review)) }
                    Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                    Button(
                        onClick = onConfirm,
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        if (isLoading) {
                            MgtikButtonLoadingContent(text = stringResource(R.string.sub_confirming), indicatorColor = Color.White)
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.sub_confirm_now))
                        }
                    }
                }
            }
        }
    }
}
