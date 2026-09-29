package org.ytp.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.ytp.R
import org.ytp.model.XposedModule
import org.ytp.ui.component.YtpCard
import org.ytp.ui.component.YtpEmptyState
import org.ytp.ui.component.SearchAppBar
import org.ytp.ui.component.ShimmerAnimation
import org.ytp.ui.page.destinations.ModuleDetailScreenDestination
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.viewmodel.RepoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Destination
@Composable
fun RepoScreen(
    navigator: DestinationsNavigator,
    viewModel: RepoViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Scaffold(
        topBar = {
            SearchAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.screen_repo),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                searchText = searchQuery,
                onSearchTextChange = viewModel::updateSearchQuery,
                onClearClick = { viewModel.updateSearchQuery("") },
                onBackClick = navigator::popBackStack,
                menuContent = { close ->
                    SortMenuItem("↓ ${stringResource(R.string.repo_update_time)}") {
                        viewModel.setSortType(0); close()
                    }
                    SortMenuItem("↑ ${stringResource(R.string.repo_update_time)}") {
                        viewModel.setSortType(1); close()
                    }
                    SortMenuItem("${stringResource(R.string.repo_app_name)} (A–Z)") {
                        viewModel.setSortType(2); close()
                    }
                    SortMenuItem("${stringResource(R.string.repo_app_name)} (Z–A)") {
                        viewModel.setSortType(3); close()
                    }
                },
                containerColor = YtpColors.PageChrome,
                scrolledContainerColor = YtpColors.PageChrome,
                contentColor = YtpColors.TextPrimary
            )
        },
        containerColor = YtpColors.PageContainer
    ) { innerPadding ->
        when (val state = uiState) {
            is RepoViewModel.RepoUiState.Loading -> RepositoryLoading(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            )
            is RepoViewModel.RepoUiState.Error -> YtpEmptyState(
                title = state.message,
                icon = Icons.Outlined.CloudOff,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                iconContainerColor = YtpColors.AccentPurpleContainer,
                iconContentColor = YtpColors.AccentPurple,
                action = {
                    Button(
                        onClick = viewModel::loadModules,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = YtpColors.ThemePrimary,
                            contentColor = YtpColors.PrimaryActionContent
                        )
                    ) {
                        Text(stringResource(R.string.repo_retry))
                    }
                }
            )
            is RepoViewModel.RepoUiState.Success -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.loadModules(true) },
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                if (state.modules.isEmpty()) {
                    YtpEmptyState(
                        title = stringResource(R.string.repo_empty_modules),
                        icon = Icons.Outlined.Inventory2,
                        iconContainerColor = YtpColors.PrimaryContainer,
                        iconContentColor = YtpColors.IconContent,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 10.dp,
                            end = 16.dp,
                            bottom = 30.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // The repo JSON is remote data: duplicate `name` values would crash the
                        // list with "Key ... was already used", so the index keeps keys unique.
                        itemsIndexed(state.modules, key = { index, module -> "$index-${module.name}" }) { _, module ->
                            ModuleItem(module) {
                                navigator.navigate(ModuleDetailScreenDestination(module))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun RepositoryLoading(modifier: Modifier = Modifier) {
    ShimmerAnimation(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 10.dp,
                end = 16.dp,
                bottom = 30.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            items(7) { index ->
                YtpCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(18.dp),
                            ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                            spotColor = YtpColors.Primary.copy(alpha = 0.05f)
                        ),
                    containerColor = YtpColors.Surface,
                    borderColor = YtpColors.Border
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(brush, CircleShape)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (index % 2 == 0) 0.78f else 0.58f)
                                    .height(18.dp)
                                    .background(brush, MaterialTheme.shapes.small)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.46f)
                                    .height(12.dp)
                                    .background(brush, MaterialTheme.shapes.small)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (index % 3 == 0) 0.92f else 0.68f)
                                    .height(12.dp)
                                    .background(brush, MaterialTheme.shapes.small)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleItem(module: XposedModule, onClick: () -> Unit) {
    val displayTitle = remember(module.description, module.name, module.isUpdate) {
        module.description.ifEmpty { module.name }
    }
    val statusColor: Color = when {
        module.isUpdate -> YtpColors.ModuleUpdateIcon
        module.installed.isNotEmpty() -> YtpColors.ModuleInstalledIcon
        else -> YtpColors.ModuleAvailableIcon
    }

    YtpCard(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                spotColor = statusColor.copy(alpha = 0.07f)
            )
            .clickable(onClick = onClick),
        containerColor = YtpColors.Surface,
        borderColor = YtpColors.Border
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    YtpColors.iconBackground(statusColor, 0.14f),
                                    YtpColors.iconBackground(statusColor, 0.06f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = statusColor
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = YtpColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = module.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = YtpColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = YtpColors.iconForeground(YtpColors.Primary).copy(alpha = 0.70f)
                )
            }

            if (!module.summary.isNullOrBlank()) {
                Text(
                    text = module.summary!!,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = YtpColors.TextSecondary
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (module.isUpdate) {
                    StatusBadge(
                        text = "${module.installed} → ${module.latestVersionCode}",
                        color = statusColor
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!module.latestReleaseTime.isNullOrBlank()) {
                    Text(
                        text = module.formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = YtpColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}
