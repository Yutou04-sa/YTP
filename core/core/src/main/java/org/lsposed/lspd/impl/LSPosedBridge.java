package org.lsposed.lspd.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lsposed.lspd.nativebridge.HookBridge;
import org.lsposed.lspd.util.Utils.Log;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.HookFailedError;

/**
 * LSPosed 框架的核心桥接类，连接 Xposed API 与底层原生 Hook 实现。
 *
 * 该类提供了 Xposed API 中所有核心接口的具体实现，包括：
 * - {@link ChainImpl}：方法调用链的实现，支持钩子链式调用和异常模式处理
 * - {@link HookHandleImpl}：钩子句柄，支持取消注册
 * - {@link HookBuilderImpl}：钩子构建器，支持设置优先级和异常模式
 * - {@link MethodInvoker}：方法调用器，支持原始调用和带 maxPriority 过滤的链式调用
 * - {@link CtorInvokerImpl}：构造函数调用器，支持特殊调用和实例分配
 * - {@link NativeHooker}：原生回调处理器，处理 ART 运行时触发的钩子回调
 *
 * 设计要点：
 * - 所有内部类均作为 LSPosedBridge 的静态内部类，便于 LSPosedContext 统一管理和访问
 * - 通过 HookBridge 原生桥接层与 ART 运行时交互，实现方法 Hook 和原始方法调用
 * - 支持两种异常模式：PROTECTIVE（保护模式，捕获并记录异常后继续执行）和 PASSTHROUGH（透传模式，直接抛出异常）
 */
public class LSPosedBridge {

    private static final String TAG = "LSPosed-Bridge";

    /**
     * 类型转换异常的错误提示信息。
     * 当钩子回调返回值的类型与被 Hook 方法的返回类型不匹配时使用。
     */
    private static final String castException = "Return value's type from hook callback does not match the hooked method";

    /**
     * InvocationTargetException.getCause() 方法的反射引用。
     * 通过反射获取以避免在 Android 运行时中可能存在的混淆或兼容性问题，
     * 确保在 NativeHooker.callback() 中能可靠地获取原始异常。
     */
    private static final Method getCause;

    static {
        Method tmp;
        try {
            tmp = InvocationTargetException.class.getMethod("getCause");
        } catch (Throwable e) {
            tmp = null;
        }
        getCause = tmp;
    }

    /**
     * 钩子回调的包装类，存储钩子实例及其配置信息。
     *
     * 当通过 {@link HookBridge#hookMethod} 注册钩子时，此对象会被传递给原生层。
     * 在方法被调用时，原生层通过 {@link HookBridge#callbackSnapshot} 获取所有已注册的
     * HookerCallback 对象，然后由 {@link NativeHooker#callback} 方法处理。
     *
     * 存储的信息包括：
     * - hooker：实现了 {@link XposedInterface.Hooker} 接口的钩子实例
     * - exceptionMode：该钩子的异常处理模式（PROTECTIVE/PASSTHROUGH/DEFAULT）
     * - priority：该钩子的优先级，用于控制钩子执行顺序和 Type.Chain 的 maxPriority 过滤
     * - id：该钩子的唯一标识符（API 102 新增），用于原子替换钩子
     */
    public static class HookerCallback {
        /** 实现了 Hooker 接口的钩子实例 */
        @NonNull
        final XposedInterface.Hooker hooker;
        /** 该钩子的异常处理模式 */
        @NonNull
        final XposedInterface.ExceptionMode exceptionMode;
        /** 该钩子的优先级，值越小优先级越高 */
        final int priority;
        /** 该钩子的唯一标识符，用于原子替换（API 102） */
        @Nullable
        final String id;

        /**
         * 使用默认配置创建钩子回调包装类。
         * 异常模式默认为 PROTECTIVE，优先级默认为 PRIORITY_DEFAULT。
         * 此构造器主要用于内部框架钩子（如 LSPosedHelper.doHook）。
         *
         * @param hooker 实现了 Hooker 接口的钩子实例
         */
        public HookerCallback(@NonNull XposedInterface.Hooker hooker) {
            this.hooker = hooker;
            this.exceptionMode = XposedInterface.ExceptionMode.PROTECTIVE;
            this.priority = XposedInterface.PRIORITY_DEFAULT;
            this.id = null;
        }

        /**
         * 使用完整配置创建钩子回调包装类。
         * 此构造器用于模块通过 HookBuilder 链式 API 注册钩子时。
         *
         * @param hooker        实现了 Hooker 接口的钩子实例
         * @param exceptionMode 异常处理模式
         * @param priority      钩子优先级
         */
        public HookerCallback(@NonNull XposedInterface.Hooker hooker, @NonNull XposedInterface.ExceptionMode exceptionMode, int priority) {
            this.hooker = hooker;
            this.exceptionMode = exceptionMode;
            this.priority = priority;
            this.id = null;
        }

        /**
         * 使用完整配置和 ID 创建钩子回调包装类（API 102）。
         * 支持设置钩子唯一标识符，用于实现原子替换钩子的功能。
         *
         * @param hooker        实现了 Hooker 接口的钩子实例
         * @param exceptionMode 异常处理模式
         * @param priority      钩子优先级
         * @param id            钩子唯一标识符，可为 null
         */
        public HookerCallback(@NonNull XposedInterface.Hooker hooker, @NonNull XposedInterface.ExceptionMode exceptionMode, int priority, @Nullable String id) {
            this.hooker = hooker;
            this.exceptionMode = exceptionMode;
            this.priority = priority;
            this.id = id;
        }
    }

    /**
     * 记录一条文本日志。
     * 同时输出到 logcat 和 LSPosed 的日志回调系统。
     *
     * @param text 要记录的日志文本
     */
    public static void log(String text) {
        Log.i(TAG, text);
        LSPDataCallback.getInstance().log(android.util.Log.INFO, TAG, text);
    }

    /**
     * 记录一个异常堆栈。
     * 将异常的完整堆栈跟踪转换为字符串后，同时输出到 logcat 和 LSPosed 的日志回调系统。
     *
     * @param t 要记录的异常
     */
    public static void log(Throwable t) {
        String logStr = Log.getStackTraceString(t);
        Log.e(TAG, logStr);
        LSPDataCallback.getInstance().log(android.util.Log.ERROR, TAG, logStr);
    }

    /**
     * 判断是否为"APK 中不含 dex"的异常。
     * 插件系统加载纯 native 插件 APK（不含 classes.dex）时，
     * openDexFileNative 会抛出 "Entry not found" 的 IOException，这是正常行为，
     * 不应当作错误记录日志。
     *
     * @param t 待判断的异常
     * @return 若是无 dex APK 导致的 IOException，返回 true
     */
    private static boolean isNoDexException(Throwable t) {
        return t instanceof IOException
                && t.getMessage() != null
                && t.getMessage().contains("Entry not found");
    }

