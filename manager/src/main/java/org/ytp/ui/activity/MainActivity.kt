/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ramcosta.composedestinations.DestinationsNavHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ytp.R
import org.ytp.config.Configs
import org.ytp.lspApp
import org.ytp.model.UpdateInfo
import org.ytp.share.YTPConfig
import org.ytp.ui.component.UpdateDialog
import org.ytp.ui.page.BottomBarDestination
import org.ytp.ui.page.NavGraphs
import org.ytp.ui.page.appCurrentDestinationAsState
import org.ytp.ui.page.destinations.Destination
import org.ytp.ui.page.startAppDestination
import org.ytp.ui.theme.YtpColors
import org.ytp.ui.theme.LSPTheme
import org.ytp.ui.util.LocalSnackbarHost
import org.ytp.util.UpdateChecker

class MainActivity : ComponentActivity() {

    private var updateInfo by mutableStateOf<UpdateInfo?>(null)
    private var showUpdateDialog by mutableStateOf(false)

    @SuppressLint("WrongConstant")
    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 生命周期作用域 + 主线程：更新结果要写 Compose 状态，必须回到主线程，
        // 并且 Activity 销毁后不能再继续写状态。
        lifecycleScope.launch {
            val update = UpdateChecker.checkUpdate()
            if (update != null) {
                updateInfo = update
                lspApp.updateInfo = update
                if (YTPConfig.instance.VERSION_CODE < update.version) {
                    showUpdateDialog = true
                }
            }
        }



