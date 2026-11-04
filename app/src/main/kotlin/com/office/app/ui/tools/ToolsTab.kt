package com.office.app.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.BrandingWatermark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.office.app.R

enum class ToolCategory(val title: Int) {
    ORGANIZE(R.string.tools_cat_organize),
    OPTIMIZE(R.string.tools_cat_optimize),
    TO_PDF(R.string.tools_cat_to_pdf),
    FROM_PDF(R.string.tools_cat_from_pdf),
    EDIT(R.string.tools_cat_edit),
}

/** What a tool asks for before it can run. */
enum class ToolInput { PDF, PDFS, TWO_PDFS, IMAGES, CAMERA, WORD, SHEET, SLIDES, TEXT }

enum class ToolId(val title: Int, val desc: Int, val icon: ImageVector, val category: ToolCategory, val input: ToolInput) {
    MERGE(R.string.tool_merge, R.string.tool_merge_desc, Icons.Outlined.CallMerge, ToolCategory.ORGANIZE, ToolInput.PDFS),
    SPLIT(R.string.tool_split, R.string.tool_split_desc, Icons.Outlined.CallSplit, ToolCategory.ORGANIZE, ToolInput.PDF),
    ORGANIZE(R.string.tool_organize, R.string.tool_organize_desc, Icons.Outlined.ViewModule, ToolCategory.ORGANIZE, ToolInput.PDF),
    ROTATE(R.string.tool_rotate, R.string.tool_rotate_desc, Icons.Outlined.RotateRight, ToolCategory.ORGANIZE, ToolInput.PDF),
    DELETE_PAGES(R.string.tool_delete_pages, R.string.tool_delete_pages_desc, Icons.Outlined.DeleteSweep, ToolCategory.ORGANIZE, ToolInput.PDF),
    COMPRESS(R.string.tool_compress, R.string.tool_compress_desc, Icons.Outlined.Compress, ToolCategory.OPTIMIZE, ToolInput.PDF),
    REPAIR(R.string.tool_repair, R.string.tool_repair_desc, Icons.Outlined.Build, ToolCategory.OPTIMIZE, ToolInput.PDF),
    IMAGES_TO_PDF(R.string.tool_images_to_pdf, R.string.tool_images_to_pdf_desc, Icons.Outlined.Image, ToolCategory.TO_PDF, ToolInput.IMAGES),
    SCAN(R.string.tool_scan, R.string.tool_scan_desc, Icons.Outlined.DocumentScanner, ToolCategory.TO_PDF, ToolInput.CAMERA),
    WORD_TO_PDF(R.string.tool_word_to_pdf, R.string.tool_word_to_pdf_desc, Icons.Outlined.Description, ToolCategory.TO_PDF, ToolInput.WORD),
    EXCEL_TO_PDF(R.string.tool_excel_to_pdf, R.string.tool_excel_to_pdf_desc, Icons.Outlined.TableChart, ToolCategory.TO_PDF, ToolInput.SHEET),
    PPT_TO_PDF(R.string.tool_ppt_to_pdf, R.string.tool_ppt_to_pdf_desc, Icons.Outlined.Slideshow, ToolCategory.TO_PDF, ToolInput.SLIDES),
    TEXT_TO_PDF(R.string.tool_text_to_pdf, R.string.tool_text_to_pdf_desc, Icons.Outlined.TextSnippet, ToolCategory.TO_PDF, ToolInput.TEXT),
    PDF_TO_IMAGES(R.string.tool_pdf_to_images, R.string.tool_pdf_to_images_desc, Icons.Outlined.PhotoLibrary, ToolCategory.FROM_PDF, ToolInput.PDF),
    PDF_TO_WORD(R.string.tool_pdf_to_word, R.string.tool_pdf_to_word_desc, Icons.Outlined.Article, ToolCategory.FROM_PDF, ToolInput.PDF),
    PDF_TO_PPT(R.string.tool_pdf_to_ppt, R.string.tool_pdf_to_ppt_desc, Icons.Outlined.Slideshow, ToolCategory.FROM_PDF, ToolInput.PDF),
    PDF_TO_TEXT(R.string.tool_pdf_to_text, R.string.tool_pdf_to_text_desc, Icons.Outlined.Notes, ToolCategory.FROM_PDF, ToolInput.PDF),
    SIGN(R.string.tool_sign, R.string.tool_sign_desc, Icons.Outlined.Draw, ToolCategory.EDIT, ToolInput.PDF),
    WATERMARK(R.string.tool_watermark, R.string.tool_watermark_desc, Icons.Outlined.BrandingWatermark, ToolCategory.EDIT, ToolInput.PDF),
    PAGE_NUMBERS(R.string.tool_page_numbers, R.string.tool_page_numbers_desc, Icons.Outlined.FormatListNumbered, ToolCategory.EDIT, ToolInput.PDF),
    PROTECT(R.string.tool_protect, R.string.tool_protect_desc, Icons.Outlined.Lock, ToolCategory.EDIT, ToolInput.PDF),
    UNLOCK(R.string.tool_unlock, R.string.tool_unlock_desc, Icons.Outlined.LockOpen, ToolCategory.EDIT, ToolInput.PDF),
    REDACT(R.string.tool_redact, R.string.tool_redact_desc, Icons.Outlined.VisibilityOff, ToolCategory.EDIT, ToolInput.PDF),
    CROP(R.string.tool_crop, R.string.tool_crop_desc, Icons.Outlined.Crop, ToolCategory.EDIT, ToolInput.PDF),
    FILL_FORM(R.string.tool_fill_form, R.string.tool_fill_form_desc, Icons.Outlined.EditNote, ToolCategory.EDIT, ToolInput.PDF),
    FLATTEN(R.string.tool_flatten, R.string.tool_flatten_desc, Icons.Outlined.Layers, ToolCategory.EDIT, ToolInput.PDF),
    METADATA(R.string.tool_metadata, R.string.tool_metadata_desc, Icons.Outlined.Info, ToolCategory.EDIT, ToolInput.PDF),
    EXTRACT_IMAGES(R.string.tool_extract_images, R.string.tool_extract_images_desc, Icons.Outlined.Collections, ToolCategory.EDIT, ToolInput.PDF),
    COMPARE(R.string.tool_compare, R.string.tool_compare_desc, Icons.Outlined.Difference, ToolCategory.EDIT, ToolInput.TWO_PDFS),
    ;

    /** Tools that are done inside the PDF viewer; the Tools tab only picks the file. */
    val viewerTool: String?
        get() = when (this) {
            SIGN -> "sign"
            REDACT -> "redact"
            FILL_FORM -> "form"
            else -> null
        }
}

@Composable
fun ToolsTab(onTool: (ToolId) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize().testTag("tools_grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (cat in ToolCategory.entries) {
            item(key = "h_$cat", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(cat.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(ToolId.entries.filter { it.category == cat }, key = { it.name }) { tool -> ToolCard(tool) { onTool(tool) } }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolId, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().height(132.dp).testTag("tool_${tool.name}")) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(tool.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(tool.desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
