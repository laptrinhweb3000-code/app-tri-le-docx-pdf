package com.doccast.presenter.parser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.doccast.presenter.model.DocFormat
import com.doccast.presenter.model.LoadedDocument

object DocumentParser {

    fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Tài liệu"
    }

    fun detectFormat(context: Context, uri: Uri, displayName: String): DocFormat {
        val mime = context.contentResolver.getType(uri)
        return DocFormat.fromFileNameAndMime(displayName, mime)
    }

    fun loadOfficeDocument(
        context: Context,
        uri: Uri,
        displayName: String,
        format: DocFormat
    ): LoadedDocument.HtmlPages {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Không thể mở luồng dữ liệu cho tệp: $displayName")

        return stream.use { input ->
            when (format) {
                DocFormat.DOCX -> OoxmlParser.parseDocx(displayName, input)
                DocFormat.DOC -> LegacyOfficeParser.parseDoc(displayName, input)
                else -> throw IllegalArgumentException("Ứng dụng chỉ hỗ trợ định dạng PDF, DOCX và DOC.")
            }
        }
    }
}
