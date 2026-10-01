/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch.util;

import static org.ytp.share.Constants.ANDROID_PROXY_FACTORIES_SMAIL;
import static org.ytp.share.Constants.LOG_PROCESS;

import org.ytp.share.ClassDefInfo;
import org.ytp.share.Constants;
import org.ytp.share.R;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.ReferenceType;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.Annotation;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction20bc;
import org.jf.dexlib2.iface.instruction.formats.Instruction21c;
import org.jf.dexlib2.iface.instruction.formats.Instruction22c;
import org.jf.dexlib2.iface.instruction.formats.Instruction31c;
import org.jf.dexlib2.iface.instruction.formats.Instruction35c;
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.value.EncodedValue;
import org.jf.dexlib2.iface.value.StringEncodedValue;
import org.jf.dexlib2.immutable.instruction.ImmutableInstructionFactory;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.rewriter.TypeRewriter;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DEX 类型引用、字符串常量和继承关系的定向修改工具。 */
public class DexModifier {

    private final Logger logger;

    public static final List<String> SELF_PROXY = List.of("L" + Constants.PROXY_APP_PROXY_FACTORY.replace('.', '/') + ";");

    private static final String FACTORY_SUFFIX = "ComponentFactory;";

    private static final List<String> EXCLUDED_PREFIXES = List.of(
            // Java 和 Kotlin 标准库
            "Ljava/", "Ljavax/",
            "Lkotlin/", "Lkotlinx/",
            // Android 平台类
            "Landroid/support/",
            "Landroid/accessibilityservice/",
            "Landroid/accounts/",
            "Landroid/animation/",
            "Landroid/appwidget/",
            "Landroid/bluetooth/",
            "Landroid/content/",
            "Landroid/database/",
            "Landroid/graphics/",
            "Landroid/hardware/",
            "Landroid/location/",
            "Landroid/media/",
            "Landroid/net/",
            "Landroid/os/",
            "Landroid/provider/",
            "Landroid/telephony/",
            "Landroid/view/",
            "Landroid/webkit/",
            "Landroid/widget/",
            "Lcom/android/tools/",
            "Lcom/android/annotation/",
            // AndroidX 通用库类
            "Landroidx/activity/",
            "Landroidx/annotation/",
            "Landroidx/appcompat/",
            "Landroidx/arch/",
            "Landroidx/asynclayoutinflater/",
            "Landroidx/biometric/",
            "Landroidx/camera/",
            "Landroidx/cardview/",
            "Landroidx/collection/",
            "Landroidx/compose/",
            "Landroidx/constraintlayout/",
            "Landroidx/coordinatorlayout/",
            "Landroidx/cursoradapter/",
            "Landroidx/customview/",
            "Landroidx/databinding/",
            "Landroidx/documentfile/",
            "Landroidx/drawerlayout/",
            "Landroidx/dynamicanimation/",
            "Landroidx/emoji/",
            "Landroidx/exifinterface/",
            "Landroidx/fragment/",
            "Landroidx/gridlayout/",
            "Landroidx/heaptestutil/",
            "Landroidx/interpolator/",
            "Landroidx/leanback/",
            "Landroidx/legacy/",
            "Landroidx/lifecycle/",
            "Landroidx/loader/",
            "Landroidx/localbroadcastmanager/",
            "Landroidx/media/",
            "Landroidx/navigation/",
            "Landroidx/palette/",
            "Landroidx/percentlayout/",
            "Landroidx/preference/",
            "Landroidx/print/",
            "Landroidx/recyclerview/",
            "Landroidx/remotecallback/",
            "Landroidx/room/",
            "Landroidx/savedstate/",
            "Landroidx/slice/",
            "Landroidx/sqlite/",
            "Landroidx/swiperefreshlayout/",
            "Landroidx/test/",
            "Landroidx/transition/",
            "Landroidx/vectordrawable/",
            "Landroidx/versionedparcelable/",
            "Landroidx/viewpager/",
            "Landroidx/viewpager2/",
            "Landroidx/webkit/",
            "Landroidx/work/",
            // 其他常见第三方库类
            "Ldalvik/","Lcom/google/gson/","Lcom/google/guava/","Lorg/apache/","Lorg/jetbrains/","Lorg/gradle/","Lio/netty/","Lorg/xml/", "Lorg/w3c/");

    public DexModifier(){
        this(null);
    }

    public DexModifier(Logger logger){
        this.logger = logger;
    }

