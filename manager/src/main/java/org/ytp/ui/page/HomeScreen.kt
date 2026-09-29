package org.ytp.ui.page

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootNavGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import org.ytp.R
import org.ytp.config.Configs
import org.ytp.share.YTPConfig
import org.ytp.ui.component.AppFab
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.page.destinations.ModuleManagerScreenDestination
import org.ytp.ui.page.destinations.NewPatchScreenDestination
import org.ytp.ui.page.destinations.PatchedAppsScreenDestination
import org.ytp.ui.util.LocalSnackbarHost
import java.io.IOException

private val YtpBlue get() = YtpColors.ThemePrimary
private val YtpBlue2 get() = YtpColors.PrimaryLight
private val YtpCyan get() = YtpColors.PrimaryAccent
private val YtpGreen get() = YtpColors.Success
private val YtpPurple get() = YtpColors.AccentPurple
private val YtpSoftBlue get() = YtpColors.PrimaryContainer
private val YtpTextDark get() = YtpColors.TextPrimary
private val YtpTextMuted get() = YtpColors.TextSecondary
private val YtpPageBg get() = YtpColors.PageContainer
private val YtpSurface get() = YtpColors.Surface
private val YtpBorder get() = YtpColors.Border
private val YtpDivider get() = YtpColors.Divider

@OptIn(ExperimentalMaterial3Api::class)
@RootNavGraph(start = true)
@Destination
@Composable
fun HomeScreen(navigator: DestinationsNavigator) {
    var isIntentLaunched by rememberSaveable { mutableStateOf(false) }

    val intent = LocalActivity.current?.intent

    LaunchedEffect(intent) {
        if (
            !isIntentLaunched &&
            intent?.action == Intent.ACTION_VIEW &&
            intent.hasCategory(Intent.CATEGORY_DEFAULT)
        ) {
            val uri = intent.data
            // 只接受安装包来源：content/file scheme，且 mime 是 APK 或路径以 .apk 结尾。
            // 其余（intent://、自定义 scheme、其它 mime/后缀）一律忽略，
            // 避免外部 intent 把任意文件塞进安装流程。
            val schemeAllowed = uri?.scheme.equals("content", ignoreCase = true) == true ||
                uri?.scheme.equals("file", ignoreCase = true) == true
            val looksLikeApk = intent.type == "application/vnd.android.package-archive" ||
                uri?.lastPathSegment?.endsWith(".apk", ignoreCase = true) == true
            if (uri != null && schemeAllowed && looksLikeApk) {
                isIntentLaunched = true
                navigator.navigate(
                    NewPatchScreenDestination(
                        id = ACTION_INTENT_INSTALL,
                        data = uri
                    )
                )
            }
        }
    }

    Scaffold(
        containerColor = YtpPageBg,
        floatingActionButton = { AppFab(navigator) }
    ) { innerPadding ->
        // 首页改为固定顺序的纵向流式排布：内容始终可滚动，
        // 不再按视口高度加权分配空间，避免设备信息在小屏或大屏上被裁掉。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 88.dp
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RuntimeHero()
            DeviceSnapshot()
            ManagementActions(
                onOpenModules = { navigator.navigate(ModuleManagerScreenDestination) },
                onOpenPatchedApps = { navigator.navigate(PatchedAppsScreenDestination) }
            )
            CommunityAction()
        }
    }

}

