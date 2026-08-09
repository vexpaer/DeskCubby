package com.deskcubby.app.data.repository

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultDiaryChildTreeUriTest {
    @Test
    fun childRootKeepsTheSelectedParentTreeGrant() {
        val parent = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Documents")
        val child = DocumentsContract.buildDocumentUriUsingTree(
            parent,
            "primary:Documents/deskcubby/diary",
        )

        val validated = validatedInheritedChildTreeUri(parent, child)

        assertEquals(parent.authority, validated.authority)
        assertEquals(
            DocumentsContract.getTreeDocumentId(parent),
            DocumentsContract.getTreeDocumentId(validated),
        )
        assertEquals(
            "primary:Documents/deskcubby/diary",
            DocumentsContract.getDocumentId(validated),
        )
    }

    @Test
    fun standaloneChildTreeCannotPretendToInheritTheParentGrant() {
        val parent = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Documents")
        val standaloneChild = DocumentsContract.buildTreeDocumentUri(
            AUTHORITY,
            "primary:Documents/deskcubby/diary",
        )

        assertThrows(IllegalArgumentException::class.java) {
            validatedInheritedChildTreeUri(parent, standaloneChild)
        }
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
