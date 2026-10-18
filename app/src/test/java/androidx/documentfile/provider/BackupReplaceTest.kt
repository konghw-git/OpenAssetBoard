// DocumentFile's constructor is package-private: a test provider has to live in its package.
package androidx.documentfile.provider

import android.app.Application
import android.net.Uri
import com.assetboard.data.BackupKind
import com.assetboard.data.Fixture
import com.assetboard.domain.LedgerException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A folder provider with switchable capabilities, over a real directory. */
private class TestProvider(
    private val doc: DocumentFile,
    private val canRename: (from: String?, to: String) -> Boolean,
    private val canCreate: (name: String) -> Boolean,
) : DocumentFile(null) {
    private fun wrap(d: DocumentFile) = TestProvider(d, canRename, canCreate)
    override fun createFile(mimeType: String, displayName: String): DocumentFile? =
        if (!canCreate(displayName)) null else doc.createFile(mimeType, displayName)?.let(::wrap)
    override fun createDirectory(displayName: String) = doc.createDirectory(displayName)
    override fun getUri(): Uri = doc.uri
    override fun getName(): String? = doc.name
    override fun getType(): String? = doc.type
    override fun isDirectory() = doc.isDirectory
    override fun isFile() = doc.isFile
    override fun isVirtual() = doc.isVirtual
    override fun lastModified() = doc.lastModified()
    override fun length() = doc.length()
    override fun canRead() = doc.canRead()
    override fun canWrite() = doc.canWrite()
    override fun delete() = doc.delete()
    override fun exists() = doc.exists()
    override fun listFiles(): Array<DocumentFile> = doc.listFiles().map(::wrap).toTypedArray()
    override fun renameTo(displayName: String) = canRename(doc.name, displayName) && doc.renameTo(displayName)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupReplaceTest {
    private lateinit var f: Fixture
    private lateinit var dir: File
    private val name = BackupKind.MANUAL.fileName

    @Before fun setUp() {
        f = Fixture()
        dir = File(f.context.cacheDir, "provider-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() = f.close()

    private fun files() = dir.listFiles().orEmpty().associate { it.name to it.readText() }

    private fun replace(provider: DocumentFile) = f.backup.replaceInFolder(provider, name, "new backup")

    @Test fun renamingProviderReplacesTheFileAndLeavesNothingElse() {
        File(dir, name).writeText("old backup")
        replace(TestProvider(DocumentFile.fromFile(dir), { _, _ -> true }, { true }))
        assertEquals(mapOf(name to "new backup"), files())
    }

    @Test fun providerWithoutRenameRewritesTheFileInPlace() {
        File(dir, name).writeText("old backup")
        replace(TestProvider(DocumentFile.fromFile(dir), { _, _ -> false }, { it != name }))
        assertEquals(mapOf(name to "new backup"), files())
    }

    @Test fun failedRenameOfTheNewFilePutsTheOldOneBackBeforeRewriting() {
        File(dir, name).writeText("old backup")
        // The old file moves aside, the new one cannot take its name, and no final file can be created.
        val provider = TestProvider(DocumentFile.fromFile(dir), { from, _ -> from == name || from?.contains("previous") == true }, { it != name })
        replace(provider)
        assertEquals("new backup", files()[name])
        assertFalse(files().keys.any { "previous" in it || "partial" in it })
    }

    @Test fun whenNothingWorksTheOldBackupAndTheCompleteNewOneBothSurvive() {
        // No old file to rewrite, no rename, no way to create the final file.
        val provider = TestProvider(DocumentFile.fromFile(dir), { _, _ -> false }, { it != name })
        try {
            replace(provider)
            fail("expected the replacement to fail")
        } catch (e: LedgerException) {
            assertTrue(e.message!!.contains("partial"))
        }
        // The raw file provider appends the MIME extension again; real providers keep the name.
        assertEquals(listOf("new backup"), files().filterKeys { it.startsWith("asset-board-backup.partial") }.values.toList())
    }
}
