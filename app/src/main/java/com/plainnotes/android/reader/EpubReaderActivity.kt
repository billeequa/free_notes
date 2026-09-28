package com.plainnotes.android.reader

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.plainnotes.android.data.AppSettingsRepository
import com.plainnotes.android.ui.ThemeMode
import com.plainnotes.android.ui.theme.PlainNotesTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReaderColor
import org.readium.r2.navigator.preferences.Theme as ReaderTheme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.toUri
import com.plainnotes.android.ui.theme.plainNotesTopAppBarColors

@OptIn(ExperimentalReadiumApi::class, ExperimentalMaterial3Api::class)
class EpubReaderActivity : FragmentActivity() {
    private val containerId = View.generateViewId()
    private var navigator: EpubNavigatorFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Reopen from the persistent source after process death. A stale Readium fragment
        // cannot restore without its publication/factory, so never restore that fragment.
        super.onCreate(null)
        val uri = intent.getStringExtra("book_uri")?.let(Uri::parse) ?: run { finish(); return }
        val library = BookLibrary(this)
        val settings = AppSettingsRepository(this)
        var publication by mutableStateOf<Publication?>(null)
        var error by mutableStateOf<String?>(null)
        var theme by mutableStateOf(ThemeMode.DARK_1)
        var fontScale by mutableFloatStateOf(1f)
        var controls by mutableStateOf(false)
        var toc by mutableStateOf(false)
        var progress by mutableDoubleStateOf(0.0)

        lifecycleScope.launch { settings.themeMode.collectLatest { theme = ThemeMode.fromStorage(it) } }
        lifecycleScope.launch { settings.fontScale.collectLatest { fontScale = it } }
        lifecycleScope.launch {
            try { publication = library.open(uri) }
            catch (e: Exception) { error = e.message ?: "This EPUB cannot be opened." }
        }

        setContent {
            PlainNotesTheme(theme, fontScale) {
                val colors = MaterialTheme.colorScheme
                SideEffect {
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.background.toArgb()
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = colors.background.luminance() > 0.5f
                        isAppearanceLightNavigationBars = colors.background.luminance() > 0.5f
                    }
                }
                val reading = publication
                LaunchedEffect(reading, theme, fontScale) {
                    navigator?.submitPreferences(preferences(theme, fontScale, colors.background.toArgb(), colors.onBackground.toArgb()))
                }
                BackHandler(enabled = toc) { toc = false }
                Box(Modifier.fillMaxSize().background(colors.background)) {
                    when {
                        error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                            Text(error!!)
                            TextButton(onClick = ::finish) { Text("Back to library") }
                        }
                        reading == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> {
                            AndroidView(factory = { context -> FrameLayout(context).apply { id = containerId } },
                                modifier = Modifier.fillMaxSize(), update = { frame ->
                                    if (navigator == null) {
                                        val initial = runCatching { library.locator(uri)?.let { Locator.fromJSON(JSONObject(it)) } }.getOrNull()
                                        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(reading).createFragmentFactory(
                                            initialLocator = initial,
                                            initialPreferences = preferences(theme, fontScale, colors.background.toArgb(), colors.onBackground.toArgb()),
                                            listener = object : EpubNavigatorFragment.Listener {
                                                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                                                    if (url.isHttp) runCatching {
                                                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
                                                    }
                                                }
                                            },
                                        )
                                        supportFragmentManager.commitNow { add(frame.id, EpubNavigatorFragment::class.java, Bundle(), "epub") }
                                        navigator = supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                                        navigator?.addInputListener(object : InputListener {
                                            override fun onTap(event: TapEvent): Boolean { controls = !controls; return true }
                                        })
                                        lifecycleScope.launch {
                                            navigator?.currentLocator?.collectLatest { locator ->
                                                progress = locator.locations.totalProgression ?: progress
                                                library.savePosition(uri, locator.toJSON().toString(), progress)
                                            }
                                        }
                                    }
                                })
                            if (controls) {
                                TopAppBar(title = { Text(reading.metadata.title ?: "E Reader", maxLines = 1) },
                                    navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") } },
                                    actions = { IconButton(onClick = { toc = true }) { Icon(Icons.Rounded.List, "Table of contents") } },
                                    colors = plainNotesTopAppBarColors(), modifier = Modifier.align(Alignment.TopCenter))
                                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    FilledTonalButton(onClick = { navigator?.goBackward() }) { Text("Previous") }
                                    Text("${(progress * 100).toInt()}%", color = colors.onSurface)
                                    FilledTonalButton(onClick = { navigator?.goForward() }) { Text("Next") }
                                }
                            }
                            if (toc) AlertDialog(onDismissRequest = { toc = false }, title = { Text("Contents") },
                                text = { androidx.compose.foundation.lazy.LazyColumn {
                                    val chapters = chapters(reading.tableOfContents)
                                    items(chapters.size) { index ->
                                        val (link, depth) = chapters[index]
                                        TextButton(onClick = { navigator?.go(link); toc = false; controls = false }) {
                                            Text("  ".repeat(depth) + (link.title ?: "Chapter ${index + 1}"))
                                        }
                                    }
                                } }, confirmButton = { TextButton(onClick = { toc = false }) { Text("Close") } })
                        }
                    }
                }
            }
        }
    }

    private fun preferences(theme: ThemeMode, scale: Float, background: Int, foreground: Int): EpubPreferences =
        EpubPreferences(
            theme = if (theme.name.startsWith("DARK") || theme == ThemeMode.AMOLED) ReaderTheme.DARK else ReaderTheme.LIGHT,
            backgroundColor = ReaderColor(background), textColor = ReaderColor(foreground),
            fontSize = scale.toDouble().coerceIn(0.7, 2.0),
        )

    private fun chapters(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> =
        links.flatMap { link -> listOf(link to depth) + chapters(link.children, depth + 1) }
}
