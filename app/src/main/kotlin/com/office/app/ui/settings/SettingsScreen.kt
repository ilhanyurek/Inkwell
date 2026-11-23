package com.office.app.ui.settings

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.Settings
import com.office.app.data.ThemeMode
import com.office.app.ui.design.CardDivider
import com.office.app.ui.design.CardGroup
import com.office.app.ui.design.ChoiceRow
import com.office.app.ui.design.IconTile
import com.office.app.ui.design.ListRow
import com.office.app.ui.design.PageScaffold
import com.office.app.ui.design.SectionHeader
import com.office.app.ui.design.SegmentedPills
import com.office.app.ui.design.SwitchRow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    PageScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_appearance))
            val modes = ThemeMode.entries
            SegmentedPills(
                options = modes.map {
                    stringResource(
                        when (it) {
                            ThemeMode.SYSTEM -> R.string.theme_short_system
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                            ThemeMode.BLACK -> R.string.theme_short_black
                        },
                    )
                },
                selected = modes.indexOf(settings.theme),
                onSelect = { i -> scope.launch { container.settings.setTheme(modes[i]) } },
                testTags = modes.map { "theme_${it.name.lowercase()}" },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_white_paper),
                    subtitle = stringResource(R.string.settings_white_paper_desc),
                    checked = settings.whitePaper,
                    icon = Icons.Outlined.Description,
                    onChange = { v -> scope.launch { container.settings.setWhitePaper(v) } },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    CardDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_desc),
                        checked = settings.dynamicColor,
                        icon = Icons.Outlined.Palette,
                        onChange = { v -> scope.launch { container.settings.setDynamicColor(v) } },
                    )
                }
            }

            SectionHeader(stringResource(R.string.settings_editor))
            var scale by remember(settings.editorTextScale) { mutableFloatStateOf(settings.editorTextScale) }
            CardGroup {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Outlined.FormatSize)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.settings_text_size), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${(scale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = scale,
                        onValueChange = { scale = it },
                        onValueChangeFinished = { scope.launch { container.settings.setEditorTextScale(scale) } },
                        valueRange = 0.8f..1.6f,
                        steps = 7,
                        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Text(
                        stringResource(R.string.settings_text_preview),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CardDivider(inset = 0.dp)
                SwitchRow(
                    title = stringResource(R.string.settings_keep_files_open),
                    subtitle = stringResource(R.string.settings_keep_files_open_desc),
                    checked = settings.keepFilesOpen,
                    icon = Icons.Outlined.Tab,
                    onChange = { v -> scope.launch { container.settings.setKeepFilesOpen(v) } },
                    modifier = Modifier.testTag("setting_keep_files_open"),
                )
                CardDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_autosave),
                    subtitle = stringResource(R.string.settings_autosave_desc),
                    checked = settings.autoSaveToFile,
                    icon = Icons.Outlined.Save,
                    onChange = { v -> scope.launch { container.settings.setAutoSaveToFile(v) } },
                )
            }

            SectionHeader(stringResource(R.string.settings_language))
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            CardGroup {
                ChoiceRow(
                    title = stringResource(R.string.language_system),
                    selected = current.isEmpty(),
                    icon = Icons.Outlined.Language,
                    onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) },
                )
                // Each language is listed by its own name so people can find theirs whatever the current language is.
                LANGUAGES.forEach { (tags, name) ->
                    CardDivider(inset = 16.dp)
                    ChoiceRow(
                        title = name,
                        selected = tags.any { current.startsWith(it) },
                        onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags.first())) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.settings_about))
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            }
            CardGroup {
                ListRow(
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.settings_version, version),
                    leading = { IconTile(Icons.Outlined.Info) },
                )
                CardDivider(inset = 0.dp)
                Text(
                    stringResource(R.string.settings_formats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                Text(
                    stringResource(R.string.settings_licenses),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
                Text(
                    stringResource(R.string.settings_font_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 14.dp),
                )
            }
        }
    }
}

/** Interface languages: language tags (first one is applied) and the language's own name. */
private val LANGUAGES = listOf(
    listOf("tr") to "Türkçe",
    listOf("en") to "English",
    listOf("zh") to "中文（简体）",
    listOf("hi") to "हिन्दी",
    listOf("es") to "Español",
    listOf("fr") to "Français",
    listOf("ar") to "العربية",
    listOf("bn") to "বাংলা",
    listOf("pt") to "Português",
    listOf("ru") to "Русский",
    listOf("ur") to "اردو",
    listOf("id", "in") to "Bahasa Indonesia",
    listOf("de") to "Deutsch",
    listOf("ja") to "日本語",
    listOf("mr") to "मराठी",
    listOf("te") to "తెలుగు",
    listOf("ta") to "தமிழ்",
    listOf("vi") to "Tiếng Việt",
    listOf("tl", "fil") to "Filipino",
    listOf("ko") to "한국어",
    listOf("fa") to "فارسی",
    listOf("sw") to "Kiswahili",
)
