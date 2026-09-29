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
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DexModifier {

    private Logger logger;

    final static public List<String> SELF_PROXY = List.of("L"+Constants.PROXY_APP_PROXY_FACTORY.replaceAll("\\.","/")+";");

    final static private List<String> INCLUDED_LIST = List.of(
            //android
            "Landroid/support/_ComponentFactory;"
    );

    final static private List<String> EXCLUSION_LIST = List.of(
            //java
            "Ljava/", "Ljavax/",
            //kotlin
            "Lkotlin/", "Lkotlinx/",
            // android
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
            //androidx
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
            //other
            "Ldalvik/","Lcom/google/gson/","Lcom/google/guava/","Lorg/apache/","Lorg/jetbrains/","Lorg/gradle/","Lio/netty/","Lorg/xml/", "Lorg/w3c/");

    public DexModifier(){
    }

    public DexModifier(Logger logger){
        this.logger = logger;
    }

    public R<InputStream> modifySuperclass(byte[] dexData, String className, String newSuperclassName) throws IOException {
        R<byte[]> r = modifySuperclass2ByteArray(dexData, className, newSuperclassName);
        if(r.getCode()){
            return new R<>(true, new ByteArrayInputStream(r.getData()));
        }
        return new R<>(false, new ByteArrayInputStream(r.getData()));
    }

    /**
     * Renames one Java package in a DEX file and rewrites all type references that point into it.
     * String constants containing the old package are rewritten as well because Android libraries
     * commonly keep generated class names in error messages or reflection code.
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
            if (reference.getString().contains(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static String rewritePackageInString(String value, String oldPackageName, String newPackageName) {
        return value.replace(oldPackageName, newPackageName);
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

    public static ClassDefInfo getFinalClass(Map<String, ClassDefInfo> classDefMap, String startClassName){
        // Process inheritance chain starting from startClassName
        startClassName = "L"+startClassName.replaceAll("\\.","/")+";";
        String currentClassName = startClassName;
        ClassDefInfo targetClassDefInfo = null;
        while (currentClassName != null) {
            ClassDefInfo currentClassDef = classDefMap.get(currentClassName);
            if (currentClassDef == null) {
                // Class not found in this DEX file, stop traversing
                break;
            }
            
            // Check if this class meets the basic conditions
            if (ANDROID_PROXY_FACTORIES_SMAIL.contains(currentClassDef.getSuperClassName())  &&
                    !ANDROID_PROXY_FACTORIES_SMAIL.contains(currentClassDef.getClassName()) &&
                    !SELF_PROXY.contains(currentClassDef.getClassName())) {
                targetClassDefInfo = currentClassDef;
            }

            // Move to the superclass
            currentClassName = currentClassDef.getSuperClassName();

            // If we've reached a superclass that is in our target lists, stop traversing
            if (ANDROID_PROXY_FACTORIES_SMAIL.contains(currentClassName)) {
                break;
            }
        }
        
        return targetClassDefInfo;
    }

    public Map<String, ClassDefInfo>  getAllClass(byte[] dexData, String dexName) throws IOException {
        Opcodes opcodes = Opcodes.forApi(35);
        DexBackedDexFile dexFile = new DexBackedDexFile(opcodes, dexData);
       Map<String, ClassDefInfo> classDefMap = new java.util.HashMap<>();
        for (ClassDef def : dexFile.getClasses()) {
            if(def.getSuperclass()!=null && (
                    EXCLUSION_LIST.stream().noneMatch(exclusion -> def.getType().contains(exclusion) || def.getSuperclass().contains(exclusion)))
                    || INCLUDED_LIST.stream().anyMatch(included -> (def.getType().startsWith(included.split("_")[0]) && def.getType().endsWith(included.split("_")[1])))
            ){
                classDefMap.put(def.getType(), new ClassDefInfo(def.getType(), def.getSuperclass(), dexName));
            }
        }
        return classDefMap;
    }


    /**
     * Modifies the superclass of a specific class in a DEX file
     *
     * @param dexData The original DEX file data
     * @param className The class to modify (fully qualified name)
     * @param newSuperclassName The new superclass (fully qualified name)
     * @return The modified DEX file data
     * @throws IOException If there's an error processing the DEX file
     */
    private  R<byte[]> modifySuperclass2ByteArray(byte[] dexData, String className, String newSuperclassName) throws IOException {
        Opcodes opcodes = Opcodes.forApi(35);
        boolean modified = false;
       // String smailClassName = "L" + className.replace(".", "/") + ";";
        // Load the DEX file
        DexBackedDexFile dexFile = new DexBackedDexFile(opcodes, dexData);

        // Create a new DEX pool for writing
        DexPool dexPool = new DexPool(opcodes);

        // Process all classes in the DEX file
        int size = dexFile.getClasses().size();
        int num = 1;
        for (ClassDef classDef : dexFile.getClasses()) {
            // Check if this is the class we want to modify
            if (classDef.getType().equals(className)) {
                // Create a modified class definition with new superclass
                ModifiedClassDef modifiedClassDef = new ModifiedClassDef(classDef, newSuperclassName);
                dexPool.internClass(modifiedClassDef);
            }
            else {
                dexPool.internClass(classDef);
            }
            logger.d(LOG_PROCESS+"  -Modified: "+ num +"/" + size );
            num++;
        }
        logger.d("  -Generate dex... ");
        // Write the modified DEX to memory
        MemoryDataStore dataStore = new MemoryDataStore();
        dexPool.writeTo(dataStore);
        // Return the modified DEX data
        return new R<>(modified, dataStore.getData());
    }

    /**
     * Helper class to modify the superclass of a class definition
     */
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
