package com.goga.carlauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Minimal read-only provider that shares DVR / USB video and music files with players (no AndroidX needed). */
class FilesProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val f = File(uri.path ?: throw FileNotFoundException())
        if (!(Dvr.isVideo(f.name) || Media.isMedia(f.name)) || !f.isFile) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String = (uri.path ?: "").let { if (Media.isMedia(it)) Media.mime(it) else Dvr.mime(it) }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        fun uriFor(f: File): Uri = Uri.parse("content://com.goga.carlauncher.files" + Uri.encode(f.absolutePath, "/"))
    }
}
