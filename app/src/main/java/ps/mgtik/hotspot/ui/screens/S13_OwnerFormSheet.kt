package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import ps.mgtik.hotspot.ui.viewmodel.OwnerField

/**
 * الشاشة 13: نموذج إنشاء مالك جديد مع التحقق الآمن من المدخلات (§4.18)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S13_OwnerFormSheet(
    isSaving: Boolean,
    validationErrors: Map<String, Int>,
    errorMessageResId: Int?,
    onFieldEdited: (String) -> Unit,
    onMessageShown: () -> Unit,
    onSave: (name: String, phone: String) -> Unit,
    onBack: () -> Unit,
    isEdit: Boolean = false,
    initialName: String = "",
    initialPhone: String = ""
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var phone by remember(initialPhone) { mutableStateOf(initialPhone) }
    var nameError by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(errorMessageResId) {
        errorMessageResId?.let {
            snackbarHostState.showSnackbar(context.getString(it))
            onMessageShown()
        }
    }

    val nameServerError = validationErrors[OwnerField.NAME]
    val phoneServerError = validationErrors[OwnerField.PHONE]

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(if (isEdit) R.string.owner_form_edit_title else R.string.owner_form_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.SpaceXXL),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLG)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = !OwnerInputValidator.isNameValid(it)
                    onFieldEdited(OwnerField.NAME)
                },
                label = { Text(stringResource(R.string.owner_field_name)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                enabled = !isSaving,
                isError = nameError || nameServerError != null,
                supportingText = {
                    if (nameError || nameServerError != null) Text(stringResource(nameServerError ?: R.string.owner_error_name_too_short))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it
                    phoneError = !OwnerInputValidator.isPhoneValid(it)
                    onFieldEdited(OwnerField.PHONE)
                },
                label = { Text(stringResource(R.string.owner_field_phone)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                enabled = !isSaving,
                isError = phoneError || phoneServerError != null,
                supportingText = {
                    if (phoneServerError != null) Text(stringResource(phoneServerError))
                    else if (phoneError) Text(stringResource(R.string.owner_error_phone_invalid))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done)
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (OwnerInputValidator.isNameValid(name) && OwnerInputValidator.isPhoneValid(phone) && !isSaving) {
                        onSave(name.trim(), phone.trim())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                enabled = OwnerInputValidator.isNameValid(name) && OwnerInputValidator.isPhoneValid(phone) && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