    /**
     * XposedInterface.Chain 接口的实现，表示一个被拦截的方法调用链。
     *
     * 当方法被 Hook 后，所有已注册的钩子按优先级排列成一个调用链。
     * 每个钩子可以通过调用 {@link #proceed()} 将执行传递给链中的下一个钩子，
     * 或者调用 {@link #proceedWith(Object)} / {@link #proceedWith(Object, Object[])}
     * 修改 thisObject 或参数后继续执行。
     *
     * 链式调用流程：
     * 1. NativeHooker.callback 被 ART 运行时触发
     * 2. 创建 ChainImpl 实例，包含所有钩子数组和当前索引（初始为 0）
     * 3. 调用 hookers[0].intercept(chain) 启动链
     * 4. 钩子调用 chain.proceed() -> 创建新的 ChainImpl (索引 +1) -> 调用下一个钩子
     * 5. 最后一个钩子调用 proceed() -> 调用 HookBridge.invokeOriginalMethod 执行原始方法
     *
     * 异常处理：
     * - PROTECTIVE 模式：捕获钩子异常并记录日志，根据是否调用了 proceed() 决定后续行为
     * - PASSTHROUGH 模式：异常直接向上传播，不进行拦截
     */
    public static class ChainImpl implements XposedInterface.Chain {
        /** 被 Hook 的方法或构造函数的反射对象 */
        private final Executable executable;
        /** 方法调用所属的对象实例（静态方法为 null） */
        private Object thisObject;
        /** 当前钩子接收到的方法参数数组 */
        private Object[] args;
        /** 是否已经执行过 proceed，防止重复调用 */
        private boolean proceeded = false;

        /**
         * 当前链中所有钩子的数组。
         * 当为 null 时，表示这是一个独立的链（非多钩子链式调用场景）。
         */
        private final XposedInterface.Hooker[] hookers;
        /** 当前正在执行的钩子在数组中的索引 */
        private final int hookerIndex;

        /**
         * 每个钩子对应的异常处理模式数组，与 hookers 数组一一对应。
         * 用于在 proceed() 中决定如何处理下一个钩子抛出的异常。
         */
        private final XposedInterface.ExceptionMode[] exceptionModes;

        /** 最后一次 proceed() 的执行结果 */
        private Object result;
        /** 最后一次 proceed() 抛出的异常 */
        private Throwable throwable;

        /**
         * 创建方法调用链。
         *
         * @param executable     被 Hook 的方法或构造函数
         * @param thisObject     方法所属对象（静态方法传 null）
         * @param args           方法参数
         * @param hookers        钩子数组
         * @param hookerIndex    当前钩子索引
         * @param exceptionModes 异常模式数组
         */
        public ChainImpl(Executable executable, Object thisObject, Object[] args,
                         XposedInterface.Hooker[] hookers, int hookerIndex,
                         XposedInterface.ExceptionMode[] exceptionModes) {
            this.executable = executable;
            this.thisObject = thisObject;
            this.args = args;
            this.hookers = hookers;
            this.hookerIndex = hookerIndex;
            this.exceptionModes = exceptionModes;
        }

        /**
         * 获取被 Hook 的方法或构造函数。
         *
         * @return 可执行对象（Method 或 Constructor）
         */
        @NonNull
        @Override
        public Executable getExecutable() {
            return executable;
        }

        /**
         * 获取方法调用所属的对象实例。
         *
         * @return 对象实例，静态方法返回 null
         */
        @Override
        public Object getThisObject() {
            return thisObject;
        }

        /**
         * 获取方法调用的所有参数列表。
         * 返回的 List 由 Arrays.asList 包装，不支持结构性修改（如 add/remove），
         * 但可以通过 set 方法修改单个元素的值。
         *
         * @return 参数列表
         */
        @NonNull
        @Override
        public java.util.List<Object> getArgs() {
            return java.util.Collections.unmodifiableList(java.util.Arrays.asList(args));
        }

        /**
         * 获取指定索引的参数值。
         *
         * @param index 参数索引，从 0 开始
         * @return 索引对应的参数值
         * @throws IndexOutOfBoundsException 如果索引越界
         * @throws ClassCastException        如果参数类型不匹配（通常不会抛出）
         */
        @Override
        public Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException {
            return args[index];
        }

        /**
         * 执行调用链中的下一个步骤。
         *
         * 行为规则：
         * - 如果还有下一个钩子，创建新的链实例并将执行传递给该钩子
         * - 如果这是链中的最后一个，调用 HookBridge.invokeOriginalMethod 执行原始方法
         * - 对于 PROTECTIVE 模式的钩子：如果抛出异常且未调用 proceed，则跳过该钩子继续执行；
         *   如果已调用 proceed，则返回 proceed 的结果
         * - 禁止重复调用（会抛出 IllegalStateException）
         *
         * @return 方法执行结果
         * @throws Throwable 方法执行过程中抛出的异常
         */
        @Override
        public Object proceed() throws Throwable {
            if (proceeded) {
                throw new IllegalStateException("Chain already proceeded");
            }
            proceeded = true;

            // 如果链中还有更多钩子，调用下一个钩子的 intercept 方法
            if (hookers != null && hookerIndex + 1 < hookers.length) {
                var nextHooker = hookers[hookerIndex + 1];
                var nextChain = new ChainImpl(executable, thisObject, args, hookers,
                        hookerIndex + 1, exceptionModes);

                // 获取下一个钩子的异常处理模式，默认使用 PROTECTIVE
                var nextMode = (exceptionModes != null && hookerIndex + 1 < exceptionModes.length)
                        ? exceptionModes[hookerIndex + 1] : XposedInterface.ExceptionMode.PROTECTIVE;

                // DEFAULT 未在注册时解析的（防御性兜底），按文档默认值 PROTECTIVE 处理
                if (nextMode != XposedInterface.ExceptionMode.PASSTHROUGH) {
                    // PROTECTIVE 模式：捕获钩子异常，根据是否调用过 proceed 来决定后续行为
                    try {
                        result = nextHooker.intercept(nextChain);
                        throwable = null;
                    } catch (Throwable t) {
                        log("Hooker threw an exception in PROTECTIVE mode: " + t.getMessage());
                        log(t);
                        if (!nextChain.proceeded) {
                            // 钩子没有调用 proceed() 就抛出异常，跳过该钩子继续执行链
                            // 此时调用链本身的 proceed() 会前往下一个钩子或原始方法
                            result = nextChain.proceed();
                            return result;
                        } else {
                            // 钩子调用了 proceed() 之后抛出异常，使用 proceed() 的结果/异常
                            throwable = nextChain.getThrowable();
                            result = nextChain.getResult();
                            if (throwable != null) {
                                throw throwable;
                            }
                            return result;
                        }
                    }
                } else {
                    // PASSTHROUGH 或 DEFAULT 模式：异常直接向上传播
                    try {
                        result = nextHooker.intercept(nextChain);
                        throwable = null;
                    } catch (Throwable t) {
                        throwable = t;
                        throw t;
                    }
                }
                return result;
            }

            // 链的末端：调用原始方法
            // 注意：当 hookers 为 null 时（独立链场景），直接调用原始方法
            try {
                result = HookBridge.invokeOriginalMethod(executable, thisObject, args);
                throwable = null;
            } catch (InvocationTargetException ite) {
                // 解包 InvocationTargetException，直接抛出原始异常
                throwable = ite.getCause() != null ? ite.getCause() : ite;
                throw throwable;
            } catch (Throwable t) {
                throwable = t;
                throw t;
            }
            return result;
        }

