package org.ytp.ui.page

import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.ytp.R
import org.ytp.ui.theme.YtpColors

private val credits = listOf(
    "HKP / HkPatch" to "https://github.com/wyx176/HKP",
    "LSPatch (JM)" to "https://github.com/JingMatrix/LSPatch",
    "LSPatch" to "https://github.com/LSPosed/LSPatch",
    "Vector" to "https://github.com/JingMatrix/Vector",
    "LSPosed (JM)" to "https://github.com/JingMatrix/LSPosed",
    "LSPosed" to "https://github.com/LSPosed/LSPosed",
    "XPatch" to "https://github.com/WindySha/Xpatch",
    "libXposed" to "https://github.com/libxposed/api"
)

@Destination
@Composable
fun AboutScreen(navigator: DestinationsNavigator) {
    Scaffold(
        containerColor = YtpColors.PageContainer
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AboutHero(
                onBackClick = navigator::popBackStack
            )

            HookLogCard()

            ModuleManagerCard()

            CreditsCard()

            LicenseCard()
        }
    }
}

@Composable
private fun AboutHero(
    onBackClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onBackClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                modifier = Modifier.size(22.dp),
                tint = YtpColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = stringResource(R.string.settings_about),
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = YtpColors.TextPrimary
        )
    }
}

@Composable
private fun HookLogCard() {
    val clipboard = LocalClipboardManager.current
    val path = "${Environment.getExternalStorageDirectory()}${
        stringResource(R.string.about_hook_description)
    }"

    AboutInfoCard(
        icon = Icons.Outlined.Folder,
        iconColor = YtpColors.Primary,
        title = stringResource(R.string.about_hook_title),
        description = path,
        monospace = true,
        trailing = {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(YtpColors.iconBackground(YtpColors.Primary, 0.08f))
                    .border(1.dp, YtpColors.IconButtonBorder, CircleShape)
                    .clickable {
                        clipboard.setText(AnnotatedString(path))
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = YtpColors.iconForeground(YtpColors.Primary)
                )
            }
        }
    )
}

@Composable
private fun ModuleManagerCard() {
    AboutInfoCard(
        icon = Icons.Outlined.Apps,
        iconColor = YtpColors.Success,
        title = stringResource(R.string.about_module_manager_title),
        description = stringResource(R.string.about_module_manager_description)
    )
}

@Composable
private fun AboutInfoCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    monospace: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                spotColor = iconColor.copy(alpha = 0.06f)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = YtpColors.Surface
        ),
        border = BorderStroke(
            1.dp,
            YtpColors.Border
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            iconColor.copy(alpha = 0.035f),
                            YtpColors.Surface
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        YtpColors.iconBackground(iconColor, 0.13f),
                                        YtpColors.iconBackground(iconColor, 0.06f)
                                    )
                                )
                            )
                            .border(
                                1.dp,
                                YtpColors.IconButtonBorder,
                                RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(25.dp),
                            tint = YtpColors.iconForeground(iconColor)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = YtpColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    if (trailing != null) {
                        trailing()
                    }
                }

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (monospace) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    color = YtpColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CreditsCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = YtpColors.Shadow.copy(alpha = 0.025f),
                spotColor = YtpColors.AccentPurple.copy(alpha = 0.06f)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = YtpColors.Surface
        ),
        border = BorderStroke(
            1.dp,
            YtpColors.Border
        )
    ) {
        Column {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                                Brush.linearGradient(
                                    listOf(
                                        YtpColors.iconBackground(YtpColors.AccentPurple, 0.14f),
                                        YtpColors.iconBackground(YtpColors.AccentPurple, 0.07f)
                                    )
                                )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(25.dp),
                        tint = YtpColors.iconForeground(YtpColors.AccentPurple)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_thanks),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = YtpColors.TextPrimary
                    )
                }
            }

            credits.forEachIndexed { index, (name, url) ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            start = 14.dp,
                            end = 14.dp
                        ),
                        thickness = 1.dp,
                        color = YtpColors.Divider
                    )
                }

                CreditItem(
                    projectName = name,
                    projectUrl = url
                )
            }
        }
    }
}

@Composable
private fun LicenseCard() {
    AboutInfoCard(
        icon = Icons.Outlined.Description,
        iconColor = YtpColors.AccentPurple,
        title = stringResource(R.string.about_license_title),
        description = stringResource(R.string.about_license_description)
    )
}

@Composable
private fun CreditItem(
    projectName: String,
    projectUrl: String
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        projectUrl.toUri()
                    )
                )
            }
            .padding(
                horizontal = 14.dp,
                vertical = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = projectName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = YtpColors.TextPrimary
            )

            Text(
                text = projectUrl,
                style = MaterialTheme.typography.bodySmall,
                color = YtpColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(YtpColors.iconBackground(YtpColors.Primary, 0.07f))
                .border(1.dp, YtpColors.IconButtonBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = YtpColors.iconForeground(YtpColors.Primary)
            )
        }
    }
}
