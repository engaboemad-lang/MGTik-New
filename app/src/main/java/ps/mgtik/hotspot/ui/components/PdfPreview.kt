package ps.mgtik.hotspot.ui.components

import android.graphics.Bitmap
import ps.mgtik.hotspot.ui.theme.Dimens
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ps.mgtik.hotspot.R
import java.io.File

/** معاينة PDF داخل التطبيق (PdfRenderer) مع إجراءات الحفظ والمشاركة والطباعة. تُعرض صفحات الملف الفعلي نفسه الذي سيُطبع. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewDialog(
    file: File,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onPrint: () -> Unit
) {
    val pages by produceState<Result<List<Bitmap>>?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { runCatching { renderPages(file) } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.inv_preview_title), fontWeight = FontWeight.Black) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                        }
                    )
                },
                bottomBar = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceMD),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
                    ) {
                        OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.inv_action_save_pdf))
                        }
                        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.inv_action_share))
                        }
                        Button(onClick = onPrint, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Print, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.inv_action_print))
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    val result = pages
                    when {
                        result == null -> CircularProgressIndicator()
                        result.isFailure || result.getOrNull().isNullOrEmpty() ->
                            Text(stringResource(R.string.inv_preview_failed), color = MaterialTheme.colorScheme.error)
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
                        ) {
                            itemsIndexed(result.getOrThrow()) { _, bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth().background(Color.White)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun renderPages(file: File): List<Bitmap> {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            val targetWidth = 1100
            return (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { page ->
                    val h = (targetWidth.toFloat() * page.height / page.width).toInt()
                    Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(AColor.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}
