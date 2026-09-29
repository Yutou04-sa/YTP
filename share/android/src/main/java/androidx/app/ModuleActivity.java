/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package androidx.app;

import static org.ytp.share.Constants.CONFIG_EXTERNAL_MODULE;
import static org.ytp.share.Constants.LOWER_CASE_NAME;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.ytp.share.AppInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模块管理Activity界面
 * 用于展示和管理已安装的Xposed模块
 */
public class ModuleActivity extends Activity {

    private static final int REQUEST_CODE = 999;

    private static final String GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS";

    // 存储选中的应用列表
    private List<AppInfo> selectedAppList = new ArrayList<>();

    // 存储应用的选中状态，以package为键确保唯一性
    private Map<String, Boolean> appSelectionMap = new HashMap<>();

    // 缓存应用列表数据，搜索时直接从缓存过滤
    private List<AppInfo> cachedAppList = new ArrayList<>();

    // 添加主题色相关常量
    private static final int[] THEME_ATTRIBUTES = {
            android.R.attr.colorPrimary,
            android.R.attr.colorAccent,
            android.R.attr.textColorPrimary,
            android.R.attr.textColorSecondary,
            android.R.attr.colorBackground
    };

    // 初音色：模块管理界面固定使用这一套配色，不跟随宿主主题。
    private static final int MIKU_TEAL = 0xFF39C5BB;
    private static final int MIKU_TEAL_DARK = 0xFF1F9E96;
    private static final int MIKU_BACKGROUND_LIGHT = 0xFFF7FAFA;
    private static final int MIKU_BACKGROUND_DARK = 0xFF121212;
    private static final int MIKU_TEXT_PRIMARY_LIGHT = 0xFF1B1B1F;
    private static final int MIKU_TEXT_SECONDARY_LIGHT = 0xFF5F5F66;

    // 默认值只在宿主没有提供相应主题属性时使用。
    private int primaryColor = MIKU_TEAL;
    private int accentColor = MIKU_TEAL;
    private int textColorPrimary = MIKU_TEXT_PRIMARY_LIGHT;
    private int textColorSecondary = MIKU_TEXT_SECONDARY_LIGHT;
    private int backgroundColor = MIKU_BACKGROUND_LIGHT;
    private int surfaceColor;
    private int surfaceVariantColor;
    private int outlineColor;
    private int onAccentColor;

    // 定义View ID常量
    private static final int LOADING_CONTAINER_ID = View.generateViewId();
    private static final int SCROLL_VIEW_ID = View.generateViewId();

    private TextView selectionText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkSelfPermission();
        // 加载主题颜色
        loadThemeColors();
        applySystemBarColors();

        readConfig(getModuleConfigPath());