@Composable
private fun RuntimeHero(
    modifier: Modifier = Modifier,
    fillAvailableHeight: Boolean = false
) {
    val shape = RoundedCornerShape(24.dp)

    BoxWithConstraints(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .then(if (fillAvailableHeight) Modifier.fillMaxHeight() else Modifier)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        YtpColors.PrimarySurface,
                        YtpColors.PrimarySurfaceHighlight,
                        YtpSoftBlue
                    )
                )
            )
            .border(
                width = 1.dp,
                color = YtpBlue.copy(alpha = 0.10f),
                shape = shape
            )
    ) {
        val heroProgress = if (fillAvailableHeight) {
            when {
                maxHeight <= 150.dp -> 0f
                maxHeight >= 250.dp -> 1f
                else -> (maxHeight.value - 150f) / 100f
            }
        } else {
            0f
        }
        val heroVerticalPadding = if (fillAvailableHeight) 6.dp else 14.dp
        val logoSize = (48f + 8f * heroProgress).dp
        val logoIconSize = (26f + 4f * heroProgress).dp
        val metricIconSize = (34f + 4f * heroProgress).dp
        val metricContentIconSize = (18f + 2f * heroProgress).dp
        val metricVerticalPadding = (9f + 2f * heroProgress).dp
        val heroVerticalSpacing = (6f + 4f * heroProgress).dp

        // 背景氛围圆
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 32.dp, y = (-34).dp)
                .size(140.dp)
                .clip(CircleShape)
                .background(YtpColors.decorativeAccent(YtpBlue, 0.07f))
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 46.dp, y = 16.dp)
                .size(96.dp)
                .clip(CircleShape)
                .background(YtpColors.decorativeAccent(YtpPurple, 0.06f))
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = heroVerticalPadding),
            verticalArrangement = if (fillAvailableHeight) {
                Arrangement.spacedBy(heroVerticalSpacing, Alignment.CenterVertically)
            } else {
                Arrangement.spacedBy(10.dp)
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = YtpTextDark
                    )
                    Text(
                        text = stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = YtpTextMuted
                    )
                }

                VersionChip()
            }

            FeaturePills(
                verticalPadding = (4f + 2f * heroProgress).dp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OverviewMetric(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Extension,
                    iconColor = YtpBlue,
                    label = stringResource(R.string.home_ytpatch_version),
                    value = YTPConfig.instance.VERSION_CODE.toString(),
                    iconSize = metricIconSize,
                    iconContentSize = metricContentIconSize,
                    verticalPadding = metricVerticalPadding,
                    cardBrush = Brush.linearGradient(
                        listOf(YtpSurface.copy(alpha = 0.84f), YtpSoftBlue)
                    )
                )

                OverviewMetric(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Memory,
                    iconColor = YtpPurple,
                    label = stringResource(R.string.home_api_version),
                    value = YTPConfig.instance.API_CODE.toString(),
                    iconSize = metricIconSize,
                    iconContentSize = metricContentIconSize,
                    verticalPadding = metricVerticalPadding,
                    cardBrush = Brush.linearGradient(
                        listOf(YtpSurface.copy(alpha = 0.84f), YtpSoftBlue)
                    )
                )
            }
        }
    }
}

@Composable
private fun VersionChip() {
    Surface(
        shape = RoundedCornerShape(50),
        color = YtpSurface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, YtpSurface.copy(alpha = 0.85f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "v${YTPConfig.instance.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = YtpColors.TextStrong
            )
        }
    }
}

@Composable
private fun FeaturePills(
    verticalPadding: Dp = 4.dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FeaturePill(
            stringResource(R.string.home_feature_no_root),
            YtpBlue,
            verticalPadding
        )
        FeaturePill(
            stringResource(R.string.home_feature_module_compatibility),
            YtpPurple,
            verticalPadding
        )
        FeaturePill(
            stringResource(R.string.home_feature_extension_freedom),
            YtpGreen,
            verticalPadding
        )
    }
}

@Composable
private fun FeaturePill(
    text: String,
    accent: Color,
    verticalPadding: Dp
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = YtpSurface.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.12f))
    ) {
        Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = YtpColors.TextSoft
            )
        }
    }
}

