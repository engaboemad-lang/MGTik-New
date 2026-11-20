package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator

/**
 * دالة مساعدة لتنسيق المبالغ المالية مع إظهار الكسور بشكل دقيق لمنع أي فقد مالي.
 */
fun formatMoney(amountMinor: Long): String = ps.mgtik.hotspot.core.util.formatMinor(amountMinor)

/** الخطوة الأولى للاسترجاع: إدخال المبلغ (≤ الرصيد المتاح) أو اختيار كامل الرصيد. */
@Composable
fun RefundFromCreditDialog(availableMinor: Long, onDismiss: () -> Unit, onContinue: (Long) -> Unit) {
    var amountStr by remember { mutableStateOf("") }
    val parsed = OwnerInputValidator.parseAmountMinor(amountStr)
    val exceeds = parsed != null && parsed > availableMinor
    val isValid = parsed != null && !exceeds
    val currencySymbol = LocalCurrencySymbol.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner_dialog_refund_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Text(
                    stringResource(R.string.owner_dialog_refund_available, formatMoney(availableMinor), currencySymbol),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { new ->
                        if (new.all { it.isDigit() || it == '.' } && new.count { it == '.' } <= 1) amountStr = new
                    },
                    label = { Text(stringResource(R.string.owner_dialog_amount_label)) },
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = amountStr.isNotBlank() && !isValid,
                    supportingText = {
                        when {
                            exceeds -> Text(stringResource(R.string.owner_error_refund_exceeds))
                            amountStr.isNotBlank() && parsed == null -> Text(stringResource(R.string.owner_error_amount_invalid))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
                )
                TextButton(onClick = { amountStr = OwnerInputValidator.minorToInput(availableMinor) }) {
                    Text(stringResource(R.string.owner_dialog_refund_all))
                }
            }
        },
        confirmButton = {
            Button(onClick = { parsed?.let(onContinue) }, enabled = isValid) { Text(stringResource(R.string.owner_btn_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
fun RecordAdvanceDialog(onDismiss: () -> Unit, onConfirm: (Long, String?) -> Unit) {
    var amountStr by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val parsedMinor = OwnerInputValidator.parseAmountMinor(amountStr)
    val isAmountValid = parsedMinor != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner_btn_deposit), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                OutlinedTextField(
                    value = amountStr, 
                    onValueChange = { amountStr = it }, 
                    label = { Text(stringResource(R.string.owner_dialog_amount_label)) },
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    modifier = Modifier.fillMaxWidth(),
                    isError = amountStr.isNotBlank() && !isAmountValid,
                    supportingText = {
                        if (amountStr.isNotBlank() && !isAmountValid) Text(stringResource(R.string.owner_error_amount_invalid))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = notes, 
                    onValueChange = { notes = it }, 
                    label = { Text(stringResource(R.string.owner_field_notes)) },
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { parsedMinor?.let { onConfirm(it, notes.ifBlank { null }) } },
                enabled = isAmountValid
            ) { Text(stringResource(R.string.owner_btn_confirm_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } }
    )
}

@Composable
fun RecordOpeningBalanceDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var amountStr by remember { mutableStateOf("") }
    val parsedMinor = OwnerInputValidator.parseAmountMinor(amountStr)
    val isAmountValid = parsedMinor != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner_btn_opening_balance), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                OutlinedTextField(
                    value = amountStr, 
                    onValueChange = { amountStr = it }, 
                    label = { Text(stringResource(R.string.owner_dialog_amount_label)) },
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    modifier = Modifier.fillMaxWidth(),
                    isError = amountStr.isNotBlank() && !isAmountValid,
                    supportingText = {
                        if (amountStr.isNotBlank() && !isAmountValid) Text(stringResource(R.string.owner_error_amount_invalid))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { parsedMinor?.let(onConfirm) },
                enabled = isAmountValid
            ) { Text(stringResource(R.string.owner_btn_confirm_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } }
    )
}
