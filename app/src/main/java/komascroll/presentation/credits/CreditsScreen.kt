package komascroll.presentation.credits

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.screen.about.OpenSourceLicensesScreen
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import komascroll.core.KomaScrollBranding
import komascroll.i18n.KSR
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Acknowledges the upstream projects KomaScroll is built on, as required by their Apache 2.0 licenses.
 */
class CreditsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KSR.strings.credits),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    LogoHeader()
                }

                item {
                    Text(
                        text = stringResource(KSR.strings.credits_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.medium,
                        ),
                    )
                }

                upstreamProjects.forEach { project ->
                    item(key = project.name) {
                        TextPreferenceWidget(
                            title = project.name,
                            subtitle = stringResource(project.description),
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                            onPreferenceClick = { uriHandler.openUri(project.url) },
                        )
                    }
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(KSR.strings.credits_license_title),
                        subtitle = stringResource(KSR.strings.credits_license_body),
                        icon = Icons.Outlined.Gavel,
                        onPreferenceClick = { uriHandler.openUri(APACHE_LICENSE_URL) },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.licenses),
                        icon = Icons.Outlined.Code,
                        onPreferenceClick = { navigator.push(OpenSourceLicensesScreen()) },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(KSR.strings.credits_source_code),
                        subtitle = KomaScrollBranding.GITHUB_URL,
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        onPreferenceClick = { uriHandler.openUri(KomaScrollBranding.GITHUB_URL) },
                    )
                }
            }
        }
    }

    private data class UpstreamProject(
        val name: String,
        val description: StringResource,
        val url: String,
    )

    // Kept in the companion object so the Voyager screen itself holds no non-serializable state.
    private companion object {
        const val APACHE_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

        val upstreamProjects = listOf(
            UpstreamProject("Komikku", KSR.strings.credits_komikku, "https://github.com/komikku-app/komikku"),
            UpstreamProject("Mihon", KSR.strings.credits_mihon, "https://github.com/mihonapp/mihon"),
            UpstreamProject("TachiyomiSY", KSR.strings.credits_tachiyomisy, "https://github.com/jobobby04/TachiyomiSY"),
            UpstreamProject("Tachiyomi", KSR.strings.credits_tachiyomi, "https://github.com/tachiyomiorg/tachiyomi"),
        )
    }
}
