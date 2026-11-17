package com.plainnotes.android.reader

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import org.readium.r2.shared.publication.services.locateProgression
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
        enableEdgeToEdge()
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
        var scrubProgress by mutableFloatStateOf(0f)
        var scrubbing by mutableStateOf(false)
        var controlInteraction by mutableIntStateOf(0)
        var showTapHints by mutableStateOf(false)
        var seekError by mutableStateOf<String?>(null)

        lifecycleScope.launch { settings.themeMode.collectLatest { theme = ThemeMode.fromStorage(it) } }
        lifecycleScope.launch { settings.fontScale.collectLatest { fontScale = it } }
        lifecycleScope.launch {
            try { publication = library.open(uri) }
            catch (e: Exception) { error = e.message ?: "This EPUB cannot be opened." }
        }

        setContent {
            PlainNotesTheme(theme, fontScale) {
                val colors = MaterialTheme.colorScheme
                val toolbarColors = plainNotesTopAppBarColors()
                SideEffect {
                    window.statusBarColor = Color.Transparent.toArgb()
                    window.navigationBarColor = Color.Transparent.toArgb()
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = if (controls) toolbarColors.titleContentColor.luminance() < 0.5f
                            else colors.background.luminance() > 0.5f
                        isAppearanceLightNavigationBars = (if (controls) colors.surface else colors.background).luminance() > 0.5f
                    }
                }
                val reading = publication
                LaunchedEffect(reading, theme, fontScale) {
                    navigator?.submitPreferences(preferences(theme, fontScale, colors.background.toArgb(), colors.onBackground.toArgb()))
                }
                LaunchedEffect(controls, controlInteraction, toc, scrubbing) {
                    if (controls && !toc && !scrubbing) {
                        delay(5_000)
                        controls = false
                    }
                }
                LaunchedEffect(showTapHints) {
                    if (showTapHints) {
                        delay(1_500)
                        showTapHints = false
                    }
                }
                val hintAlpha by animateFloatAsState(if (showTapHints) 1f else 0f,
                    animationSpec = tween(900), label = "Page turn hints")
                BackHandler(enabled = toc || controls) {
                    if (toc) toc = false else controls = false
                }
                Box(Modifier.fillMaxSize().background(colors.background)) {
                    when {
                        error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                            Text(error!!)
                            TextButton(onClick = ::finish) { Text("Back to library") }
                        }
                        reading == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> {
                            AndroidView(factory = { context -> FrameLayout(context).apply { id = containerId } },
                                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), update = { frame ->
                                    if (navigator == null) {
                                        val initial = runCatching { library.locator(uri)?.let { Locator.fromJSON(JSONObject(it)) } }.getOrNull()
                                        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(reading).createFragmentFactory(
                                            initialLocator = initial,
                                            // Compose already applies safe drawing insets; do not pad the cutout twice.
                                            configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false),
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
                                            override fun onTap(event: TapEvent): Boolean {
                                                val view = navigator?.publicationView ?: return false
                                                when (ReaderTapZones.direction(event.point.x, event.point.y, view.width, view.height)) {
                                                    -1 -> { navigator?.goBackward(); controlInteraction++ }
                                                    1 -> { navigator?.goForward(); controlInteraction++ }
                                                    else -> { controls = !controls; controlInteraction++ }
                                                }
                                                return true
                                            }
                                        })
                                        showTapHints = true
                                        lifecycleScope.launch {
                                            navigator?.currentLocator?.collectLatest { locator ->
                                                progress = locator.locations.totalProgression ?: progress
                                                library.savePosition(uri, locator.toJSON().toString(), progress)
                                            }
                                        }
                                    }
                                })
                            // Draw only: Readium retains swipes, text selection, and book links.
                            // The visible curve and the tap test share the exact same dimensions.
                            Canvas(Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                if (hintAlpha > 0f) {
                                    val edge = size.width * ReaderTapZones.END_WIDTH
                                    val control = size.width * ReaderTapZones.CURVE_CONTROL_WIDTH
                                    val left = Path().apply {
                                        moveTo(0f, 0f); lineTo(edge, 0f)
                                        quadraticBezierTo(control, size.height / 2, edge, size.height)
                                        lineTo(0f, size.height); close()
                                    }
                                    val right = Path().apply {
                                        moveTo(size.width, 0f); lineTo(size.width - edge, 0f)
                                        quadraticBezierTo(size.width - control, size.height / 2, size.width - edge, size.height)
                                        lineTo(size.width, size.height); close()
                                    }
                                    drawPath(left, colors.primary.copy(alpha = 0.18f * hintAlpha))
                                    drawPath(right, colors.primary.copy(alpha = 0.18f * hintAlpha))
                                }
                            }
                            AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(),
                                modifier = Modifier.align(Alignment.TopCenter)) {
                                // The bar fills the status-bar area; TopAppBar applies its inset once.
                                TopAppBar(title = { Text(reading.metadata.title ?: "E Reader", maxLines = 1,
                                        overflow = TextOverflow.Ellipsis) },
                                    navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") } },
                                    actions = { IconButton(onClick = { toc = true; controlInteraction++ }) { Icon(Icons.Rounded.List, "Table of contents") } },
                                    colors = toolbarColors)
                            }
                            AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(),
                                modifier = Modifier.align(Alignment.BottomCenter)) {
                                Surface(color = colors.surface) {
                                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
                                        Slider(
                                            value = if (scrubbing) scrubProgress else progress.toFloat().coerceIn(0f, 1f),
                                            onValueChange = { scrubProgress = it; scrubbing = true; controlInteraction++ },
                                            onValueChangeFinished = {
                                                val target = scrubProgress.toDouble()
                                                seekError = null
                                                lifecycleScope.launch {
                                                    try {
                                                        val locator = reading.locateProgression(target)
                                                        if (locator != null && navigator?.go(locator) == true) progress = target
                                                        else seekError = "Unable to change reading position."
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        seekError = "Unable to change reading position."
                                                    } finally {
                                                        scrubbing = false
                                                        controlInteraction++
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Reading progress" },
                                        )
                                        seekError?.let { Text(it, color = colors.error, style = MaterialTheme.typography.labelMedium) }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { navigator?.goBackward(); controlInteraction++ }) {
                                                Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "Previous page")
                                            }
                                            Text("${((if (scrubbing) scrubProgress.toDouble() else progress) * 100).toInt()}%",
                                                style = MaterialTheme.typography.labelLarge)
                                            IconButton(onClick = { navigator?.goForward(); controlInteraction++ }) {
                                                Icon(Icons.AutoMirrored.Rounded.NavigateNext, "Next page")
                                            }
                                        }
                                    }
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