        /**
         * 使用新的参数继续执行调用链，保持 thisObject 不变。
         *
         * @param newArgs 新的参数数组
         * @return 方法执行结果
         * @throws Throwable 方法执行过程中抛出的异常
         */
        @NonNull
        @Override
        public Object proceed(@NonNull Object[] newArgs) throws Throwable {
            args = newArgs;
            return proceed();
        }

        /**
         * 使用新的 thisObject 但保持原有参数继续执行调用链。
         *
         * 注意：静态方法不支持此操作，会抛出 UnsupportedOperationException。
         *
         * @param newThisObject 新的 thisObject
         * @return 方法执行结果
         * @throws Throwable 方法执行过程中抛出的异常
         */
        @Override
        public Object proceedWith(@NonNull Object newThisObject) throws Throwable {
            if (Modifier.isStatic(executable.getModifiers())) {
                throw new UnsupportedOperationException("proceedWith is not supported for static methods");
            }
            thisObject = newThisObject;
            return proceed();
        }

        /**
         * 使用新的 thisObject 和新的参数继续执行调用链。
         *
         * 此方法用于完全替换方法调用的接收对象和参数，适用于动态代理等高级场景。
         *
         * 注意：静态方法不支持此操作，会抛出 UnsupportedOperationException。
         *
         * @param newThisObject 新的 thisObject
         * @param newArgs       新的参数数组
         * @return 方法执行结果
         * @throws Throwable 方法执行过程中抛出的异常
         */
        @Override
        public Object proceedWith(@NonNull Object newThisObject, @NonNull Object[] newArgs) throws Throwable {
            if (Modifier.isStatic(executable.getModifiers())) {
                throw new UnsupportedOperationException("proceedWith is not supported for static methods");
            }
            thisObject = newThisObject;
            args = newArgs;
            return proceed();
        }

        /**
         * 获取最后一次 proceed() 的结果值。
         * 用于 NativeHooker.callback 和 Invoker 中获取链式调用的最终结果。
         *
         * @return 执行结果，可能为 null
         */
        public Object getResult() {
            return result;
        }

        /**
         * 获取最后一次 proceed() 抛出的异常。
         * 用于 NativeHooker.callback 和 Invoker 中获取链式调用的最终异常。
         *
         * @return 异常对象，没有异常时返回 null
         */
        public Throwable getThrowable() {
            return throwable;
        }

        /**
         * 检查当前链是否已经执行过 proceed()。
         * 用于 PROTECTIVE 异常模式中判断钩子是否调用了 proceed()，
         * 从而决定是跳过钩子还是使用 proceed 的结果。
         *
         * @return 如果已经调用过 proceed() 返回 true
         */
        public boolean hasProceeded() {
            return proceeded;
        }
    }

    /**
     * XposedInterface.HookHandle 接口的实现，表示一个已注册的钩子句柄。
     *
     * 钩子句柄用于管理钩子的生命周期，当前主要支持取消注册（unhook）。
     * 每个通过 {@link HookBuilderImpl#intercept} 注册的钩子都会返回一个
     * HookHandleImpl 实例，调用者可持有该句柄以便后续取消钩子。
     *
     * API 102 新增支持：
     * - {@link #getId()}：获取钩子的唯一标识符
     * - {@link #replaceHook(XposedInterface.Hooker)}：原子替换钩子
     *
     * 线程安全：unhook() 方法通过 unregistered 标志防止重复取消。
     */
    public static class HookHandleImpl implements XposedInterface.HookHandle {
        /** 被 Hook 的方法或构造函数 */
        private final Executable executable;
        /**
         * 注册时使用的回调对象（HookerCallback 实例）。
         * 取消钩子时需要将此对象传递给 HookBridge.unhookMethod，
         * 以便原生层能正确识别并移除对应的钩子条目。
         */
        private final Object callback;
        /** 钩子的唯一标识符（API 102），用于原子替换钩子 */
        @Nullable
        private final String id;
        /** 是否已经取消注册，防止重复调用 unhook；volatile 保证跨线程可见 */
        private volatile boolean unregistered = false;

        /**
         * 创建钩子句柄。
         *
         * @param executable 被 Hook 的可执行对象
         * @param callback   注册时使用的 HookerCallback 对象
         */
        public HookHandleImpl(Executable executable, Object callback) {
            this.executable = executable;
            this.callback = callback;
            this.id = null;
        }

        /**
         * 创建带 ID 的钩子句柄（API 102）。
         *
         * @param executable 被 Hook 的可执行对象
         * @param callback   注册时使用的 HookerCallback 对象
         * @param id         钩子的唯一标识符，可为 null
         */
        public HookHandleImpl(Executable executable, Object callback, @Nullable String id) {
            this.executable = executable;
            this.callback = callback;
            this.id = id;
        }

        /**
         * 获取被 Hook 的方法或构造函数。
         *
         * @return 可执行对象
         */
        @NonNull
        @Override
        public Executable getExecutable() {
            return executable;
        }

        /**
         * 获取钩子的唯一标识符（API 102）。
         *
         * @return 钩子 ID，如果未设置则返回 null
         */
        @Nullable
        @Override
        public String getId() {
            return id;
        }

        /**
         * 原子替换此钩子为新的 Hooker（API 102）。
         * <p>当前版本暂未实现，调用将抛出 UnsupportedOperationException。</p>
         *
         * @param hooker 新的 Hooker 对象
         * @return 新的钩子句柄
         * @throws UnsupportedOperationException 当前版本不支持此操作
         */
        @NonNull
        @Override
        public XposedInterface.HookHandle replaceHook(@NonNull XposedInterface.Hooker hooker) {
            throw new UnsupportedOperationException("replaceHook is not yet implemented");
        }

        /**
         * 取消注册此钩子。
         * 调用后，该方法将不再被此钩子拦截。
         * 多次调用是安全的，只有第一次调用会实际执行取消操作。
         */
        @Override
        public void unhook() {
            if (!unregistered) {
                HookBridge.unhookMethod(true, executable, callback);
                unregistered = true;
            }
        }
    }