@Composable
private fun OverviewMetric(
    modifier: Modifier,
    icon: ImageVector,
    iconColor: Color,
    label: String,
    value: String,
    iconSize: Dp = 34.dp,
    iconContentSize: Dp = 18.dp,
    verticalPadding: Dp = 9.dp,
    cardBrush: Brush
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.04f),
                spotColor = YtpBlue.copy(alpha = 0.08f)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(cardBrush)
            .border(1.dp, YtpSurface.copy(alpha = 0.90f), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(iconSize)
                    .clip(CircleShape)
                    .background(
                        if (YtpColors.IsMonochromeTheme) {
                            YtpColors.IconButtonSurface
                        } else {
                            YtpColors.iconBackground(iconColor, 0.10f)
                        }
                    )
                    .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(iconContentSize),
                    tint = YtpColors.iconForeground(iconColor)
                )
            }

            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = YtpTextMuted
                )
                Text(
                    text = value,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = YtpTextDark
                )
            }
        }
    }
}

@Composable
private fun DeviceSnapshot(
    modifier: Modifier = Modifier,
    fillAvailableHeight: Boolean = false
) {
    Column(
        modifier = modifier
            .then(if (fillAvailableHeight) Modifier.fillMaxHeight() else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillAvailableHeight) Modifier.weight(1f) else Modifier)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(22.dp),
                    ambientColor = YtpColors.Shadow.copy(alpha = 0.03f),
                    spotColor = YtpBlue.copy(alpha = 0.06f)
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = YtpSurface),
            border = BorderStroke(1.dp, YtpBorder)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val deviceProgress = if (fillAvailableHeight) {
                    when {
                        maxHeight <= 130.dp -> 0f
                        maxHeight >= 230.dp -> 1f
                        else -> (maxHeight.value - 130f) / 100f
                    }
                } else {
                    0f
                }
                val deviceVerticalPadding = if (fillAvailableHeight) 4.dp else 0.dp
                val deviceRowVerticalPadding = if (fillAvailableHeight) 4.dp else 14.dp
                val deviceIconSize = (52f + 4f * deviceProgress).dp
                val deviceIconContentSize = (26f + 2f * deviceProgress).dp
                val metricIconSize = (32f + 4f * deviceProgress).dp
                val metricContentIconSize = (18f + 2f * deviceProgress).dp
                val metricPadding = (10f + 2f * deviceProgress).dp
                val deviceVerticalSpacing = (6f + 4f * deviceProgress).dp

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = deviceVerticalPadding),
                    verticalArrangement = if (fillAvailableHeight) {
                        Arrangement.spacedBy(
                            deviceVerticalSpacing,
                            Alignment.CenterVertically
                        )
                    } else {
                        Arrangement.Center
                    }
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 14.dp,
                            vertical = deviceRowVerticalPadding
                        ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(deviceIconSize)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(YtpColors.PrimarySurface, YtpSoftBlue)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Smartphone,
                            contentDescription = null,
                            modifier = Modifier.size(deviceIconContentSize),
                            tint = YtpBlue
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = device,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = YtpTextDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }

                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = stringResource(R.string.home_device),
                            style = MaterialTheme.typography.bodySmall,
                            color = YtpTextMuted
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = YtpDivider
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    DeviceMetric(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Outlined.Android,
                        iconSize = metricIconSize,
                        iconContentSize = metricContentIconSize,
                        contentPadding = metricPadding,
                        label = stringResource(R.string.home_system_version),
                        value = "$systemVersion · $androidApi",
                        accent = YtpBlue
                    )

                    VerticalDivider(
                        modifier = Modifier.padding(
                            vertical = if (fillAvailableHeight) 8.dp else 10.dp
                        ),
                        color = YtpDivider
                    )

                    DeviceMetric(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Outlined.Memory,
                        iconSize = metricIconSize,
                        iconContentSize = metricContentIconSize,
                        contentPadding = metricPadding,
                        label = stringResource(R.string.home_system_abi),
                        value = primaryAbi,
                        accent = YtpPurple
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun DeviceMetric(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color,
    iconSize: Dp = 32.dp,
    contentPadding: Dp = 10.dp,
    iconContentSize: Dp = 18.dp
) {
    Row(
        modifier = modifier.padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(CircleShape)
                .background(YtpColors.iconBackground(accent, 0.09f))
                .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconContentSize),
                tint = YtpColors.iconForeground(accent)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = YtpTextMuted
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = YtpTextDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CommunityAction() {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)

    Card(
        onClick = {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://t.me/Yutou_v50".toUri()
                )
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = shape,
                ambientColor = YtpBlue.copy(alpha = 0.05f),
                spotColor = YtpBlue.copy(alpha = 0.08f)
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, YtpBlue.copy(alpha = 0.12f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            YtpColors.PrimarySurface,
                            YtpColors.PrimarySurfaceHighlight,
                            YtpSoftBlue
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 24.dp, y = (-22).dp)
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(YtpColors.decorativeAccent(YtpBlue, 0.05f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 42.dp)
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(YtpColors.decorativeAccent(YtpBlue, 0.04f))
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(YtpColors.iconBackground(YtpBlue, 0.10f))
                            .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = YtpColors.iconForeground(YtpBlue)
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(
                                R.string.home_view_source_code,
                                stringResource(R.string.home_telegram_channel)
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = YtpTextDark,
                            textAlign = TextAlign.Start
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.home_telegram_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = YtpTextMuted,
                            textAlign = TextAlign.Start
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(YtpColors.iconBackground(YtpBlue, 0.08f))
                        .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = YtpColors.iconForeground(YtpBlue)
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagementActions(
    onOpenModules: () -> Unit,
    onOpenPatchedApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    // NP 风格的入口列表：整行卡片（图标盒 + 名称 + 箭头），纵向排列；
    // 补丁入口仍走右下角的悬浮按钮（Scaffold 的 floatingActionButton）。
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ManagementEntry(
            icon = Icons.Outlined.Extension,
            title = stringResource(R.string.home_modules),
            accent = YtpColors.MikuTeal,
            onClick = onOpenModules
        )

        ManagementEntry(
            icon = Icons.Outlined.Apps,
            title = stringResource(R.string.home_patched_apps),
            accent = YtpColors.MikuPink,
            onClick = onOpenPatchedApps
        )
    }
}

/** 首页入口行：整行卡片，左侧圆角图标盒、中间名称、右侧箭头。 */
@Composable
private fun ManagementEntry(
    icon: ImageVector,
    title: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    val iconShape = RoundedCornerShape(14.dp)

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = YtpColors.Shadow.copy(alpha = 0.02f),
                spotColor = accent.copy(alpha = 0.07f)
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = YtpSurface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(iconShape)
                    .background(YtpColors.iconBackground(accent, 0.14f))
                    .border(1.dp, YtpColors.IconButtonBorder, iconShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = YtpColors.iconForeground(accent)
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = YtpColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = YtpTextMuted
            )
        }
    }
}

// 设备信息全部来自 android.os.Build 的静态读取；个别 ROM 上这些字段可能为空字符串，
// 这里统一兜底，避免首页出现空白行（"读不到设备信息"的另一半保险）。
private val systemVersion = (
    if (Build.VERSION.PREVIEW_SDK_INT != 0) {
        "${Build.VERSION.CODENAME} Preview"
    } else {
        Build.VERSION.RELEASE
    }
).orEmpty().ifBlank { Build.VERSION.CODENAME.orEmpty() }.ifBlank { "Unknown" }

private val androidApi = "API ${Build.VERSION.SDK_INT}"

private val device = listOfNotNull(
    Build.MANUFACTURER.toDisplayName().ifBlank { null },
    Build.BRAND
        .takeUnless {
            it.equals(Build.MANUFACTURER, ignoreCase = true)
        }
        ?.toDisplayName()
        ?.ifBlank { null },
    Build.MODEL.ifBlank { null }
).joinToString(" ").ifBlank {
    Build.DEVICE.orEmpty().ifBlank { Build.PRODUCT.orEmpty() }
        .toDisplayName()
        .ifBlank { "Unknown" }
}

private val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "Unknown" }

private fun String.toDisplayName(): String =
    trim().replaceFirstChar { character ->
        if (character.isLowerCase()) {
            character.titlecase()
        } else {
            character.toString()
        }
    }
