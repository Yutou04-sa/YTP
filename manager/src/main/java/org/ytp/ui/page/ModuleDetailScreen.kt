package org.ytp.ui.page

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import org.ytp.R
import org.ytp.model.Collaborator
import org.ytp.model.Release
import org.ytp.model.XposedModule
import org.ytp.ui.component.CenterTopBar
import org.ytp.ui.component.YtpCard
import org.ytp.ui.component.YtpEmptyState
import org.ytp.ui.component.YtpIconContainer
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.util.Html
import org.ytp.ui.viewmodel.RepoViewModel
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private const val detailPageCount = 3
private const val githubUserBaseUrl = "https://github.com/"

@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun ModuleDetailScreen(
    navigator: DestinationsNavigator,
    module: XposedModule,
    viewModel: RepoViewModel = viewModel()
) {
    val pagerState = rememberPagerState(pageCount = { detailPageCount })
    val scope = rememberCoroutineScope()
    var readmeHtml by remember(module.name) { mutableStateOf(module.readmeHTML) }
    var releases by remember(module.name) { mutableStateOf(module.releases) }
    var collaborators by remember(module.name) { mutableStateOf(module.collaborators) }

    LaunchedEffect(module.name) {
        if (readmeHtml.isNullOrBlank() || releases.isEmpty()) {
            viewModel.loadModuleDetail(module.name) { detail ->
                detail ?: return@loadModuleDetail
                readmeHtml = detail.readmeHTML
                releases = detail.releases
                collaborators = detail.collaborators
            }
        }
    }

    Scaffold(
        topBar = {
            CenterTopBar(
                text = module.description.ifEmpty { module.name },
                onBackClick = navigator::popBackStack,
                containerColor = YtpColors.PageChrome,
                scrolledContainerColor = YtpColors.PageChrome,
                contentColor = YtpColors.TextPrimary
            )
        },
        containerColor = YtpColors.PageContainer
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ModuleTabRow(
                selectedPage = pagerState.currentPage,
                onTabClick = { page -> scope.launch { pagerState.animateScrollToPage(page) } }
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> ReleasesTab(releases)
                    1 -> ReadmeTab(readmeHtml)
                    2 -> CollaboratorsTab(collaborators)
                }
            }
        }
    }
}

@Composable
private fun ModuleTabRow(selectedPage: Int, onTabClick: (Int) -> Unit) {
    val tabs = listOf(
        stringResource(R.string.repo_version) to Icons.Outlined.NewReleases,
        stringResource(R.string.repo_details) to Icons.Outlined.Description,
        stringResource(R.string.repo_info) to Icons.Outlined.Group
    )
    TabRow(
        selectedTabIndex = selectedPage,
        containerColor = YtpColors.PageChrome,
        divider = {}
    ) {
        tabs.forEachIndexed { index, (title, icon) ->
            Tab(
                selected = selectedPage == index,
                onClick = { onTabClick(index) },
                text = { Text(title, maxLines = 1) },
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                selectedContentColor = YtpColors.iconForeground(YtpColors.Primary),
                unselectedContentColor = YtpColors.iconForeground(YtpColors.Primary).copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
private fun ReadmeTab(readmeHTML: String?) {
    if (readmeHTML.isNullOrBlank()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            YtpEmptyState(
                title = stringResource(R.string.repo_empty_readme_text),
                icon = Icons.Outlined.Description,
                iconContainerColor = YtpColors.PrimaryContainer,
                iconContentColor = YtpColors.IconContent
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 30.dp)
    ) {
        item {
            YtpCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = MaterialTheme.shapes.large,
                        ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                        spotColor = YtpColors.Primary.copy(alpha = 0.05f)
                    ),
                containerColor = YtpColors.Surface,
                borderColor = YtpColors.Border
            ) {
                Html(
                    html = readmeHTML,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ReleasesTab(releases: List<Release>) {
    if (releases.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            YtpEmptyState(
                title = stringResource(R.string.repo_no_version),
                icon = Icons.Outlined.NewReleases,
                iconContainerColor = YtpColors.PrimaryContainer,
                iconContentColor = YtpColors.IconContent
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(releases, key = { "${it.name}-${it.publishedAt}" }) { release ->
            ReleaseItem(release)
        }
    }
}

@Composable
private fun ReleaseItem(release: Release) {
    val context = LocalContext.current
    YtpCard(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = MaterialTheme.shapes.large,
                ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                spotColor = YtpColors.Primary.copy(alpha = 0.05f)
            ),
        containerColor = YtpColors.Surface,
        borderColor = YtpColors.Border
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                YtpIconContainer(
                    icon = Icons.Outlined.NewReleases,
                    containerColor = YtpColors.PrimaryContainer,
                    contentColor = YtpColors.IconContent
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = YtpColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    release.publishedAt?.takeIf(String::isNotBlank)?.let {
                        Text(
                            text = formatDateTime(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = YtpColors.TextSecondary
                        )
                    }
                }
            }
            release.descriptionHTML?.takeIf(String::isNotBlank)?.let { description ->
                Html(html = description, modifier = Modifier.fillMaxWidth())
            }
            release.releaseAssets.forEach { asset ->
                FilledTonalButton(
                    onClick = { openUrl(context, asset.downloadUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = YtpColors.PrimaryContainer,
                        contentColor = YtpColors.iconForeground(YtpColors.Primary)
                    )
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = asset.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CollaboratorsTab(collaborators: List<Collaborator>?) {
    if (collaborators.isNullOrEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            YtpEmptyState(
                title = stringResource(R.string.repo_empty_collaborators_text),
                icon = Icons.Outlined.Group,
                iconContainerColor = YtpColors.AccentPurpleContainer,
                iconContentColor = YtpColors.AccentPurple
            )
        }
        return
    }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(collaborators, key = { it.login }) { collaborator ->
            YtpCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = MaterialTheme.shapes.large,
                        ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                        spotColor = YtpColors.AccentPurple.copy(alpha = 0.05f)
                    )
                    .clickable { openUrl(context, githubUserBaseUrl + collaborator.login) },
                containerColor = YtpColors.Surface,
                borderColor = YtpColors.Border
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    YtpIconContainer(
                        icon = Icons.Outlined.Group,
                        containerColor = YtpColors.AccentPurpleContainer,
                        contentColor = YtpColors.AccentPurple
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = collaborator.name ?: collaborator.login,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = YtpColors.TextPrimary
                        )
                        Text(
                            text = "@${collaborator.login}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = YtpColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

private fun formatDateTime(date: String): String = try {
    val input = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    val output = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
    output.format(LocalDateTime.parse(date, input).atOffset(ZoneOffset.UTC))
} catch (_: DateTimeParseException) {
    date
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
