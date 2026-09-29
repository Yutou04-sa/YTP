package org.ytp.ui.util

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import org.ytp.ui.theme.YtpColors

// 👇 这就是你要的 ComposeWebView，定义在这里
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Html(
    html: String,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = YtpColors.IsDarkTheme
    val themedHtml = remember(html, isDarkTheme) {
        if (isDarkTheme) html.withDarkThemeStyles() else html
    }
    // Remembers what is currently loaded in the WebView. `update` runs on every recomposition
    // (scroll, theme change, ...), so the HTML is (re)loaded only when it actually changed.
    // It starts as null so the first `update` right after the factory always performs the load.
    var lastLoadedHtml: String? = null

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // 核心：http/https 链接跳外部浏览器，其余 scheme 一律拦截
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (url == null) return false
                        // 只放行 http/https：intent://、file://、自定义 scheme 等一律拦下，
                        // 否则渲染的 HTML 可以借 startActivity 拉起任意组件。
                        if (!url.startsWith("http://", ignoreCase = true) &&
                            !url.startsWith("https://", ignoreCase = true)
                        ) {
                            return true // 告诉WebView自己不处理
                        }
                        // 普通网页链接都用外部浏览器打开
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        context.startActivity(intent)
                        return true // 告诉WebView自己不处理
                    }
                }

                settings.javaScriptEnabled = true // 让图片正常显示
                settings.domStorageEnabled = true

                // ==============================================
                // 👇 这三行 = 彻底禁止缩放（关键代码）
                settings.setSupportZoom(false)        // 不支持缩放
                settings.builtInZoomControls = false // 隐藏缩放控件
                settings.displayZoomControls = false // 不显示缩放
                // ==============================================

                // Keep the HTML surface transparent so it uses the Compose card
                // background in both light and dark system modes.
                setBackgroundColor(0x00000000)

                // The HTML is themed explicitly below. Leave WebView's automatic
                // inversion off so it does not invert the colors we inject.
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    settings.setAlgorithmicDarkeningAllowed(false)
                }
                isHorizontalScrollBarEnabled = false // 隐藏横向滚动条
                isVerticalScrollBarEnabled = false   // 隐藏滚动条
            }
        },
        update = { webView ->
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webView.settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                webView.settings.setAlgorithmicDarkeningAllowed(false)
            }
            // 加载 HTML（自动支持 img、样式、链接），仅在内容真的变化时重新加载
            if (lastLoadedHtml != themedHtml) {
                lastLoadedHtml = themedHtml
                webView.loadDataWithBaseURL(
                    null,
                    themedHtml,
                    "text/html; charset=UTF-8",
                    "UTF-8",
                    null
                )
            }
        },
        onRelease = { webView ->
            // Leaving composition (e.g. scrolling away in a LazyColumn): free the native
            // WebView instead of leaking it, then force a reload if it ever comes back.
            lastLoadedHtml = null
            webView.destroy()
        }
    )
}

private fun String.withDarkThemeStyles(): String {
    val textPrimary = YtpColors.TextPrimary.toCssHex()
    val textSecondary = YtpColors.TextSecondary.toCssHex()
    val accent = YtpColors.ThemePrimary.toCssHex()
    val surface = YtpColors.Surface.toCssHex()
    val container = YtpColors.PrimaryContainer.toCssHex()
    val border = YtpColors.Border.toCssHex()
    val style = """
        <style id="ytp-dark-theme">
            :root { color-scheme: dark; }
            html, body {
                background: transparent !important;
                color: $textPrimary !important;
            }
            html body * {
                color: $textPrimary !important;
                border-color: $border !important;
            }
            html body a,
            html body a * {
                color: $accent !important;
            }
            html body small,
            html body figcaption,
            html body blockquote {
                color: $textSecondary !important;
            }
            html body pre,
            html body code,
            html body kbd {
                background-color: $container !important;
                color: $textPrimary !important;
            }
            html body blockquote {
                border-left-color: $accent !important;
            }
            html body table,
            html body th,
            html body td,
            html body hr {
                border-color: $border !important;
            }
            html body th {
                background-color: $surface !important;
            }
        </style>
    """.trimIndent()
    val headEnd = indexOf("</head>", ignoreCase = true)
    return if (headEnd >= 0) {
        substring(0, headEnd) + style + substring(headEnd)
    } else {
        style + this
    }
}

private fun Color.toCssHex(): String =
    "#%06X".format(java.util.Locale.US, toArgb() and 0xFFFFFF)