        setContent {
            val navController = rememberNavController()
            LSPTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
                    Scaffold(
                        bottomBar = { BottomBar(navController) },
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        containerColor = MaterialTheme.colorScheme.background
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            PageBackgroundImage()

                            DestinationsNavHost(
                                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                                navGraph = NavGraphs.root,
                                navController = navController
                            )
                        }
                        // 显示更新对话框
                        if (showUpdateDialog && updateInfo != null) {
                            if(Configs.updateNever != updateInfo!!.version || updateInfo!!.force) {
                                UpdateDialog(
                                    updateInfo = updateInfo!!,
                                    onDismiss = {
                                        showUpdateDialog = false
                                    },
                                    onCancel = {
                                        showUpdateDialog = false
                                        //点击关闭后，写入一个值到SharedPreferences中，表示已经显示过更新对话框
                                        Configs.updateNever = updateInfo!!.version
                                    },
                                    onDownload = {
                                        val downloadUrl =
                                            if (java.util.Locale.getDefault().language.lowercase()
                                                    .contains("zh")
                                            ) {
                                                updateInfo!!.zh.download
                                            } else {
                                                updateInfo!!.en.download
                                            }
                                        val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri())
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(intent)
                                        if (!updateInfo!!.force) {
                                            showUpdateDialog = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController) {
    val currentDestination: Destination = navController.appCurrentDestinationAsState().value
        ?: NavGraphs.root.startAppDestination
    var topDestination by rememberSaveable { mutableStateOf(currentDestination.route) }
    LaunchedEffect(currentDestination) {
        val queue = navController.currentBackStack.value
        if (queue.size == 2) topDestination = queue[1].destination.route!!
        else if (queue.size > 2) topDestination = queue[2].destination.route!!
    }

    GlassNavigationBar(
        destinations = BottomBarDestination.navbarItems(),
        selectedRoute = topDestination,
        onSelect = { destination ->
            navController.navigate(destination.direction.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    )
}

/**
 * 液态玻璃底栏。
 *
 * 悬浮的圆角胶囊：半透明渐变 + 描边高光 + 斜向反光，底部留一段圆角玻璃折射的光晕；
 * 选中项是一片会滑动的高亮镜片（[animateDpAsState] 弹簧动画）。
 *
 * 设置了自定义背景图时，整块玻璃按「控件透明度」（[Configs.surfaceAlpha]）变透明，
 * 底图会从玻璃下面透出来；没有背景图时几乎是实心的，只是颜色更通透一点。
 */
@Composable
private fun GlassNavigationBar(
    destinations: List<BottomBarDestination>,
    selectedRoute: String?,
    onSelect: (BottomBarDestination) -> Unit
) {
    val dark = YtpColors.IsDarkTheme
    val monochrome = YtpColors.IsMonochromeTheme

    val glassTop = when {
        monochrome && dark -> Color(0xFF232323)
        monochrome -> Color.White
        dark -> Color(0xFF152326)
        else -> Color.White
    }
    val glassBottom = when {
        monochrome && dark -> Color(0xFF131313)
        monochrome -> Color(0xFFF1F1F1)
        dark -> Color(0xFF0A1214)
        else -> Color(0xFFEAF4F3)
    }
    val glassAlpha = if (Configs.backgroundImage.isNotEmpty()) {
        (0.74f * Configs.surfaceAlpha).coerceIn(0.20f, 0.94f)
    } else {
        0.97f
    }
    val shape = RoundedCornerShape(30.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
            .shadow(elevation = 12.dp, shape = shape)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        glassTop.copy(alpha = glassAlpha),
                        glassBottom.copy(alpha = glassAlpha)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.16f else 0.80f),
                        Color.White.copy(alpha = 0.04f)
                    )
                ),
                shape = shape
            )
    ) {
        // 斜向反光，玻璃的“液态”就靠这一层
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) 0.05f else 0.26f),
                            Color.Transparent,
                            Color.White.copy(alpha = if (dark) 0.02f else 0.08f)
                        )
                    )
                )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            val lensShape = RoundedCornerShape(20.dp)
            if (destinations.isNotEmpty()) {
                val itemWidth = maxWidth / destinations.size.toFloat()
                val selectedIndex = destinations
                    .indexOfFirst { it.direction.route == selectedRoute }
                    .coerceAtLeast(0)
                val lensOffset by animateDpAsState(
                    targetValue = itemWidth * selectedIndex.toFloat(),
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "glassLensOffset"
                )

                Box(
                    modifier = Modifier
                        .offset(x = lensOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp)
                        .clip(lensShape)
                        .background(YtpColors.Primary.copy(alpha = 0.16f))
                        .border(1.dp, YtpColors.Primary.copy(alpha = 0.30f), lensShape)
                )
            }

            Row(modifier = Modifier.fillMaxSize()) {
                destinations.forEach { destination ->
                    GlassNavigationItem(
                        destination = destination,
                        selected = destination.direction.route == selectedRoute,
                        onClick = { onSelect(destination) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassNavigationItem(
    destination: BottomBarDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconColor by animateColorAsState(
        targetValue = if (selected) YtpColors.iconForeground(YtpColors.Primary)
        else YtpColors.TextSecondary,
        label = "glassIconColor"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) YtpColors.ThemePrimary else YtpColors.TextSecondary,
        label = "glassLabelColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        label = "glassIconScale"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) destination.iconSelected else destination.iconNotSelected,
            contentDescription = stringResource(destination.label),
            tint = iconColor,
            modifier = Modifier
                .size(23.dp)
                .scale(iconScale)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(destination.label),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1
        )
    }
}

/**
 * 设置里选择的自定义背景图。
 *
 * 没有设置时什么都不画，页面用默认纯色背景；
 * 设置之后页面容器会变成透明（[YtpColors.PageContainer]），底图就透出来了。
 */
@Composable
private fun PageBackgroundImage() {
    val path = Configs.backgroundImage
    if (path.isEmpty()) {
        return
    }

    // 背景是 Crop 铺满整屏，解码到屏幕长边附近就够；原实现主线程解整张图（12MP ≈ 48MB）并常驻。
    val context = LocalContext.current
    val targetSide = remember(context) {
        val metrics = context.resources.displayMetrics
        maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
    }

    // 解码放在 IO 线程：path 变化会重新解码；新图到货前先沿用上一张，视觉上不闪。
    val bitmap by produceState<Bitmap?>(initialValue = null, path, targetSide) {
        value = withContext(Dispatchers.IO) { decodeSampledBackground(path, targetSide) }
    }

    // 只回收本组件自己解码出来的位图：path 变化或组件销毁时释放上一张。
    DisposableEffect(bitmap) {
        val decoded = bitmap
        onDispose { decoded?.recycle() }
    }

    val ready = bitmap ?: return

    Image(
        bitmap = ready.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}

/**
 * 按 [targetSide]（屏幕长边，像素）采样解码背景图。
 *
 * `inJustDecodeBounds` 只读图片头拿尺寸，`inSampleSize` 是 2 的幂，让解码后的长边落在
 * `[targetSide / 2, targetSide)`：既明显小于原图，又不会糊到看得见。
 * 读不出尺寸或解码失败（文件被删、格式不认识）返回 null，界面就当作没有背景图。
 */
private fun decodeSampledBackground(path: String, targetSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    if (maxDim <= 0) return@runCatching null

    val minSide = (targetSide / 2).coerceAtLeast(1)
    var sampleSize = 1
    while (sampleSize < 1024 && maxDim / (sampleSize * 2) >= minSide) {
        sampleSize *= 2
    }

    BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}.getOrNull()