    /**
     * 只替换指定类的父类描述符；找不到该类时返回原始字节并标记为未修改。
     * 完整 DEX 会在最后统一重写一次，避免逐类重建造成重复工作。
     */
    public R<InputStream> modifySuperclass(byte[] dexData, String className, String newSuperclassName) throws IOException {
        R<byte[]> result = modifySuperclass2ByteArray(dexData, className, newSuperclassName);
        return new R<>(result.getCode(), new ByteArrayInputStream(result.getData()));
    }

    /**
     * 重命名 DEX 中的包及其类型引用，并同步处理反射或错误信息里的完整包名字符串。
     */
    public byte[] renamePackage(byte[] dexData, String oldPackageName, String newPackageName) throws IOException {
        if (oldPackageName == null || newPackageName == null || oldPackageName.isEmpty()
                || newPackageName.isEmpty() || oldPackageName.equals(newPackageName)) {
            return dexData;
        }

        final String oldDescriptor = "L" + oldPackageName.replace('.', '/') + ";";
        final String oldDescriptorPrefix = "L" + oldPackageName.replace('.', '/') + "/";
        final String newDescriptor = "L" + newPackageName.replace('.', '/') + ";";
        final String newDescriptorPrefix = "L" + newPackageName.replace('.', '/') + "/";

        DexBackedDexFile dexFile = new DexBackedDexFile(Opcodes.forApi(35), dexData);
        if (!containsPackage(dexFile, oldPackageName)) {
            return dexData;
        }
        DexRewriter dexRewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                return new TypeRewriter() {
                    @Override
                    protected String rewriteUnwrappedType(String value) {
                        if (value.equals(oldDescriptor)) {
                            return newDescriptor;
                        }
                        if (value.startsWith(oldDescriptorPrefix)) {
                            return newDescriptorPrefix + value.substring(oldDescriptorPrefix.length());
                        }
                        return value;
                    }
                };
            }

