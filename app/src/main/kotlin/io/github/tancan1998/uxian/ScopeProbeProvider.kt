package io.github.tancan1998.uxian

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * 只读探针：让系统自己解析 resources.arsc 里的 array/xposed_scope，
 * 用 content query 就能看到结果，不需要界面。
 *
 *   content query --uri content://io.github.tancan1998.uxian.scope
 */
class ScopeProbeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val c = MatrixCursor(arrayOf("k", "v"))
        try {
            val arr = context!!.resources.getStringArray(ID)
            c.addRow(arrayOf<Any>("count", arr.size.toString()))
            for (i in arr.indices) {
                c.addRow(arrayOf<Any>("item$i", arr[i]))
            }
        } catch (t: Throwable) {
            Log.e(MtopBridge.TAG, "scope resolve failed: $t")
            c.addRow(arrayOf<Any>("error", t.javaClass.simpleName + ": " + t.message))
        }
        return c
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "io.github.tancan1998.uxian.scope"
        private const val ID = 0x7F010000 // array/xposed_scope
    }
}