        // 显示模块管理界面
        showModuleManagementUI();
    }

    @SuppressLint("WrongConstant")
    private void checkSelfPermission() {
        try {
            //系统支持动态申请该权限
            PermissionInfo permissionInfo =  getPackageManager().getPermissionInfo(GET_INSTALLED_APPS, 0);
            if (permissionInfo!=null && checkSelfPermission(GET_INSTALLED_APPS) != PackageManager.PERMISSION_GRANTED) {
                //没有权限，需要申请
                requestPermissions( new String[]{GET_INSTALLED_APPS}, REQUEST_CODE);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshModuleList();
            } else {
                Toast.makeText(this, getString("grant_permission"), Toast.LENGTH_SHORT).show();
                //跳转到app权限设置页面
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName())));
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 添加刷新菜单项到右侧
        MenuItem refreshItem = menu.add(0, 1, 0, getString("refresh"));
        refreshItem.setIcon(android.R.drawable.ic_popup_sync);
        refreshItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // 处理返回按钮
                selectedAppList.clear();
                appSelectionMap.clear();
                finish();
                return true;
            case 1: // 刷新菜单项
                refreshModuleList();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * 刷新模块列表
     */
    private void refreshModuleList() {
        final LinearLayout loadingContainer = findViewById(LOADING_CONTAINER_ID); // 通过ID找到loading容器
        final ScrollView scrollView = findViewById(SCROLL_VIEW_ID); // 通过ID找到scrollView
        final LinearLayout listContainer = scrollView == null
                ? null : (LinearLayout) scrollView.getChildAt(0);

        if (loadingContainer != null && listContainer != null) {
            // 1. 显示加载提示（与初次加载完全一致）
            loadingContainer.setVisibility(View.VISIBLE);
            // 2. 隐藏列表（避免加载时显示旧数据）
            scrollView.setVisibility(View.GONE);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<AppInfo> refreshedData = loadAppListData(ModuleActivity.this);
                    runOnUiThread(() -> {
                        sortSelectedFirst(refreshedData);
                        cachedAppList = refreshedData;
                        // 3. 隐藏加载提示
                        loadingContainer.setVisibility(View.GONE);
                        // 4. 显示列表并填充新数据
                        scrollView.setVisibility(View.VISIBLE);
                        listContainer.removeAllViews(); // 清空旧数据
                        populateAppList(listContainer, refreshedData, scrollView);
                        if(!refreshedData.isEmpty()){
                            Toast.makeText(ModuleActivity.this, getString("refresh_success"), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }).start();
        }
    }

    // 从宿主主题读取颜色，并派生界面需要的表面色与对比色。
    @SuppressLint("ResourceType")
    private void loadThemeColors() {
        boolean nightMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        TypedArray colors = getTheme().obtainStyledAttributes(THEME_ATTRIBUTES);
        try {
            backgroundColor = usableColor(themeColor(colors, 4, backgroundColor), backgroundColor);
            primaryColor = usableColor(themeColor(colors, 0, primaryColor), primaryColor);
            accentColor = usableColor(themeColor(colors, 1, primaryColor), primaryColor);
            textColorPrimary = usableColor(themeColor(colors, 2, textColorPrimary), textColorPrimary);
            textColorSecondary = usableColor(themeColor(colors, 3, textColorSecondary), textColorSecondary);
        } finally {
            colors.recycle();
        }

        // 宿主主题只作参考：模块管理界面固定用初音色，否则会被宿主的蓝紫配色带跑。
        if (nightMode) {
            backgroundColor = MIKU_BACKGROUND_DARK;
            textColorPrimary = 0xFFF2F0F4;
            textColorSecondary = 0xFFB6B1BA;
        } else {
            backgroundColor = MIKU_BACKGROUND_LIGHT;
            textColorPrimary = MIKU_TEXT_PRIMARY_LIGHT;
            textColorSecondary = MIKU_TEXT_SECONDARY_LIGHT;
        }
        primaryColor = MIKU_TEAL;
        accentColor = MIKU_TEAL;

        boolean dark = isDark(backgroundColor);
        surfaceColor = blendColors(backgroundColor, dark ? Color.WHITE : textColorPrimary,
                dark ? 0.075f : 0.025f);
        surfaceVariantColor = blendColors(surfaceColor, accentColor, dark ? 0.14f : 0.09f);
        outlineColor = blendColors(backgroundColor, textColorSecondary, dark ? 0.42f : 0.25f);
        onAccentColor = contentColorFor(accentColor);
    }

    private int themeColor(TypedArray colors, int index, int fallback) {
        try {
            return colors.getColor(index, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private int usableColor(int color, int fallback) {
        if (Color.alpha(color) < 32) {
            return fallback;
        }
        return compositeColor(color, backgroundColor);
    }

    private void applySystemBarColors() {
        Window window = getWindow();
        window.setStatusBarColor(primaryColor);
        window.setNavigationBarColor(surfaceColor);
        int flags = window.getDecorView().getSystemUiVisibility();
        flags = setFlag(flags, View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, !isDark(primaryColor));
        flags = setFlag(flags, View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, !isDark(surfaceColor));
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int setFlag(int flags, int flag, boolean enabled) {
        return enabled ? flags | flag : flags & ~flag;
    }

    // 添加调整透明度的辅助方法
    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(255 * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    private int compositeColor(int foreground, int background) {
        float alpha = Color.alpha(foreground) / 255f;
        if (alpha >= 1f) return foreground;
        return Color.rgb(
                Math.round(Color.red(foreground) * alpha + Color.red(background) * (1f - alpha)),
                Math.round(Color.green(foreground) * alpha + Color.green(background) * (1f - alpha)),
                Math.round(Color.blue(foreground) * alpha + Color.blue(background) * (1f - alpha)));
    }

    private int blendColors(int base, int overlay, float amount) {
        return Color.rgb(
                Math.round(Color.red(base) + (Color.red(overlay) - Color.red(base)) * amount),
                Math.round(Color.green(base) + (Color.green(overlay) - Color.green(base)) * amount),
                Math.round(Color.blue(base) + (Color.blue(overlay) - Color.blue(base)) * amount));
    }

    private boolean isDark(int color) {
        return relativeLuminance(color) < 0.42f;
    }

    private int contentColorFor(int background) {
        return contrastRatio(Color.WHITE, background) >= contrastRatio(Color.BLACK, background)
                ? Color.WHITE : Color.BLACK;
    }

    private float contrastRatio(int first, int second) {
        float lighter = Math.max(relativeLuminance(first), relativeLuminance(second));
        float darker = Math.min(relativeLuminance(first), relativeLuminance(second));
        return (lighter + 0.05f) / (darker + 0.05f);
    }

    private float relativeLuminance(int color) {
        float r = linearize(Color.red(color) / 255f);
        float g = linearize(Color.green(color) / 255f);
        float b = linearize(Color.blue(color) / 255f);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private float linearize(float value) {
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    /**
     * 创建自定义工具栏
     */
    private LinearLayout createCustomToolbar() {
        int toolbarContentColor = contentColorFor(primaryColor);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(primaryColor);
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        toolbar.setLayoutParams(toolbarParams);
        toolbar.setElevation(dp(2));

        // 返回按钮
        ImageButton backButton = new ImageButton(this);
        backButton.setImageResource(android.R.drawable.ic_menu_revert);
        backButton.setBackground(createToolbarButtonBackground(toolbarContentColor));
        backButton.setColorFilter(toolbarContentColor);
        backButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        backButton.setContentDescription(getString("back"));
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        toolbar.addView(backButton, backParams);

        // 标题与当前选择数量
        LinearLayout titleContainer = new LinearLayout(this);
        titleContainer.setOrientation(LinearLayout.VERTICAL);
        titleContainer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleContainerParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        titleContainerParams.leftMargin = dp(4);
        toolbar.addView(titleContainer, titleContainerParams);

        TextView titleText = new TextView(this);
        titleText.setText(getString("module_management"));
        titleText.setTextColor(toolbarContentColor);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleText.setTypeface(null, Typeface.BOLD);
        titleContainer.addView(titleText);

        selectionText = new TextView(this);
        selectionText.setTextColor(adjustAlpha(toolbarContentColor, 0.72f));
        selectionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        titleContainer.addView(selectionText);
        updateSelectionSummary();

        // 刷新按钮
        ImageButton refreshButton = new ImageButton(this);
        refreshButton.setImageResource(android.R.drawable.ic_popup_sync);
        refreshButton.setBackground(createToolbarButtonBackground(toolbarContentColor));
        refreshButton.setColorFilter(toolbarContentColor);
        refreshButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        refreshButton.setContentDescription(getString("refresh"));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        toolbar.addView(refreshButton, refreshParams);

        // 点击事件
        backButton.setOnClickListener(v -> {
            selectedAppList.clear();
            appSelectionMap.clear();
            finish();
        });
        refreshButton.setOnClickListener(v -> refreshModuleList());

        return toolbar;
    }
    
    // 国际化文本获取方法
    private String getString(String key) {
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        
        // 根据系统语言返回对应文本，默认英文
        switch (key) {
            case "module_management":
                if ("zh".equals(language)) {
                    return "世界之门";
                }
                return "World Gate";
            case "search_hint":
                if ("zh".equals(language)) {
                    return "搜索应用名称或包名...";
                }
                return "Search app name or package...";
            case "loading":
                if ("zh".equals(language)) {
                    return "正在加载...";
                }
                return "Loading...";
            case "no_data":
                if ("zh".equals(language)) {
                    return "暂无数据";
                }
                return "No data";
            case "clear_all":
                if ("zh".equals(language)) {
                    return "清空全部";
                }
                return "Clear All";
            case "confirm_clear_title":
                if ("zh".equals(language)) {
                    return "清空模块";
                }
                return "Empty the module";
            case "confirm_clear_message":
                if ("zh".equals(language)) {
                    return "确定要清空所有已选择的模块吗？";
                }
                return "Are you sure you want to clear all selected module?";
            case "confirm_clear_restart":
                if ("zh".equals(language)) {
                    return "清空重启";
                }
                return "Empty & Restart";
            case "cancel":
                if ("zh".equals(language)) {
                    return "取消";
                }
                return "Cancel";
            case "save_and_restart":
                if ("zh".equals(language)) {
                    return "保存重启";
                }
                return "Save & Restart";
            case "restart_hint":
                if ("zh".equals(language)) {
                    return "已保存，重启应用后生效";
                }
                return "Saved. Restart the app to apply.";
            case "grant_permission":
                if ("zh".equals(language)) {
                    return "请授予获取应用列表权限";
                }
                return "Please grant permission to access the app list";
            case "refresh_success":
                if ("zh".equals(language)) {
                    return "刷新成功";
                }
                return "Refresh successful";
            case "refresh":
                if ("zh".equals(language)) {
                    return "刷新";
                }
                return "Refresh";
            case "back":
                if ("zh".equals(language)) {
                    return "返回";
                }
                return "Back";
            case "selected_count":
                if ("zh".equals(language)) {
                    return "已选择 %d 个模块";
                }
                return "%d modules selected";
            case "no_modules":
                if ("zh".equals(language)) {
                    return "没有Xposed模块";
                }
                return "No Xposed modules";
            case "app_name":
                if ("zh".equals(language)) {
                    return "名称";
                }
                return "Name";
            case "open_module":
                if ("zh".equals(language)) {
                    return "打开模块";
                }
                return "Open Module";
            case "module_info":
                if ("zh".equals(language)) {
                    return "模块信息";
                }
                return "Module Info";
            case "open_failed":
                if ("zh".equals(language)) {
                    return "打开失败";
                }
                return "Open failed";
            case "save_failed":
                if ("zh".equals(language)) {
                    return "保存配置失败，请重试";
                }
                return "Failed to save configuration, please try again";
            default:
                return key;
        }
    }
    
    private void showModuleManagementUI() {
        // 隐藏系统ActionBar（使用自定义工具栏）
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }

        // 所有背景色均从宿主主题推导，自动适配浅色、深色和动态配色主题。
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(backgroundColor);
        rootLayout.setFitsSystemWindows(true);

        // 自定义工具栏
        LinearLayout toolbar = createCustomToolbar();
        rootLayout.addView(toolbar);

        // 内容区域
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        contentLayout.setPadding(dp(16), dp(16), dp(16), dp(8));

        // 搜索栏
        final EditText searchEdit = new EditText(this);
        searchEdit.setHint(getString("search_hint"));
        searchEdit.setTextColor(textColorPrimary);
        searchEdit.setHintTextColor(textColorSecondary);
        searchEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchEdit.setSingleLine(true);
        searchEdit.setBackground(createRippleBackground(surfaceColor, outlineColor, 14));
        searchEdit.setPadding(dp(14), 0, dp(14), 0);
        searchEdit.setGravity(Gravity.CENTER_VERTICAL);
        Drawable searchIcon = getDrawable(android.R.drawable.ic_menu_search);
        if (searchIcon != null) {
            searchIcon = searchIcon.mutate();
            searchIcon.setTint(textColorSecondary);
            searchEdit.setCompoundDrawablesWithIntrinsicBounds(searchIcon, null, null, null);
            searchEdit.setCompoundDrawablePadding(dp(10));
        }
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        searchLp.bottomMargin = dp(14);
        contentLayout.addView(searchEdit, searchLp);

        // 加载提示区域
        final LinearLayout loadingContainer = new LinearLayout(this);
        loadingContainer.setOrientation(LinearLayout.VERTICAL);
        loadingContainer.setGravity(Gravity.CENTER);
        loadingContainer.setPadding(0, dp(64), 0, dp(64));
        loadingContainer.setVisibility(View.VISIBLE);
        loadingContainer.setId(LOADING_CONTAINER_ID);
        LinearLayout.LayoutParams loadingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentLayout.addView(loadingContainer, loadingLp);

        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(accentColor));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        progressLp.gravity = Gravity.CENTER;
        progressLp.bottomMargin = dp(12);
        loadingContainer.addView(progressBar, progressLp);

        TextView loadingText = new TextView(this);
        loadingText.setText(getString("loading"));
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        loadingText.setTextColor(textColorSecondary);
        loadingText.setGravity(Gravity.CENTER);
        loadingContainer.addView(loadingText);

        // 列表区域
        final ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setVisibility(View.GONE);
        scrollView.setId(SCROLL_VIEW_ID);
        contentLayout.addView(scrollView);

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, 0, 0, dp(8));
        scrollView.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部操作栏
        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setGravity(Gravity.CENTER_VERTICAL);
        buttonBar.setPadding(dp(16), dp(10), dp(16), dp(12));
        buttonBar.setBackgroundColor(surfaceColor);
        buttonBar.setElevation(dp(6));

        Button cancelButton = new Button(this);
        cancelButton.setText(getString("clear_all"));
        cancelButton.setTextColor(MIKU_TEAL_DARK);
        cancelButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancelButton.setTypeface(null, Typeface.BOLD);
        cancelButton.setAllCaps(false);
        cancelButton.setBackground(createRippleBackground(surfaceColor, MIKU_TEAL_DARK, 12));
        cancelButton.setPadding(dp(16), dp(4), dp(16), dp(4));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(48), 0.8f);
        cancelLp.rightMargin = dp(10);
        buttonBar.addView(cancelButton, cancelLp);

        Button confirmButton = new Button(this);
        confirmButton.setText(getString("save_and_restart"));
        confirmButton.setTextColor(onAccentColor);
        confirmButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        confirmButton.setTypeface(null, Typeface.BOLD);
        confirmButton.setAllCaps(false);
        confirmButton.setBackground(createRippleBackground(accentColor, Color.TRANSPARENT, 12));
        confirmButton.setPadding(dp(16), dp(4), dp(16), dp(4));
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, dp(48), 1.2f);
        buttonBar.addView(confirmButton, confirmLp);

        rootLayout.addView(contentLayout, contentParams);
        rootLayout.addView(buttonBar);
        setContentView(rootLayout);

        // 搜索监听
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(Locale.getDefault());
                List<AppInfo> filteredList = new ArrayList<>();
                for (AppInfo item : new ArrayList<>(cachedAppList)) {
                    String label = item.getLabel();
                    String packageName = item.getPackageName();
                    if (query.isEmpty()
                            || (label != null && label.toLowerCase(Locale.getDefault()).contains(query))
                            || (packageName != null && packageName.toLowerCase(Locale.ROOT).contains(query))) {
                        filteredList.add(item);
                    }
                }
                sortSelectedFirst(filteredList);
                populateAppList(listContainer, filteredList, scrollView);
            }
        });

        // 异步加载数据
        new Thread(() -> {
            final List<AppInfo> appItems = loadAppListData(ModuleActivity.this);
            runOnUiThread(() -> {
                sortSelectedFirst(appItems);
                cachedAppList = appItems;
                loadingContainer.setVisibility(View.GONE);
                scrollView.setVisibility(View.VISIBLE);
                populateAppList(listContainer, appItems, scrollView);
            });
        }).start();

        // 清空按钮
        cancelButton.setOnClickListener(v -> {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ModuleActivity.this);
            android.app.AlertDialog dialog = builder.setTitle(getString("confirm_clear_title"))
                    .setMessage(getString("confirm_clear_message"))
                    .setPositiveButton(getString("confirm_clear_restart"), (dg, which) -> {
                        selectedAppList.clear();
                        appSelectionMap.clear();
                        // 先保存配置，保存成功后再退出；保存失败则不退出，留在界面上提示用户
                        if (saveConfig()) {
                            Toast.makeText(ModuleActivity.this, getString("restart_hint"), Toast.LENGTH_LONG).show();
                            finish();
                        }
                    })
                    .setNegativeButton(getString("cancel"), null)
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(accentColor);
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(accentColor);
            });
            dialog.show();
        });

        confirmButton.setOnClickListener(v -> {
            // 先保存配置，保存成功后再退出；保存失败则不退出，留在界面上提示用户。
            // 这里不再 System.exit(0)：那会直接杀掉宿主进程；改为关闭本页并提示用户重启应用。
            if (saveConfig()) {
                Toast.makeText(ModuleActivity.this, getString("restart_hint"), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private boolean saveConfig(){
        StringBuilder sb = new StringBuilder();
        for (AppInfo appInfo : selectedAppList) {
            sb.append(appInfo.getPackageName()).append(",");
        }
        try {
            Path modulePath = getModuleConfigPath();
            if(Files.notExists(modulePath)){
                Files.createFile(modulePath);
            }
            // 保存选中的应用信息为字符串（固定 UTF-8，读取端同样按 UTF-8 解析）
            Files.write(modulePath, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            // 保存失败不再抛 RuntimeException 崩溃：记录日志并提示用户，保持在界面上
            Log.e("ModuleManager", "Failed to save module config: " + e);
            Toast.makeText(this, getString("save_failed"), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @SuppressLint("WrongConstant")
    private int dp(int px) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px, getResources().getDisplayMetrics());
    }

    private Drawable createShape(int color, int strokeColor, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        if (Color.alpha(strokeColor) > 0) {
            bg.setStroke(dp(1), strokeColor);
        }
        return bg;
    }

    private Drawable createRippleBackground(int color, int strokeColor, int radius) {
        Drawable content = createShape(color, strokeColor, radius);
        Drawable mask = createShape(Color.WHITE, Color.TRANSPARENT, radius);
        return new RippleDrawable(ColorStateList.valueOf(adjustAlpha(accentColor, 0.16f)),
                content, mask);
    }

    private Drawable createToolbarButtonBackground(int contentColor) {
        Drawable content = createShape(Color.TRANSPARENT, Color.TRANSPARENT, 24);
        Drawable mask = createShape(Color.WHITE, Color.TRANSPARENT, 24);
        return new RippleDrawable(ColorStateList.valueOf(adjustAlpha(contentColor, 0.18f)),
                content, mask);
    }

    private void updateCardAppearance(View card, boolean selected) {
        card.setBackground(createRippleBackground(
                selected ? surfaceVariantColor : surfaceColor,
                selected ? adjustAlpha(accentColor, 0.55f) : outlineColor,
                14));
    }

    private void updateCheckBoxColors(CheckBox checkBox) {
        int[][] states = {
                {android.R.attr.state_enabled, android.R.attr.state_checked},
                {android.R.attr.state_enabled},
                {-android.R.attr.state_enabled}
        };
        int[] colors = {
                accentColor,
                textColorSecondary,
                adjustAlpha(textColorSecondary, 0.38f)
        };
        checkBox.setButtonTintList(new ColorStateList(states, colors));
    }

    private void updateSelectionSummary() {
        if (selectionText == null) return;
        int count = 0;
        for (Boolean selected : appSelectionMap.values()) {
            if (Boolean.TRUE.equals(selected)) count++;
        }
        selectionText.setText(String.format(Locale.getDefault(), getString("selected_count"), count));
    }

    private void sortSelectedFirst(List<AppInfo> appItems) {
        appItems.sort((first, second) -> {
            boolean firstSelected = Boolean.TRUE.equals(
                    appSelectionMap.get(first.getPackageName()));
            boolean secondSelected = Boolean.TRUE.equals(
                    appSelectionMap.get(second.getPackageName()));
            if (firstSelected != secondSelected) {
                return firstSelected ? -1 : 1;
            }
            String firstLabel = first.getLabel() == null ? "" : first.getLabel();
            String secondLabel = second.getLabel() == null ? "" : second.getLabel();
            return firstLabel.compareToIgnoreCase(secondLabel);
        });
    }

    /* =========================================================
     * 填充App列表 - 卡片式设计
     * ======================================================= */
    @SuppressLint("SetTextI18n")
    private void populateAppList(LinearLayout container, List<AppInfo> appItems,
                                 final ScrollView scrollView) {
        container.removeAllViews();
        if (appItems.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setBackground(createRippleBackground(surfaceColor, outlineColor, 14));
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setPadding(dp(16), dp(56), dp(16), dp(56));
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyLp.bottomMargin = dp(8);
            container.addView(emptyCard, emptyLp);

            TextView emptyText = new TextView(this);
            emptyText.setText(getString("no_data"));
            emptyText.setTextColor(textColorSecondary);
            emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            emptyText.setGravity(Gravity.CENTER);
            emptyCard.addView(emptyText);
            return;
        }

        for (int i = 0; i < appItems.size(); i++) {
            final AppInfo item = appItems.get(i);

            // 紧凑的单卡片信息层级：图标、主要信息、选择状态。
            LinearLayout cardLayout = new LinearLayout(this);
            cardLayout.setOrientation(LinearLayout.VERTICAL);
            cardLayout.setPadding(dp(14), dp(12), dp(10), dp(12));
            cardLayout.setClickable(true);
            cardLayout.setFocusable(true);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(10);
            container.addView(cardLayout, cardLp);

            LinearLayout mainRow = new LinearLayout(this);
            mainRow.setOrientation(LinearLayout.HORIZONTAL);
            // 右侧区域比应用信息高，顶部对齐可避免名称看起来被 API 标签挤下去。
            mainRow.setGravity(Gravity.TOP);
            cardLayout.addView(mainRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView appIcon = new ImageView(this);
            appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            iconParams.rightMargin = dp(14);
            try {
                Drawable icon = getPackageManager().getApplicationIcon(item.getPackageName());
                appIcon.setImageDrawable(icon);
            } catch (Exception e) {
                appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            mainRow.addView(appIcon, iconParams);

            LinearLayout infoColumn = new LinearLayout(this);
            infoColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            infoLp.rightMargin = dp(8);
            mainRow.addView(infoColumn, infoLp);

            TextView nameText = new TextView(this);
            nameText.setText(item.getLabel() != null ? item.getLabel() : getString("app_name"));
            nameText.setTextColor(textColorPrimary);
            nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            nameText.setTypeface(null, Typeface.BOLD);
            nameText.setEllipsize(TextUtils.TruncateAt.END);
            nameText.setSingleLine(true);
            infoColumn.addView(nameText);

            String packageName = item.getPackageName() == null ? "" : item.getPackageName();
            String version = item.getVersionName();
            TextView packageText = new TextView(this);
            packageText.setText(packageName);
            packageText.setTextColor(textColorSecondary);
            packageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            packageText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            packageText.setSingleLine(true);
            LinearLayout.LayoutParams packageLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            packageLp.topMargin = dp(2);
            infoColumn.addView(packageText, packageLp);

            if (!TextUtils.isEmpty(version)) {
                TextView versionText = new TextView(this);
                versionText.setText(version);
                versionText.setTextColor(textColorSecondary);
                versionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                versionText.setSingleLine(true);
                LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                versionLp.topMargin = dp(1);
                infoColumn.addView(versionText, versionLp);
            }

            boolean hasApiLabel = !TextUtils.isEmpty(item.getTargetApiVersion());
            LinearLayout actionColumn = new LinearLayout(this);
            actionColumn.setOrientation(LinearLayout.VERTICAL);
            actionColumn.setGravity(Gravity.CENTER_HORIZONTAL);
            actionColumn.setMinimumWidth(dp(hasApiLabel ? 96 : 48));
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionLp.leftMargin = dp(4);
            mainRow.addView(actionColumn, actionLp);

            if (hasApiLabel) {
                TextView apiText = new TextView(this);
                apiText.setText(item.getTargetApiVersion());
                apiText.setTextColor(isDark(backgroundColor) ? MIKU_TEAL : MIKU_TEAL_DARK);
                apiText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                apiText.setTypeface(null, Typeface.BOLD);
                apiText.setSingleLine(true);
                apiText.setMinWidth(dp(88));
                apiText.setGravity(Gravity.CENTER);
                apiText.setPadding(dp(8), dp(3), dp(8), dp(3));
                apiText.setBackground(createShape(surfaceVariantColor, Color.TRANSPARENT, 10));
                LinearLayout.LayoutParams apiLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                actionColumn.addView(apiText, apiLp);
            }

            final CheckBox checkBox = new CheckBox(this);
            updateCheckBoxColors(checkBox);
            LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                    dp(40), dp(40));
            if (hasApiLabel) cbLp.topMargin = dp(4);
            checkBox.setGravity(Gravity.CENTER);

            Boolean isSelected = appSelectionMap.get(item.getPackageName());
            if (isSelected != null) checkBox.setChecked(isSelected);
            updateCardAppearance(cardLayout, checkBox.isChecked());
            if (item.isDisabled()) {
                checkBox.setVisibility(View.GONE);
            } else {
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    appSelectionMap.put(item.getPackageName(), isChecked);
                    if (isChecked) {
                        selectedAppList.removeIf(app -> app.getPackageName().equals(item.getPackageName()));
                        selectedAppList.add(item);
                    } else {
                        selectedAppList.removeIf(app -> app.getPackageName().equals(item.getPackageName()));
                    }
                    updateCardAppearance(cardLayout, isChecked);
                    updateSelectionSummary();
                });
                cardLayout.setOnClickListener(v -> checkBox.toggle());
            }
            actionColumn.addView(checkBox, cbLp);

            // 描述独占一行并按内容完整换行，不再被右侧标签和复选框挤压。
            if (!TextUtils.isEmpty(item.getDescription()) && !"null".equals(item.getDescription())) {
                TextView descText = new TextView(this);
                descText.setText(item.getDescription());
                descText.setTextColor(textColorSecondary);
                descText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                descText.setLineSpacing(dp(2), 1f);
                descText.setGravity(Gravity.START);
                descText.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                descLp.rightMargin = dp(4);
                descLp.topMargin = dp(8);
                cardLayout.addView(descText, descLp);
            }

            // 长按菜单
            cardLayout.setOnLongClickListener(v -> {
                showItemMenu(v, item.getPackageName(), item.getLabel());
                return true;
            });
        }
        scrollView.post(() -> scrollView.scrollTo(0, 0));
    }

    private void readConfig(Path moduleConfigPath){
        if(Files.exists(moduleConfigPath)){
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(moduleConfigPath);
            } catch (IOException e) {
                // 读不到配置只影响预勾选状态：绝不能抛出去（本方法由 onCreate 调用，
                // 抛出会连坐宿主进程一起崩溃）。
                Log.e("ModuleManager", "Cannot read module config " + moduleConfigPath, e);
                Toast.makeText(this, "无法读取模块配置：" + moduleConfigPath, Toast.LENGTH_LONG).show();
                return;
            }
            if(bytes == null || bytes.length == 0) return;
            String config = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!config.isEmpty() && config.charAt(0) == '\uFEFF') config = config.substring(1);
            String[] split = config.split(",");
            for (String packageName : split) {
                packageName = packageName.trim();
                if(packageName.isEmpty()) continue;
                AppInfo appInfo = new AppInfo(packageName,packageName);
                appSelectionMap.put(appInfo.getPackageName(), true);
                selectedAppList.add(appInfo);
            }
        }
    }
    
    /**
     * 加载应用列表数据
     */
    public static List<AppInfo> loadAppListData(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        @SuppressLint("QueryPermissionsNeeded")
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.ConcurrentLinkedQueue<AppInfo> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(apps.size());

        for (ApplicationInfo appInfo : apps) {
            executor.submit(() -> {
                try {
                    if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return;
                    String label = appInfo.loadLabel(pm).toString();
                    AppInfo xposedModule = isXposedModule(appInfo, pm);
                    if (xposedModule != null) {
                        xposedModule.setPackageName(appInfo.packageName);
                        xposedModule.setLabel(label);
                        queue.add(xposedModule);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try { latch.await(); } catch (InterruptedException ignored) {}
        executor.shutdown();

        List<AppInfo> appItems = new ArrayList<>(queue);
        appItems.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
        return appItems;
    }

    private static AppInfo isXposedModule(ApplicationInfo appInfo,PackageManager pm){
        AppInfo module = null;
        try(ZipFile zipFile = new ZipFile(appInfo.sourceDir)) {
            ZipEntry entry = zipFile.getEntry("META-INF/xposed/module.prop");
            if(entry != null) {
                InputStream is = zipFile.getInputStream(entry);
                Properties prop = new Properties();
                prop.load(is);
                module = new AppInfo();
                module.setDescription(appInfo.loadDescription(pm)+"");
                module.setVersionName(getVersionName(appInfo.packageName,pm));
                module.setTargetApiVersion(prop.getProperty("targetApiVersion"));
                module.setDisabled("100".equals(module.getTargetApiVersion()));
            }
        } catch (Exception e) {
            Log.e("ModuleManager", "Failed to read app list data: " + e);
        }
        if(appInfo.metaData!=null && (appInfo.metaData.containsKey("xposedmodule") || appInfo.metaData.containsKey("xposeddescription") || appInfo.metaData.containsKey("xposedminversion") || appInfo.metaData.containsKey("xposedscope"))){
            if(module!=null){
                module.setTargetApiVersion("legacy"+" / "+module.getTargetApiVersion());
                module.setDisabled(false);
                return module;
            }
            module = new AppInfo();
            module.setDescription(appInfo.metaData.getString("xposeddescription"));
            module.setVersionName(getVersionName(appInfo.packageName,pm));
            module.setTargetApiVersion("legacy");
            return module;
        }
        return module;
    }

    private static String getVersionName(String packageName,PackageManager pm) {
        try {
            return pm.getPackageInfo(packageName, PackageManager.GET_META_DATA).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 显示列表项的长按菜单
     */
    private void showItemMenu(View anchorView, String packageName, String appName) {
        PopupMenu popupMenu = new PopupMenu(this, anchorView, Gravity.END);
        
        // 添加菜单项
        popupMenu.getMenu().add(Menu.NONE, 1, Menu.NONE, getString("open_module"));
        popupMenu.getMenu().add(Menu.NONE, 2, Menu.NONE, getString("module_info"));
        
        // 设置菜单项点击事件
        popupMenu.setOnMenuItemClickListener(item -> switch (item.getItemId()) {
            case 1 -> {
                openModule(packageName);
                yield true;
            }
            case 2 -> {
                openAppSettings(packageName);
                yield true;
            }
            default -> false;
        });
        
        popupMenu.show();
    }

    /**
     * 打开模块应用
     */
    private void openModule(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, getString("open_failed"), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString("open_failed"), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 打开应用设置页面
     */
    private void openAppSettings(String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString("open_failed"), Toast.LENGTH_SHORT).show();
        }
    }

    private Path getModuleConfigPath() {
        File externalFilesDir = getExternalFilesDir(null);
        if (externalFilesDir == null) {
            externalFilesDir = getFilesDir();
        }
        return Paths.get(externalFilesDir.getAbsolutePath(), LOWER_CASE_NAME).resolve(CONFIG_EXTERNAL_MODULE);
    }
}