            @Override
            public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                Rewriter<Instruction> delegate = super.getInstructionRewriter(rewriters);
                return instruction -> {
                    Instruction rewritten = delegate.rewrite(instruction);
                    if (!(rewritten instanceof ReferenceInstruction referenceInstruction)
                            || referenceInstruction.getReferenceType() != ReferenceType.STRING) {
                        return rewritten;
                    }

                    StringReference reference = (StringReference) referenceInstruction.getReference();
                    String rewrittenString = rewritePackageInString(
                            reference.getString(), oldPackageName, newPackageName);
                    if (reference.getString().equals(rewrittenString)) {
                        return rewritten;
                    }
                    return replaceStringReference(rewritten, new ImmutableStringReference(rewrittenString));
                };
            }

            @Override
            public Rewriter<EncodedValue> getEncodedValueRewriter(Rewriters rewriters) {
                Rewriter<EncodedValue> delegate = super.getEncodedValueRewriter(rewriters);
                return value -> {
                    EncodedValue rewritten = delegate.rewrite(value);
                    if (!(rewritten instanceof StringEncodedValue stringEncodedValue)) {
                        return rewritten;
                    }

                    String rewrittenString = rewritePackageInString(
                            stringEncodedValue.getValue(), oldPackageName, newPackageName);
                    if (stringEncodedValue.getValue().equals(rewrittenString)) {
                        return rewritten;
                    }
                    return new ImmutableStringEncodedValue(rewrittenString);
                };
            }
        });

        DexFile rewrittenDexFile = dexRewriter.getDexFileRewriter().rewrite(dexFile);
        MemoryDataStore dataStore = new MemoryDataStore();
        DexPool.writeTo(dataStore, rewrittenDexFile);
        return dataStore.getData();
    }

    private static boolean containsPackage(DexBackedDexFile dexFile, String packageName) {
        String descriptor = "L" + packageName.replace('.', '/') + ";";
        String descriptorPrefix = "L" + packageName.replace('.', '/') + "/";
        for (var reference : dexFile.getTypeReferences()) {
            String type = reference.getType();
            if (type.equals(descriptor) || type.startsWith(descriptorPrefix)) {
                return true;
            }
        }
        for (StringReference reference : dexFile.getStringReferences()) {
            if (!rewritePackageInString(reference.getString(), packageName, "").equals(reference.getString())) {
                return true;
            }
        }
        return false;
    }

    private static String rewritePackageInString(String value, String oldPackageName, String newPackageName) {
        int searchFrom = 0;
        int copiedThrough = 0;
        StringBuilder rewritten = null;
        while (true) {
            int index = value.indexOf(oldPackageName, searchFrom);
            if (index < 0) {
                break;
            }
            int end = index + oldPackageName.length();
            boolean leftBoundary = index == 0 || !isPackageCharacter(value.charAt(index - 1));
            boolean rightBoundary = end == value.length() || !isPackageCharacter(value.charAt(end))
                    || value.charAt(end) == '.';
            if (leftBoundary && rightBoundary) {
                if (rewritten == null) {
                    rewritten = new StringBuilder(value.length());
                }
                rewritten.append(value, copiedThrough, index).append(newPackageName);
                copiedThrough = end;
            }
            searchFrom = end;
        }
        return rewritten == null ? value : rewritten.append(value, copiedThrough, value.length()).toString();
    }

    private static boolean isPackageCharacter(char value) {
        return Character.isJavaIdentifierPart(value) || value == '.';
    }

    private static Instruction replaceStringReference(Instruction instruction, StringReference reference) {
        ImmutableInstructionFactory factory = ImmutableInstructionFactory.INSTANCE;
        switch (instruction.getOpcode().format) {
            case Format20bc:
                Instruction20bc instruction20bc = (Instruction20bc) instruction;
                return factory.makeInstruction20bc(instruction.getOpcode(), instruction20bc.getVerificationError(), reference);
            case Format21c:
                Instruction21c instruction21c = (Instruction21c) instruction;
                return factory.makeInstruction21c(instruction.getOpcode(), instruction21c.getRegisterA(), reference);
            case Format22c:
                Instruction22c instruction22c = (Instruction22c) instruction;
                return factory.makeInstruction22c(instruction.getOpcode(), instruction22c.getRegisterA(),
                        instruction22c.getRegisterB(), reference);
            case Format31c:
                Instruction31c instruction31c = (Instruction31c) instruction;
                return factory.makeInstruction31c(instruction.getOpcode(), instruction31c.getRegisterA(), reference);
            case Format35c:
                Instruction35c instruction35c = (Instruction35c) instruction;
                return factory.makeInstruction35c(instruction.getOpcode(), instruction35c.getRegisterCount(),
                        instruction35c.getRegisterC(), instruction35c.getRegisterD(), instruction35c.getRegisterE(),
                        instruction35c.getRegisterF(), instruction35c.getRegisterG(), reference);
            case Format3rc:
                Instruction3rc instruction3rc = (Instruction3rc) instruction;
                return factory.makeInstruction3rc(instruction.getOpcode(), instruction3rc.getStartRegister(),
                        instruction3rc.getRegisterCount(), reference);
            default:
                throw new IllegalArgumentException("Unsupported string reference instruction format: "
                        + instruction.getOpcode().format);
        }
    }

    /**
     * 从 Manifest 中声明的工厂沿父类链查找，返回直接继承系统组件工厂的应用类。
     * classDefMap 可汇总多个 DEX；缺失父类或循环继承会安全终止。
     */
    public static ClassDefInfo getFinalClass(Map<String, ClassDefInfo> classDefMap, String startClassName){
        // 异常 DEX 可能含有循环继承关系，已访问集合保证查找能够终止。
        String currentClassName = startClassName.startsWith("L") && startClassName.endsWith(";")
                ? startClassName : "L" + startClassName.replace('.', '/') + ";";
        ClassDefInfo targetClassDefInfo = null;
        Set<String> visited = new HashSet<>();
        while (currentClassName != null && visited.add(currentClassName)) {
            ClassDefInfo currentClassDef = classDefMap.get(currentClassName);
            if (currentClassDef == null) {
                // 已扫描的 DEX 都没有该父类时停止；调用方可在扫描下一 DEX 后重试。
                break;
            }
            
            // 只修改直接继承 Android 组件工厂的应用类。
            if (ANDROID_PROXY_FACTORIES_SMAIL.contains(currentClassDef.getSuperClassName())  &&
                    !ANDROID_PROXY_FACTORIES_SMAIL.contains(currentClassDef.getClassName()) &&
                    !SELF_PROXY.contains(currentClassDef.getClassName())) {
                targetClassDefInfo = currentClassDef;
            }

            // 沿继承链继续向上查找。
            currentClassName = currentClassDef.getSuperClassName();

            // 到达系统组件工厂后无需继续查找。
            if (ANDROID_PROXY_FACTORIES_SMAIL.contains(currentClassName)) {
                break;
            }
        }
        
        return targetClassDefInfo;
    }

    /**
     * 只抽取继承关系和所属 DEX，不保留方法体；跨 DEX 查找时可降低常驻内存。
     * 普通库类按描述符前缀过滤，但组件工厂类始终保留；应用类即使继承库类
     * 也必须纳入映射，否则跨 DEX 的继承链会被截断。
     */
    public Map<String, ClassDefInfo> getAllClass(byte[] dexData, String dexName) throws IOException {
        Opcodes opcodes = Opcodes.forApi(35);
        DexBackedDexFile dexFile = new DexBackedDexFile(opcodes, dexData);
        Map<String, ClassDefInfo> classDefMap = new java.util.HashMap<>();
        for (ClassDef def : dexFile.getClasses()) {
            String superclass = def.getSuperclass();
            if (superclass == null) {
                continue;
            }
            String type = def.getType();
            if (!isExcluded(type) || type.endsWith(FACTORY_SUFFIX)) {
                classDefMap.put(type, new ClassDefInfo(type, superclass, dexName));
            }
        }
        return classDefMap;
    }

    /** 只按类型前缀排除平台和常见库类，避免误删名称中碰巧包含相同片段的应用类。 */
    private static boolean isExcluded(String type) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }


    /**
     * 构建新 DEX 时只包装目标类，并让包装类返回新的父类描述符。
     * 其他类仍使用原始定义，保持字段、方法、注解等内容。
     */
    private R<byte[]> modifySuperclass2ByteArray(byte[] dexData, String className, String newSuperclassName) throws IOException {
        Opcodes opcodes = Opcodes.forApi(35);
        boolean modified = false;
        // 先解析原始 DEX，再通过 DexPool 生成有效索引和校验信息。
        DexBackedDexFile dexFile = new DexBackedDexFile(opcodes, dexData);

        DexPool dexPool = new DexPool(opcodes);

        int size = dexFile.getClasses().size();
        int num = 1;
        // 管理端会把进度日志同步到 UI；每个类都上报会让大型 DEX 的界面更新成为瓶颈。
        int progressStep = Math.max(1, size / 100);
        for (ClassDef classDef : dexFile.getClasses()) {
            if (classDef.getType().equals(className)) {
                ModifiedClassDef modifiedClassDef = new ModifiedClassDef(classDef, newSuperclassName);
                dexPool.internClass(modifiedClassDef);
                modified = true;
            }
            else {
                dexPool.internClass(classDef);
            }
            if (logger != null && logger.verbose && (num == size || num % progressStep == 0)) {
                logger.d(LOG_PROCESS + "  -Modified: " + num + "/" + size);
            }
            num++;
        }
        if (!modified) {
            // 未命中时直接返回原始字节，避免无意义的重写并让调用方识别结果。
            return new R<>(false, dexData);
        }
        if (logger != null) {
            logger.d("  -Generate dex... ");
        }
        // 只在命中目标后写出 DEX；未命中路径直接复用传入字节。
        MemoryDataStore dataStore = new MemoryDataStore();
        dexPool.writeTo(dataStore);
        return new R<>(modified, dataStore.getData());
    }

    /** 透传原始类的所有成员和元数据，仅重写 getSuperclass()。 */
    private static class ModifiedClassDef implements ClassDef {
        private final ClassDef originalClassDef;
        private final String newSuperclassName;

        public ModifiedClassDef(ClassDef originalClassDef, String newSuperclassName) {
            this.originalClassDef = originalClassDef;
            this.newSuperclassName = newSuperclassName;
        }
        
        @Override
        public String getType() {
            return originalClassDef.getType();
        }

        @Override
        public int compareTo( CharSequence o) {
            return originalClassDef.compareTo(o);
        }

        @Override
        public int getAccessFlags() {
            return originalClassDef.getAccessFlags();
        }

        @Override
        public String getSuperclass() {
            return newSuperclassName;
        }

        
        @Override
        public List<String> getInterfaces() {
            return originalClassDef.getInterfaces();
        }

        @Override
        public String getSourceFile() {
            return originalClassDef.getSourceFile();
        }

        
        @Override
        public Set<? extends Annotation> getAnnotations() {
            return originalClassDef.getAnnotations();
        }

        
        @Override
        public Iterable<? extends Field> getStaticFields() {
            return originalClassDef.getStaticFields();
        }

        
        @Override
        public Iterable<? extends Field> getInstanceFields() {
            return originalClassDef.getInstanceFields();
        }

        
        @Override
        public Iterable<? extends Field> getFields() {
            return originalClassDef.getFields();
        }

        
        @Override
        public Iterable<? extends Method> getDirectMethods() {
            return originalClassDef.getDirectMethods();
        }

        
        @Override
        public Iterable<? extends Method> getVirtualMethods() {
            return originalClassDef.getVirtualMethods();
        }

        
        @Override
        public Iterable<? extends Method> getMethods() {
            return originalClassDef.getMethods();
        }

        @Override
        public int length() {
            return originalClassDef.length();
        }

        @Override
        public char charAt(int index) {
            return originalClassDef.charAt(index);
        }

        
        @Override
        public CharSequence subSequence(int start, int end) {
            return originalClassDef.subSequence(start, end);
        }

        @Override
        public void validateReference() throws InvalidReferenceException {
            originalClassDef.validateReference();
        }
    }

}