    /**
     * XposedInterface.HookBuilder 接口的实现，用于构建和注册方法钩子。
     *
     * 采用建造者模式，支持链式调用设置以下属性：
     * - {@link #setPriority(int)}：设置钩子优先级，决定执行顺序
     * - {@link #setExceptionMode(XposedInterface.ExceptionMode)}：设置异常处理模式
     *
     * 最终通过 {@link #intercept(XposedInterface.Hooker)} 方法完成钩子注册。
     *
     * 安全限制：
     * - 不能 Hook 抽象方法
     * - 不能 Hook LSPosed 框架内部的方法
     * - 不能 Hook Method.invoke（防止无限递归）
     */
    public static class HookBuilderImpl implements XposedInterface.HookBuilder {
        /** 要 Hook 的方法或构造函数 */
        private final Executable executable;
        /** 钩子优先级，默认使用 PRIORITY_DEFAULT */
        private int priority = XposedInterface.PRIORITY_DEFAULT;
        /** 异常处理模式，默认使用 DEFAULT（跟随全局设置，通常为 PROTECTIVE） */
        private XposedInterface.ExceptionMode exceptionMode = XposedInterface.ExceptionMode.DEFAULT;
        /** 钩子的唯一标识符（API 102），用于原子替换钩子 */
        @Nullable
        private String id = null;

        /**
         * 创建钩子构建器。
         *
         * @param executable 要 Hook 的可执行对象
         */
        public HookBuilderImpl(Executable executable) {
            this.executable = executable;
        }

        /**
         * 设置钩子优先级。
         * 优先级值越小，执行顺序越靠前。例如 PRIORITY_HIGHEST 最先执行。
         *
         * @param priority 优先级值
         * @return 当前构建器实例，支持链式调用
         */
        @Override
        public XposedInterface.HookBuilder setPriority(int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * 设置异常处理模式。
         *
         * @param mode 异常处理模式（PROTECTIVE/PASSTHROUGH/DEFAULT）
         * @return 当前构建器实例，支持链式调用
         */
        @Override
        public XposedInterface.HookBuilder setExceptionMode(@NonNull XposedInterface.ExceptionMode mode) {
            this.exceptionMode = mode;
            return this;
        }

        /**
         * 设置钩子的唯一标识符（API 102）。
         * 相同 ID 的钩子会原子替换旧钩子。
         *
         * @param id 钩子唯一标识符，可为 null
         * @return 当前构建器实例，支持链式调用
         */
        @Override
        public XposedInterface.HookBuilder setId(@Nullable String id) {
            this.id = id;
            return this;
        }

        /**
         * 注册钩子并返回句柄。
         *
         * 注册流程：
         * 1. 进行合法性检查（抽象方法、内部方法、Method.invoke 等限制）
         * 2. 创建 HookerCallback 包装钩子实例、异常模式和优先级
         * 3. 通过 HookBridge.hookMethod 将钩子注册到 ART 运行时的原生层
         * 4. 注册成功返回 HookHandleImpl，失败抛出 HookFailedError
         *
         * @param hooker 实现了 Hooker 接口的钩子实例
         * @return 钩子句柄，可用于后续取消注册
         * @throws IllegalArgumentException 如果 Hook 目标不合法
         * @throws HookFailedError          如果原生层注册失败
         */
        @NonNull
        @Override
        public XposedInterface.HookHandle intercept(@NonNull XposedInterface.Hooker hooker) {
            if (hooker == null) {
                throw new IllegalArgumentException("hooker should not be null!");
            } else if (Modifier.isAbstract(executable.getModifiers())) {
                throw new IllegalArgumentException("Cannot hook abstract methods: " + executable);
            } else if (executable.getDeclaringClass().getClassLoader() == LSPosedContext.class.getClassLoader()) {
                throw new IllegalArgumentException("Do not allow hooking inner methods");
            } else if (executable.getDeclaringClass() == Method.class && executable.getName().equals("invoke")) {
                throw new IllegalArgumentException("Cannot hook Method.invoke");
            }

            // DEFAULT 模式应跟随 module.prop 的全局 exceptionMode 配置（默认 PROTECTIVE）。
            // 当前尚未解析 module.prop，此处按文档默认值解析为 PROTECTIVE。
            var resolvedMode = exceptionMode == XposedInterface.ExceptionMode.DEFAULT
                    ? XposedInterface.ExceptionMode.PROTECTIVE : exceptionMode;
            // 创建包含完整配置的回调包装对象
            var callback = new HookerCallback(hooker, resolvedMode, priority, id);

            // 通过原生层注册钩子
            if (HookBridge.hookMethod(true, executable, NativeHooker.class, priority, callback)) {
                return new HookHandleImpl(executable, callback, id);
            }
            throw new HookFailedError("Cannot hook " + executable);
        }
    }

    /**
     * XposedInterface.Invoker 接口对于方法的实现，提供灵活的方法调用方式。
     *
     * 支持三种调用类型：
     * - {@link XposedInterface.Invoker.Type.Origin}：直接调用原始方法，不经过任何钩子
     * - {@link XposedInterface.Invoker.Type.Chain}：经过钩子链调用，支持 maxPriority 过滤
     *   - Chain.FULL 包含所有钩子
     *   - Chain(maxPriority) 只包含优先级不超过 maxPriority 的钩子
     * - 默认 fallback：通过标准反射调用（method.invoke），
     *   在 ART 运行时中会经过完整的钩子链
     *
     * 此外还支持 {@link #invokeSpecial(Object, Object...)} 方法，
     * 用于调用父类的实现（类似 Java 中的 super 关键字）。
     */
    public static class MethodInvoker implements XposedInterface.Invoker<MethodInvoker, Method> {
        /** 要调用的方法 */
        private final Method method;
        /** invokeSpecial 首次使用时生成并复用，普通 invoke 不承担反射和分配开销 */
        private char[] shorty;
        /** 调用类型，默认为 Chain.FULL（经过完整钩子链调用） */
        private XposedInterface.Invoker.Type type = XposedInterface.Invoker.Type.Chain.FULL;

        /**
         * 创建方法调用器。
         *
         * @param method 要调用的方法
         */
        public MethodInvoker(Method method) {
            this.method = method;
        }

        /**
         * 设置调用类型。
         *
         * @param type 调用类型（Origin 或 Chain）
         * @return 当前调用器实例，支持链式调用
         */
        @Override
        public MethodInvoker setType(@NonNull XposedInterface.Invoker.Type type) {
            this.type = type;
            return this;
        }

