# YTP 框架

[![安卓 CI](https://github.com/Yutou04-sa/YTP/actions/workflows/android.yml/badge.svg)](https://github.com/Yutou04-sa/YTP/actions/workflows/android.yml) [![Download](https://img.shields.io/github/v/release/Yutou04-sa/YTP?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/Yutou04-sa/YTP/releases/latest) [![Total](https://shields.io/github/downloads/Yutou04-sa/YTP/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/Yutou04-sa/YTP/releases)

## 简介

未 root（免 Root）设备上的 LSPosed 框架实现：把 dex 与 so 注入目标 APK，从而为目标应用提供 Xposed API。

## 支持的版本

- 最低：Android 8.1
- 最高：理论上与 [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed#supported-versions) 相同

## 下载

稳定版请到 [GitHub Releases](https://github.com/Yutou04-sa/YTP/releases) 下载。

## 使用方法

+ 通过管理器

1. 在 Android 设备上安装 `YTP-v2.2.0-release.apk`
2. 按管理器内的提示操作

- 设备相关命令

  ```powershell
  adb devices
  ```

  ```powershell
  adb install .\out\release\YTP-v2.2.0-release.apk
  ```

+ 从源码构建

  ```powershell
  ./gradlew :manager:buildRelease
  ```

  APK 输出到 `manager/build/outputs/apk/release/`，并会复制一份到 `out/release/`。
  本项目所需的全部内容都在这个仓库里 —— **没有任何 git 子模块**，原先的 `core` 子模块
  （LSPlant / Dobby / axml 等）已作为普通源码摊平在 `core/` 下。

  签名是可选的：签名材料从 `manager/signing/signing.properties` 读取
  （`KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）；复制
  `manager/signing/signing.properties.example` 并把 `KEYSTORE_FILE` 指向你自己的密钥库即可。
  该文件已被 git 忽略，所以任何人克隆本仓库都不会拿到别人的私钥。没有这个文件时，release APK
  就以未签名状态产出（`manager-release-unsigned.apk`，自行签名），debug 构建则回退到 Android
  标准调试密钥库 —— 全新克隆一定能构建成功。

  构建环境要求：JDK 21（`JAVA_HOME`）、Android SDK（需 **platform 37**、**build-tools 37.0.0**
  与 **NDK 29.0.13113456**，见 `build.gradle.kts` 里的 `ndkVersion`；patch loader 的 JNI 部分用
  CMake 构建），以及 `local.properties` 中的 `sdk.dir`。Gradle 包装器锁定 Gradle 9.4.1，首次
  构建会自动下载所需工具链。

  默认会构建框架支持的全部 ABI（`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`），因为打补丁时会
  把这些库拷进目标应用。若只需要测试设备所用的那一种，可传 `-PytpAbis=arm64-v8a`（多种用逗号
  分隔）—— 原生构建耗时随 ABI 数量增长。

  GitHub Actions（`.github/workflows/android.yml`）会在每次 push 时构建同样的 release APK，并在
  推送 `v*` 标签时把 APK 附到以标签为版本号的 Release 上（`git tag v2.2.0 && git push <你的远端> v2.2.0`）；
  标签只是发布标识，可以和 `gradle/version.properties` 里的 `verName` 不一致（不一致时工作流只给出
  警告，并把包内实际版本号写进 Release 说明）。若仓库配置了
  `SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_PASSWORD` 三个 secret，工作流
  会为 APK 签名；fork 之后没有这些 secret 也能正常构建并发布未签名 APK。把 `SIGNING_CERT_SHA256`
  设为你的签名证书 SHA-256（即 `apksigner verify --print-certs` 输出里的 `certificate SHA-256
  digest` 那一行 —— build-tools 37 会写成 `V3.0 Signer: certificate SHA-256 digest: …`；小写
  十六进制、不含冒号），一旦签名密钥不符，构建会直接失败而不是发布一个被别人签名的 APK ——
  `apksigner verify --print-certs out/release/YTP-*.apk` 可查看当前包的实际摘要。

  发布的 APK 还会带上 `manager/sounds/` 里那批**内置提示音素材**：这些素材不在本仓库里（见下文与
  `.gitignore`），而是存在私有仓库 `Yutou04-sa/YTP-sounds`。工作流用一把**只读部署密钥**（secret
  `SOUNDS_DEPLOY_KEY`，内容是那把密钥的私钥）把素材取回来参与构建，于是「发布的 APK 带素材、源码
  仓库里一个音频文件都没有」。没有配置这个 secret（例如别人 fork 之后）时只给出警告并跳过，构建
  照常完成，只是 APK 里没有这批内置素材。

## 本仓库的改动

+ 打补丁**之前**会先把原始 APK 复制到管理器的私有存储。
+ 之后随时可以在 **原始安装包** 页面把它们装回去（安装 / 卸载 / 删除，单 APK 与 split APK 应用
  都支持）；列表会显示备份的版本、大小与时间。
+ **已打补丁的应用** 页面列出已打过补丁的应用，并按补丁来源标注：`assets/ytp/config.json` 是本
  管理器的补丁，`assets/hkp/config.json` 是改名成 YTP 之前的旧品牌（HkPatch/HKP）打的，
  `assets/lspatch/config.json` 来自上游 LSPatch，`assets/npatch/config.json` 来自 NPatch
  （0.7.2 及更早的 NPatch 写的是 LSPatch 那套路径，因此只能算 LSPatch）。
+ 每张卡片都能**重新修补**：有备份的基于未被改动过的原始 APK 重新生成补丁包；LSPatch / NPatch
  的补丁包里内嵌着原包，会先自动把它提取出来登记成备份再重打；旧品牌 HKP 的补丁包（同一套框架，
  只差资源目录与 so 名）可以直接就地换成 YTP 的补丁。LSPatch / NPatch 的条目还多一个**还原原包**
  菜单项，用来把内嵌的、开发者原签名的 APK 存成本管理器的备份；已有备份的条目则能**导出原包**
  到你选择的目录。YTP / HKP 的补丁是就地改写 dex、包里不带原包副本，所以没有可还原的内嵌原包；
  这种情况下条目上会多一个**指定原包文件**菜单项，让你自己挑一份未修补的原始 APK 登记成备份
  （文件本身带补丁标记会被拒绝），之后就能照常重新修补、导出原包、装回原版。
+ **安装前会先检查设备上的情况**：没装过就直接安装；装过且签名一致就直接覆盖安装（与系统原本的
  升级路径一致）；签名不一致时系统会拒绝覆盖，这时弹确认框，选“强制安装”会先卸载已安装的版本
  再安装（原应用数据会丢失）。设置页里的**强制安装**开关可以让这一步不再询问。
+ 设置页的**权限与访问**页汇总本应用用到的敏感权限（应用列表、安装未知应用、所有文件访问、
  存储），逐项显示是否已就绪，并可直接跳到对应的系统授权页面。
+ 修补开始与修补完成时可以各播一声**提示音**（默认开启，可在设置页关掉）。**提示音库**页可以
  试听并挑选音源，也能通过系统文件选择器添加自己**截取**的音频片段（选起止时间），内置提示音
  可以逐条删除并随时恢复。仓库里只带两个极短的自制 wav（`manager/src/main/res/raw/`）；额外的
  音效素材放在 `manager/sounds/`，该目录被 `.gitignore` 排除：它们是从第三方音源（表情包、语音包
  一类）收集来的，版权不在本项目的 GPL-3.0 授权覆盖范围内，所以**不随源码发布**，只存在私有仓库
  `Yutou04-sa/YTP-sounds`。本地构建时把这个目录放好即可打进 APK；发布构建则由工作流用只读部署密钥
  取回素材（见上文），于是发布出来的 APK 里带这些内置音效，而源码仓库里没有任何音频文件。
+ 列表在每次页面恢复（resume）时自动刷新，因此没有刷新按钮。
+ 多 APK（split）应用通过同一个 `PackageInstaller` 会话安装，不会再出现只装上一部分的情况。
+ 模块仓库入口放在底部导航栏；原先的“工具和资源”分组（含随机包名功能）已移除。
+ 打补丁时可以**重命名应用**：换一个新包名（这样补丁包会与原应用并存、作为独立应用安装）以及
  一个新的显示名。manifest 中的相对组件名会用原包名补全，因为 dex 不会被重写；authorities 与
  权限则有意保持不动。请注意那些在运行时从 `Context.getPackageName()` 推导 provider authority
  的应用：这类组件可能仍持有旧 authority，而代码却按新包名去请求。dex 从不重写，因此编译期常量
  （`BuildConfig.APPLICATION_ID`）仍保持原包名。
+ 重命名后的补丁包仍能与它的原始备份对应 —— 新包名记在备份的 `meta.properties` 里，所以重新打
  补丁和“导出原始包”都还能正常工作。
+ 补丁应用在启动器长按菜单里新增的入口及其背后的页面都叫 **World Gate**（世界之门）：快捷方式由
  `patch-loader/src/main/java/org/ytp/loader/util/ShortcutUtils.java` 注册，页面是
  `share/android/src/main/java/androidx/app/ModuleActivity.java`；管理器自己的页面叫
  **模块** 与 **应用**。它的图标是用 `Bitmap`/`Canvas` 在运行时画出来的一扇门（loader 无法自带
  资源），并且 `ShortcutUtils.SHORTCUT_VERSION` 会在改动后递增，使已打过补丁的应用在下次启动时
  重写快捷方式，而不是继续用旧图标。
+ 主题默认是初音绿（`0xFF39C5BB`）。已有安装会迁移一次，之后保持设置里调色板所选的颜色。本仓库
  新增的图标全部来自 Material 图标集，因此没有捆绑任何第三方美术资源。
+ 设置页以 **主题** 分组开头：调色板、会被复制进管理器私有存储的**背景图片**，以及**小组件
  不透明度**滑块。图片位于所有页面之下，卡片、顶栏与底栏按所选比例半透明绘制（默认 80 %，所以
  背景图能透出来）—— 调到 100 % 则完全不透明，文字保持原有对比度。
+ 主页以整行 NP 风格列出 **模块** 与 **应用**（圆角图标块、名称、箭头）—— 图标块用 Material
  图标（`Icons.Outlined.Extension` 与 `Icons.Outlined.Apps`）。**+** 打补丁按钮仍是默认的右下
  角悬浮按钮。启动器图标沿用上游美术资源（LSPosed 风格，GPL-3.0），只是把绿色换成了初音绿；
  被补丁应用内的模块管理器使用同一套配色。
+ 底栏是浮动的**液态玻璃**药丸形：半透明渐变、边缘高光与斜向光泽，随选中项滑动的弹簧高亮透镜，
  以及柔和阴影。设置背景图后玻璃会跟随**小组件不透明度**滑块，让壁纸透出来；没有背景图时它几乎
  不透明。
+ 内置的补丁签名密钥库复用了维护者的发布密钥，因此补丁应用与管理器使用同一张证书签名。该密钥库
  就**打包在** APK 里（`manager/src/main/assets/keystore`，BKS，别名 `鱼子`，仓库/密钥口令
  `123456`），所以请把它当作公开密钥：发布你自己的构建前，请生成自己的密钥库并替换该资源。
+ 主页的社区卡片会打开维护者的 Telegram 频道（[@Yutou_v50](https://t.me/Yutou_v50)，
  `HomeScreen.kt` → `CommunityAction`）；如果你要再分发这个构建，请把它改成你自己的频道。
+ **应用内更新检查已禁用**：`UpdateChecker.UPDATE_URL` 为空，所以管理器启动时不发任何更新请求、
  也永远不会弹更新对话框。填入你自己的 `update2` 地址即可重新启用（该格式的生成工具已从本仓库移除，
  你可以按需自建）—— 不要指向上游端点，它的下载包是用另一把密钥签名的。

## 来源与许可

本项目是 **LSPatch 的二次修改版本**（经由 HKP），整体以 **GPL-3.0** 分发。来源链如下 —— 每一层
的措辞都取自上游自己的 README 与仓库信息（2026-09-30 通过 GitHub API 逐项核对），不是推测：

| 层级 | 项目 | 在本项目中的角色 | 许可证 |
| --- | --- | --- | --- |
| 基础框架 | [LSPosed](https://github.com/LSPosed/LSPosed) / [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed)（即 **Vector** 一脉） | ART hook 框架与 Xposed API 桥。摊平在 `core/` 下的就是它：`core/README.md` 至今仍写着 “LSPosed Framework”，其徽章指向 `JingMatrix/LSPosed` | GPL-3.0 |
| 免 Root 补丁的起点 | [Xpatch](https://github.com/WindySha/Xpatch)（WindySha） | 把框架重打包进目标 APK 的最初做法。LSPatch 的 README 在 Credits 里把它列为 “**Fork source**” | Apache-2.0 |
| 直接前身 | [LSPatch](https://github.com/LSPosed/LSPatch)（LSPosed 组织，仓库**已归档**，末次提交 2023-12-13）/ [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch)（活跃分支，HKP 跟随的就是它） | 免 Root 补丁方案：补丁管线与管理器骨架。原版 README 的原话是 “Rootless implementation of LSPosed framework, integrating Xposed API by inserting dex and so into the target APK”，其 Credits 写的是 “LSPosed: Core framework”“Xpatch: Fork source” | GPL-3.0 |
| 直接上游 | HKP / HkPatch（原作者 wyx176） | 本仓库直接 fork 的源码树：`patch/`、`patch-loader/`、`meta-loader/`、`share/`、`manager/`、`core/` 都由它摊平而来（上游包名 `org.hkp`）。其仓库 <https://github.com/wyx176/HKP> 现已不再公开（2026-09-30 查询返回 404），因此**本仓库就是这批代码的公开源码** | GPL-3.0 |
| 本仓库 | YTP | 在上面这棵树上的二次修改（包名改为 `org.ytp`、`core` 摊平、管理器与补丁管线大量改动，见「本仓库的改动」） | GPL-3.0 |

**修改声明（GPL-3.0 第 5(a) 条）**：本仓库是一个**被修改过的版本**，不是上游原版。修改自
2026-09-29 起由 YTP 维护者进行，内容包括但不限于：包名与资源名重命名（`org.hkp` →
`org.ytp`、HKP → YTP）、把 `core` 子模块摊平为普通源码、管理器界面与交互重写、补丁管线的
健壮性修复、原生/JNI 层加固、版本号统一为 `gradle/version.properties` 单一来源。修改内容与
日期见本 README 的「本仓库的改动」以及仓库的 git 提交历史。

上游代码的版权归其各自作者所有；本仓库保留上游的许可证文本、文件头与声明（`LICENSE`、
`core/LICENSE`、`core/external/*` 各自的许可证）。完整第三方组件清单见 [`NOTICE`](NOTICE)。

## 权限说明

管理器是一个打补丁的工具，它的 manifest 对此很坦率：

- `MANAGE_EXTERNAL_STORAGE`（以及 API 32 及以下的 `READ_EXTERNAL_STORAGE`、API 28 及以下的
  `WRITE_EXTERNAL_STORAGE`）—— 读取你选择的 APK，并把补丁包或导出的备份写到指定位置。在系统
  支持的情况下会改用“选择文件夹”（SAF）流程，只获得该目录的访问权。
- `QUERY_ALL_PACKAGES`（配合 `com.android.permission.GET_INSTALLED_APPS`）—— 列出已安装应用，
  供你选择要打补丁的目标，并判断某个模块或补丁应用是否仍处于安装状态。
- `REQUEST_INSTALL_PACKAGES`、`REQUEST_DELETE_PACKAGES` —— 把补丁包交给系统安装器，以及通过同一
  个 `PackageInstaller` 会话卸载/还原。
- `INTERNET` —— 加载模块仓库列表与模块详情。
- `GET_ACCOUNTS`、`GET_TASKS`、`KILL_BACKGROUND_PROCESSES` —— 继承自上游，本仓库的代码中
  没有任何地方使用。

没有定位、相机、麦克风、通讯录、电话或广告相关权限，没有分析统计也没有遥测；应用内更新检查
已禁用（见上文）。

## 已知限制

- **已打过补丁的应用会一直用打补丁时的那份框架。** `core.so`、`libytp.so` 与 loader dex 是在
  打补丁时拷进目标 APK 的，因此之后在管理器里做的任何改动都不会到达已经打过补丁的应用 —— 需要
  重新打一次补丁（**已打补丁的应用** 页面会基于备份重建）才能拿到修复。
- 框架库只会被加入构建时支持的 ABI（`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`）；没有对应
  库的 ABI 会被跳过而不是让打补丁失败，该 ABI 下则以未打补丁状态运行。
- **免 Root 方案下，模块应用无法从它自己的界面里“激活”**：框架只在被补丁的宿主应用进程内运行，
  没有回到模块自身进程的服务通道。因此那些在自己应用里检查“我是否已激活 / 你是否同意协议”的
  模块会拒绝 hook —— 例如 `com.ss.android.ugc.aweme.yyds` 会一直记录 `未同意使用协议` 而不做
  任何事，尽管框架确实把它加载进了宿主（`Loaded external module: …`）。这类模块需要已 root 的
  Xposed/LSPosed 环境。
- 重命名补丁会保留原有 authorities 与编译期常量 —— 见上文的重命名说明。

## 仓库结构

- `manager/` —— 管理器应用（Kotlin、Jetpack Compose）。
- `patch/`、`patch-loader/`、`meta-loader/`、`share/` —— 补丁管线，以及被注入目标 APK 的代码。
- `core/` —— 摊平的免 Root Xposed 核心（LSPlant、Dobby、axml、hiddenapi 桥等），以普通源码形式
  随仓库提供，并带有自己的 GPL-3.0 `LICENSE`；整棵树中**没有任何 git 子模块**。
- `out/`、`build/` —— 构建产物，已被 git 忽略。

## 致谢

- HKP / HkPatch（原作者 wyx176）：本仓库直接 fork 的 GPL-3.0 上游（YTP 的补丁管线与摊平的 `core/` 均来自它）；仓库 <https://github.com/wyx176/HKP> 现已不再公开
- [LSPatch](https://github.com/LSPosed/LSPatch)（已归档）/ [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch)（活跃分支）：免 Root 补丁方案与补丁管线的直接前身
- [LSPosed](https://github.com/LSPosed/LSPosed) / [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed)（即 Vector）：`core/` 里的框架核心
- [Xpatch](https://github.com/WindySha/Xpatch)（Apache-2.0）：LSPatch 的 “Fork source”，免 Root 补丁方案的起点
- [Apkzlib](https://android.googlesource.com/platform/tools/apkzlib)：重打包工具
- [MT Manager](https://mt2.cn)：`patch/src/main/java/bin/{mt,zip,io}` 反编译自它 —— 原地 APK v2/v3 签名与“原包复用”所依赖的 ZIP 层（未找到上游许可证，见 [`NOTICE`](NOTICE)）
- [ManifestEditor](https://github.com/WindySha/ManifestEditor)：`core/external/axml` 下的 [axml](https://github.com/Sable/axml) 编辑器（未找到上游许可证，见 [`NOTICE`](NOTICE)）

## 第三方声明

本项目源码树与发布的 APK 中都包含他人的成果：源自 LSPosed 的免 Root Xposed 核心（GPL-3.0）、
源自 LSPatch / Xpatch 的补丁方案（GPL-3.0 / Apache-2.0）、`core/external` 下摊平的各个项目
（LGPL-3.0、Apache-2.0、MIT）、Maven 依赖（Apache-2.0 / BSD-3-Clause），以及
`patch/src/main/java/bin/` 下反编译自 MT Manager 的代码（未能找到其上游许可证，保留的原因是
补丁管线依赖它的原地 APK 签名与原包数据复用）。每一部分是什么、依据什么条款，都列在
[`NOTICE`](NOTICE) 中。

## 许可证

YTP 以 **GNU General Public License v3（GPL-3）** 授权，完整条款见
[GNU General Public License v3](http://www.gnu.org/copyleft/gpl.html)。
