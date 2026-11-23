package com.office.app

import android.app.Application
import com.office.app.data.FileRepository
import com.office.app.data.RecentFilesStore
import com.office.app.data.SettingsStore
import kotlinx.coroutines.launch
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class OfficeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        com.office.app.ui.pdf.PdfText.bundledFont = { bold ->
            resources.openRawResource(if (bold) R.font.figtree_bold else R.font.figtree_regular)
        }
        container = AppContainer(this)
    }
}

/** Hand-rolled dependency container; the app is small enough not to need a DI framework. */
class AppContainer(app: Application) {
    val appContext: android.content.Context = app
    val files = FileRepository(app)
    val recents = RecentFilesStore(app)
    val settings = SettingsStore(app)
    val drafts = com.office.app.data.DraftStore(app)
    val folders = com.office.app.data.FolderStore(app)
    val thumbnails = com.office.app.data.ThumbnailStore(app)
    val tabs = com.office.app.data.OpenTabs(app)

    /** Outlives screens, so drafts can still be written while an editor is closing. */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    init {
        // One file at a time unless the user keeps several open.
        appScope.launch { settings.settings.collect { tabs.singleFile = !it.keepFilesOpen } }
    }
}