        /**
         * 根据当前设置的调用类型执行方法调用。
         *
         * Origin 模式：
         * - 通过 HookBridge.invokeOriginalMethod 调用，绕过所有钩子
         * - 效果等同于方法未被 Hook 时的原始行为
         *
         * Chain 模式：
         * - 获取该方法的所有钩子回调，按 maxPriority 过滤
         * - 使用 ChainImpl 构建调用链并执行
         * - 链的末端通过 HookBridge.invokeOriginalMethod 调用原始方法
         * - 如果 maxPriority 过滤后没有钩子，回退到 Origin 模式
         *
         * Fallback（默认反射）：
         * - 通过 method.invoke 直接反射调用
         * - 在 ART 运行时中，如果方法被 Hook，此方式会经过完整的钩子链
         *
         * @param thisObject 方法所属对象（静态方法传 null）
         * @param args       方法参数
         * @return 方法执行结果
         * @throws InvocationTargetException 如果被调用的方法本身抛出了异常
         * @throws IllegalAccessException    如果没有访问权限
         * @throws IllegalArgumentException  如果参数类型不匹配
         */
        @Override
        public Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            // Origin 模式：直接调用原始方法，跳过所有钩子
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                return HookBridge.invokeOriginalMethod(method, thisObject, args);
            }
            // Chain 模式：通过钩子链调用，支持 maxPriority 过滤
            if (type instanceof XposedInterface.Invoker.Type.Chain) {
                var chainType = (XposedInterface.Invoker.Type.Chain) type;
                Object[][] callbacksSnapshot = HookBridge.callbackSnapshot(HookerCallback.class, method);
                // 未 Hook 的方法由 native 返回 null，保留零分配快速路径
                if (callbacksSnapshot == null) {
                    return HookBridge.invokeOriginalMethod(method, thisObject, args);
                }
                Object[] modernSnapshot = callbacksSnapshot[0];

                // 没有钩子时直接调用原始方法
                if (modernSnapshot.length == 0) {
                    return HookBridge.invokeOriginalMethod(method, thisObject, args);
                }

                // native 快照按优先级降序排列，跳过高于阈值的前缀即可
                int firstIncluded = 0;
                int maxPriority = chainType.maxPriority();
                while (firstIncluded < modernSnapshot.length
                        && ((HookerCallback) modernSnapshot[firstIncluded]).priority > maxPriority) {
                    firstIncluded++;
                }

                // 过滤后没有符合条件的钩子，直接调用原始方法
                int hookerCount = modernSnapshot.length - firstIncluded;
                if (hookerCount == 0) {
                    return HookBridge.invokeOriginalMethod(method, thisObject, args);
                }

                // 从过滤后的回调中提取钩子实例和异常模式
                var hookers = new XposedInterface.Hooker[hookerCount];
                var exceptionModes = new XposedInterface.ExceptionMode[hookerCount];
                for (int i = 0; i < hookerCount; i++) {
                    var hc = (HookerCallback) modernSnapshot[firstIncluded + i];
                    hookers[i] = hc.hooker;
                    exceptionModes[i] = hc.exceptionMode;
                }

                // 构建调用链并执行
                var chain = new ChainImpl(method, thisObject, args, hookers, 0, exceptionModes);
                try {
                    if (exceptionModes[0] == XposedInterface.ExceptionMode.PASSTHROUGH) {
                        return hookers[0].intercept(chain);
                    }
                    try {
                        return hookers[0].intercept(chain);
                    } catch (Throwable hookError) {
                        if (!chain.hasProceeded()) {
                            log("Hooker threw an exception in PROTECTIVE mode: " + hookError.getMessage());
                            log(hookError);
                            return chain.proceed();
                        }
                        var proceededThrowable = chain.getThrowable();
                        if (hookError != proceededThrowable) {
                            log("Hooker threw an exception in PROTECTIVE mode: " + hookError.getMessage());
                            log(hookError);
                        }
                        if (proceededThrowable != null) {
                            throw proceededThrowable;
                        }
                        return chain.getResult();
                    }
                } catch (InvocationTargetException | IllegalArgumentException | IllegalAccessException e) {
                    throw e;
                } catch (Throwable t) {
                    // 其他未预期的异常包装为 InvocationTargetException
                    throw new InvocationTargetException(t);
                }
            }
            // 默认 fallback：通过标准反射调用
            return method.invoke(thisObject, args);
        }

        /**
         * 调用父类的方法实现（类似 Java 的 super 关键字）。
         * 在 ART 运行时中，通过 invokeSpecial 指令绕过虚方法分派，
         * 直接调用指定父类的方法实现。
         *
         * @param thisObject 方法所属的对象实例
         * @param args       方法参数
         * @return 方法执行结果
         * @throws InvocationTargetException 如果被调用的方法本身抛出了异常
         * @throws IllegalArgumentException  如果是静态方法或参数类型不匹配
         * @throws IllegalAccessException    如果没有访问权限
         */
        @Override
        public Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            if (Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException("Cannot invoke special on static method: " + method);
            }
            return HookBridge.invokeSpecialMethod(method, getShorty(), method.getDeclaringClass(), thisObject, args);
        }

        private char[] getShorty() {
            var value = shorty;
            if (value == null) {
                shorty = value = getExecutableShorty(method);
            }
            return value;
        }

        /**
         * 获取可执行对象的短方法签名（shorty）。
         * shorty 是 ART 运行时中使用的一种紧凑签名表示，包含返回类型和参数类型。
         * 格式：shorty[0] = 返回类型字符，shorty[1..n] = 参数类型字符。
         * 用于 invokeSpecial 方法在原生层查找和调用正确的方法实现。
         *
         * @param executable 可执行对象（方法或构造函数）
         * @return 字符数组表示的 shorty
         */
        private static char[] getExecutableShorty(Executable executable) {
            var parameterTypes = executable.getParameterTypes();
            var shorty = new char[parameterTypes.length + 1];
            shorty[0] = getTypeShorty(executable instanceof Method ? ((Method) executable).getReturnType() : void.class);
            for (int i = 1; i < shorty.length; i++) {
                shorty[i] = getTypeShorty(parameterTypes[i - 1]);
            }
            return shorty;
        }

        /**
         * 将 Java 类型转换为 ART shorty 中的对应字符表示。
         *
         * 类型映射表：
         * - int → I, long → J, float → F, double → D
         * - boolean → Z, byte → B, char → C, short → S
         * - void → V, 引用类型（包括数组）→ L
         *
         * @param type Java 类型
         * @return shorty 字符
         */
        private static char getTypeShorty(Class<?> type) {
            if (type == int.class) return 'I';
            else if (type == long.class) return 'J';
            else if (type == float.class) return 'F';
            else if (type == double.class) return 'D';
            else if (type == boolean.class) return 'Z';
            else if (type == byte.class) return 'B';
            else if (type == char.class) return 'C';
            else if (type == short.class) return 'S';
            else if (type == void.class) return 'V';
            else return 'L';
        }
    }

    /**
     * XposedInterface.CtorInvoker 接口对于构造函数的实现，
     * 提供灵活且完整的构造函数调用方式，包括实例创建和初始化。
     *
     * 主要功能：
     * - {@link #invoke(Object, Object...)}：在现有对象上调用构造函数（不创建新实例），支持 Origin/Chain 模式
     * - {@link #invokeSpecial(Object, Object...)}：调用父类的构造函数（super 调用）
     * - {@link #newInstance(Object...)}：分配对象并调用构造函数初始化，支持 Origin/Chain 模式
     * - {@link #newInstanceSpecial(Class, Object...)}：创建子类实例并调用父类构造器，支持 Origin/Chain 模式
     *
     * 与 MethodInvoker 类似，支持 Origin 和 Chain 两种调用类型，
     * Chain 类型同样支持 maxPriority 过滤。
     *
     * @param <T> 构造函数所属的类类型
     */
    public static class CtorInvokerImpl<T> implements XposedInterface.CtorInvoker<T> {
        /** 要调用的构造函数 */
        private final Constructor<T> constructor;
        /** 构造函数声明类，避免每次分配或调用时重复查询 */
        private final Class<T> declaringClass;
        /** 首次执行构造函数时生成并复用 */
        private char[] shorty;
        /** 调用类型，默认为 Chain.FULL（经过完整钩子链调用） */
        private XposedInterface.Invoker.Type type = XposedInterface.Invoker.Type.Chain.FULL;

        /**
         * 创建构造函数调用器。
         *
         * @param constructor 要调用的构造函数
         */
        public CtorInvokerImpl(Constructor<T> constructor) {
            this.constructor = constructor;
            this.declaringClass = constructor.getDeclaringClass();
        }

        private char[] getShorty() {
            var value = shorty;
            if (value == null) {
                shorty = value = MethodInvoker.getExecutableShorty(constructor);
            }
            return value;
        }

        private Object invokeOriginal(Object thisObject, Object[] args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            return HookBridge.invokeSpecialMethod(constructor, getShorty(), declaringClass,
                    thisObject, args);
        }

        /**
         * 设置调用类型。
         *
         * @param type 调用类型（Origin 或 Chain）
         * @return 当前调用器实例，支持链式调用
         */
        @Override
        public XposedInterface.CtorInvoker<T> setType(@NonNull XposedInterface.Invoker.Type type) {
            this.type = type;
            return this;
        }

        /**
         * 在现有对象上调用构造函数（不创建新实例）。
         * 这类似于 Java 中的「未初始化对象 + 构造函数调用」模式，
         * 通常用于反序列化或框架内部的高级场景。
         *
         * Origin 模式：直接调用构造函数的原始实现，不经过钩子链
         * Chain 模式：经过钩子链调用，支持按 maxPriority 过滤钩子
         *
         * @param thisObject 要初始化的对象实例
         * @param args       构造函数参数
         * @return 始终为 null
         * @throws InvocationTargetException 如果构造函数本身抛出了异常
         * @throws IllegalAccessException    如果没有访问权限
         * @throws IllegalArgumentException  如果参数类型不匹配
         */
        @Override
        public Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            // Origin 模式：直接调用原始构造函数，跳过所有钩子
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                return invokeOriginal(thisObject, args);
            }
            // Chain 模式：通过钩子链调用，支持 maxPriority 过滤
            if (type instanceof XposedInterface.Invoker.Type.Chain) {
                var chainType = (XposedInterface.Invoker.Type.Chain) type;
                Object[][] callbacksSnapshot = HookBridge.callbackSnapshot(HookerCallback.class, constructor);
                if (callbacksSnapshot == null) {
                    return invokeOriginal(thisObject, args);
                }
                Object[] modernSnapshot = callbacksSnapshot[0];

                // 没有钩子时直接调用原始构造函数
                if (modernSnapshot.length == 0) {
                    return invokeOriginal(thisObject, args);
                }

                // 快照按优先级降序排列，只需跳过超出 maxPriority 的前缀
                int firstIncluded = 0;
                int maxPriority = chainType.maxPriority();
                while (firstIncluded < modernSnapshot.length
                        && ((HookerCallback) modernSnapshot[firstIncluded]).priority > maxPriority) {
                    firstIncluded++;
                }

                // 过滤后没有符合条件的钩子，直接调用原始构造函数
                int hookerCount = modernSnapshot.length - firstIncluded;
                if (hookerCount == 0) {
                    return invokeOriginal(thisObject, args);
                }

                // 构建调用链并执行
                var hookers = new XposedInterface.Hooker[hookerCount];
                var exceptionModes = new XposedInterface.ExceptionMode[hookerCount];
                for (int i = 0; i < hookerCount; i++) {
                    var hc = (HookerCallback) modernSnapshot[firstIncluded + i];
                    hookers[i] = hc.hooker;
                    exceptionModes[i] = hc.exceptionMode;
                }

                var chain = new ChainImpl(constructor, thisObject, args, hookers, 0, exceptionModes);
                try {
                    if (exceptionModes[0] == XposedInterface.ExceptionMode.PASSTHROUGH) {
                        return hookers[0].intercept(chain);
                    }
                    try {
                        return hookers[0].intercept(chain);
                    } catch (Throwable hookError) {
                        if (!chain.hasProceeded()) {
                            log("Modern Xposed API Hooker " + hookers[0].getClass().getName()
                                    + " throws an exception, skip it");
                            log(hookError);
                            return chain.proceed();
                        }
                        var proceededThrowable = chain.getThrowable();
                        if (hookError != proceededThrowable) {
                            log("Modern Xposed API Hooker " + hookers[0].getClass().getName()
                                    + " throws an exception after proceed, ignore it");
                            log(hookError);
                        }
                        if (proceededThrowable != null) {
                            throw proceededThrowable;
                        }
                        return chain.getResult();
                    }
                } catch (InvocationTargetException | IllegalAccessException |
                         IllegalArgumentException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new InvocationTargetException(t);
                }
            }
            // 默认 fallback：直接调用原始构造函数（sealed type 下此路径不可达，作为安全回退）
            return invokeOriginal(thisObject, args);
        }

        /**
         * 在现有对象上调用父类的构造函数（类似 super() 调用）。
         * 通过 ART 的 invokeSpecial 指令实现，绕过虚方法分派，
         * 直接调用指定父类的构造函数实现。
         *
         * @param thisObject 要初始化的对象实例
         * @param args       构造函数参数
         * @return 始终为 null
         * @throws InvocationTargetException 如果构造函数本身抛出了异常
         * @throws IllegalArgumentException  如果参数类型不匹配
         * @throws IllegalAccessException    如果没有访问权限
         */
        @Override
        public Object invokeSpecial(@NonNull Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            return invokeOriginal(thisObject, args);
        }

        /**
         * 完整地创建一个新实例。
         * 分两步完成：
         * 1. 通过 HookBridge.allocateObject 分配对象内存（不调用构造函数）
         * 2. 根据 type 通过原始实现或钩子链调用构造函数初始化对象
         *
         * @param args 构造函数参数
         * @return 新创建并初始化好的对象实例
         * @throws InvocationTargetException 如果构造函数本身抛出了异常
         * @throws IllegalAccessException    如果没有访问权限
         * @throws IllegalArgumentException  如果参数类型不匹配
         * @throws InstantiationException    如果无法实例化（如抽象类）
         */
        @NonNull
        @Override
        public T newInstance(Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            // 第一步：分配对象内存
            var obj = HookBridge.allocateObject(declaringClass);
            // 第二步：通过 invoke() 在已分配的对象上调用构造函数，尊重 type 设置（Origin/Chain）
            invoke(obj, args);
            return obj;
        }

        /**
         * 创建子类实例并调用父类的构造函数进行初始化。
         * 用于在子类实例化过程中调用父类构造函数的场景，
         * 例如在动态代理或字节码增强框架中创建继承自某类的子类实例。
         *
         * 流程：
         * 1. 验证 subClass 确实继承自当前构造函数的声明类
         * 2. 通过 HookBridge.allocateObject 分配子类对象内存
         * 3. 根据 type 设置调用构造函数：
         *    - Origin 模式：通过 invokeSpecialMethod 调用父类构造函数（非虚调度）
         *    - Chain 模式：通过 invoke() 走钩子链初始化对象
         *
         * @param subClass 要实例化的子类
         * @param args     构造函数参数
         * @return 新创建并初始化好的子类实例
         * @throws InvocationTargetException 如果构造函数本身抛出了异常
         * @throws IllegalAccessException    如果没有访问权限
         * @throws IllegalArgumentException  如果参数类型不匹配或 subClass 未继承自当前类
         * @throws InstantiationException    如果无法实例化（如抽象类）
         */
        @NonNull
        @Override
        public <U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
            if (!declaringClass.isAssignableFrom(subClass)) {
                throw new IllegalArgumentException(subClass + " is not inherited from " + declaringClass);
            }
            // 分配子类对象内存
            var obj = HookBridge.allocateObject(subClass);
            if (type instanceof XposedInterface.Invoker.Type.Origin) {
                // Origin 模式：直接通过 invokeSpecial 调用父类构造函数（非虚调度）
                invokeOriginal(obj, args);
            } else {
                // Chain 模式（含默认 fallback）：通过 invoke() 走钩子链，链末端调用备份构造函数
                invoke(obj, args);
            }
            return obj;
        }
    }

    /**
     * 原生钩子回调处理器，由 ART 运行时的原生层在方法被调用时触发。
     *
     * 当通过 HookBridge.hookMethod 注册了钩子的方法被执行时，
     * ART 运行时会跳转到 NativeHooker 的回调方法。native 层
     * 会创建 NativeHooker 实例并调用其 callback 方法。
     *
     * 处理流程：
     * 1. 从原生层获取方法参数，构建 LSPosedHookCallback
     * 2. 通过 HookBridge.callbackSnapshot 获取所有已注册的回调
     * 3. 优先处理现代 Hooker.intercept(Chain) 回调
     * 4. 如果没有现代回调，回退处理传统 Xposed 回调
     * 5. 都没有时直接调用原始方法并返回
     *
     * 这是一个关键的性能敏感路径，实现中尽量避免了不必要的系统方法调用
     * 以防止无限递归（例如 Method.invoke 调用导致再次进入回调）。
     *
     * @param <T> 可执行对象的类型（Method 或 Constructor）
     */
    public static class NativeHooker<T extends Executable> {
        /** 保存方法元信息的参数对象，包含 method、returnType、isStatic */
        private final Object params;

        /**
         * 创建原生钩子处理器。
         * 在构造时预先解析方法的静态信息，避免在 callback 热路径中重复解析。
         *
         * @param method 被 Hook 的方法或构造函数
         */
        private NativeHooker(Executable method) {
            var isStatic = Modifier.isStatic(method.getModifiers());
            Object returnType;
            if (method instanceof Method) {
                returnType = ((Method) method).getReturnType();
            } else {
                returnType = null;
            }
            // 将解析后的元信息存储在 Object 数组中，避免创建额外的包装类
            params = new Object[]{
                    method,
                    returnType,
                    isStatic,
            };
        }

        /**
         * 将拦截结果应用到回调对象。
         * 优先使用 intercept()/proceed() 的返回值，链内部状态作为异常信息补充。
         *
         * @param callback        回调对象
         * @param interceptResult intercept() 或 proceed() 的返回值
         * @param returnType      方法的返回类型，构造函数为 null
         */
        private void applyInterceptResult(LSPosedHookCallback<T> callback, Object interceptResult,
                                          Class<?> returnType) {
            if (interceptResult != null || returnType == null || !returnType.isPrimitive()) {
                callback.setResult(interceptResult);
            }
        }

        /**
         * PROTECTIVE 钩子在 proceed 后抛出异常时，恢复 proceed 已产生的结果或异常。
         */
        private void applyProceededResult(LSPosedHookCallback<T> callback, ChainImpl chain,
                                          Class<?> returnType) {
            var proceededThrowable = chain.getThrowable();
            if (proceededThrowable != null) {
                callback.setThrowable(proceededThrowable);
            } else {
                applyInterceptResult(callback, chain.getResult(), returnType);
            }
        }

        /**
         * 回调处理入口，由原生层在方法被调用时触发。
         *
         * 此方法是整个钩子框架的核心热路径，每次被 Hook 的方法被调用时都会执行。
         * 实现上注意：
         * - 尽量减少对象分配和系统方法调用
         * - 避免调用可能被 Hook 的方法（防止无限递归）
         * - 通过反射调用 getCause 而不是直接调用，以兼容可能的重写
         *
         * @param args 方法调用的参数数组。
         *             对于实例方法：args[0] = thisObject, args[1..n] = 方法参数
         *             对于静态方法：args 直接是方法参数
         * @return 方法执行结果
         * @throws Throwable 方法或钩子执行过程中抛出的异常
         */
        public Object callback(Object[] args) throws Throwable {
            LSPosedHookCallback<T> callback = new LSPosedHookCallback<>();

            // 从 params 数组中解包预解析的方法元信息
            var array = ((Object[]) params);

            var method = (T) array[0];
            var returnType = (Class<?>) array[1];
            var isStatic = (Boolean) array[2];

            callback.method = method;

            // 根据是否静态方法解析 thisObject 和参数
            if (isStatic) {
                callback.thisObject = null;
                callback.args = args;
            } else {
                callback.thisObject = args[0];
                callback.args = new Object[args.length - 1];
                // 手动数组拷贝，避免使用 System.arraycopy 可能造成的递归问题
                //noinspection ManualArrayCopy
                for (int i = 0; i < args.length - 1; ++i) {
                    callback.args[i] = args[i + 1];
                }
            }

            // 获取所有已注册的回调快照
            Object[][] callbacksSnapshot = HookBridge.callbackSnapshot(HookerCallback.class, method);
            Object[] modernSnapshot = callbacksSnapshot[0];
            Object[] legacySnapshot = callbacksSnapshot[1];

            // 如果没有注册任何钩子，直接调用原始方法
            if (modernSnapshot.length == 0 && legacySnapshot.length == 0) {
                try {
                    return HookBridge.invokeOriginalMethod(method, callback.thisObject, callback.args);
                } catch (InvocationTargetException ite) {
                    // 解包 InvocationTargetException 以暴露真实的原始异常
                    var cause = (Throwable) HookBridge.invokeOriginalMethod(getCause, ite);
                    throw cause != null ? cause : ite;
                }
            }

            // 传统 Xposed 回调的「方法前」阶段
            // 在现代钩子链之前执行，保证 legacy 回调对参数/thisObject 的修改能传递给现代钩子链；
            // 若 legacy 回调 returnEarly（跳过原始方法），现代链整体跳过，尊重 legacy 的替换语义
            XposedBridge.LegacyApiSupport<T> legacy = null;
            if (!callback.isSkipped && legacySnapshot.length != 0) {
                legacy = new XposedBridge.LegacyApiSupport<>(callback, legacySnapshot);
                legacy.handleBefore();
            }

            // 优先处理现代 Hooker.intercept(Chain) 回调
            // 仅当方法上存在现代回调且未被 legacy 回调跳过时执行
            if (modernSnapshot.length > 0 && !callback.isSkipped) {
                // 从所有回调中提取钩子实例和对应的异常模式
                // 回调按优先级排序，因此构建的数组也是按优先级排列的
                var hookers = new XposedInterface.Hooker[modernSnapshot.length];
                var exceptionModes = new XposedInterface.ExceptionMode[modernSnapshot.length];
                for (int i = 0; i < modernSnapshot.length; i++) {
                    var hc = (HookerCallback) modernSnapshot[i];
                    hookers[i] = hc.hooker;
                    exceptionModes[i] = hc.exceptionMode;
                }

                // 创建调用链，将钩子和异常模式数组传入
                var chain = new ChainImpl(method, callback.thisObject, callback.args, hookers, 0, exceptionModes);

                // 对第一个钩子应用异常处理模式
                // 第一个钩子的异常模式决定了顶层调用如何响应
                // DEFAULT 未在注册时解析的（防御性兜底），按文档默认值 PROTECTIVE 处理
                var firstMode = exceptionModes[0];
                if (firstMode != XposedInterface.ExceptionMode.PASSTHROUGH) {
                    // PROTECTIVE 模式：捕获异常，日志记录后根据 proceed 状态决定后续行为
                    try {
                        var result = hookers[0].intercept(chain);
                        applyInterceptResult(callback, result, returnType);
                    } catch (Throwable t) {
                        // 无 dex APK（如纯 native 插件）加载报错是正常行为，不记录日志
                        if (!isNoDexException(t)) {
                            log("First hooker threw an exception in PROTECTIVE mode: " + t.getMessage());
                            log(t);
                        }
                        if (!chain.hasProceeded()) {
                            // 第一个钩子没有调用 proceed() 就抛出异常，跳过它继续执行链
                            try {
                                var result = chain.proceed();
                                applyInterceptResult(callback, result, returnType);
                            } catch (Throwable t2) {
                                callback.setThrowable(t2);
                            }
                        } else {
                            // 第一个钩子调用了 proceed() 之后抛出异常，使用 proceed 的结果
                            applyProceededResult(callback, chain, returnType);
                        }
                    }
                } else {
                    // PASSTHROUGH 或 DEFAULT 模式：异常直接向上传播
                    try {
                        var result = hookers[0].intercept(chain);
                        applyInterceptResult(callback, result, returnType);
                    } catch (Throwable t) {
                        callback.setThrowable(t);
                    }
                }
            } else if (legacySnapshot.length != 0) {
                // 传统 Xposed 回调处理路径
                // 当没有现代回调、或现代回调被 legacy 回调 returnEarly 跳过时执行

                // 如果未被跳过，调用原始方法
                if (!callback.isSkipped) {
                    try {
                        var result = HookBridge.invokeOriginalMethod(method, callback.thisObject, callback.args);
                        callback.setResult(result);
                    } catch (InvocationTargetException e) {
                        var throwable = (Throwable) HookBridge.invokeOriginalMethod(getCause, e);
                        callback.setThrowable(throwable != null ? throwable : e);
                    }
                }
            }

            // 调用传统「方法后」回调
            // 无论现代链还是纯 legacy 路径，只要执行过「方法前」就调用，保证 before/after 成对
            if (legacy != null) {
                legacy.handleAfter();
            }

            // 统一处理返回结果
            // 无论是现代链式回调还是传统回调，最终结果都存储在 callback 对象中
            var t = callback.getThrowable();
            if (t != null) {
                // 有异常时抛出
                throw t;
            } else {
                // 有返回值时进行类型校验
                var result = callback.getResult();
                if (returnType != null && !returnType.isPrimitive() && result != null && !HookBridge.instanceOf(result, returnType)) {
                    throw new ClassCastException(castException);
                }
                return result;
            }
        }
    }

    /**
     * 内部钩子注册方法，供 LSPosedHelper 等框架内部工具使用。
     *
     * 与 {@link HookBuilderImpl#intercept} 不同，此方法是一个简化的静态方法：
     * - 默认使用 PROTECTIVE 异常模式
     * - 不需要建造者模式链式调用
     * - 主要用于框架内部钩子，而非第三方模块 API
     *
     * @param hookMethod 要 Hook 的方法或构造函数
     * @param priority   钩子优先级
     * @param hooker     实现了 Hooker 接口的钩子实例
     * @param <T>        可执行对象类型
     * @return 钩子句柄，可用于后续取消注册
     * @throws IllegalArgumentException 如果 Hook 目标不合法
     * @throws HookFailedError          如果原生层注册失败
     */
    public static <T extends Executable> XposedInterface.HookHandle
    doHook(T hookMethod, int priority, @NonNull XposedInterface.Hooker hooker) {
        if (hooker == null) {
            throw new IllegalArgumentException("hooker should not be null!");
        } else if (Modifier.isAbstract(hookMethod.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod);
        } else if (hookMethod.getDeclaringClass().getClassLoader() == LSPosedContext.class.getClassLoader()) {
            throw new IllegalArgumentException("Do not allow hooking inner methods");
        } else if (hookMethod.getDeclaringClass() == Method.class && hookMethod.getName().equals("invoke")) {
            throw new IllegalArgumentException("Cannot hook Method.invoke");
        }

        // 使用默认配置（PROTECTIVE 模式、PRIORITY_DEFAULT 优先级）创建回调包装
        var callback = new LSPosedBridge.HookerCallback(hooker);
        if (HookBridge.hookMethod(true, hookMethod, LSPosedBridge.NativeHooker.class, priority, callback)) {
            return new HookHandleImpl(hookMethod, callback);
        }
        throw new HookFailedError("Cannot hook " + hookMethod);
    }
}
