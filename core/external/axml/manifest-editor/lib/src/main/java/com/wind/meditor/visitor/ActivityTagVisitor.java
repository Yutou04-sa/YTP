package com.wind.meditor.visitor;

import com.wind.meditor.property.ModificationProperty;
import com.wind.meditor.utils.Log;

import java.util.Map;

import pxb.android.axml.NodeVisitor;

/**
 * 处理Activity节点的访问者类
 * 负责在AndroidManifest.xml中添加新的Activity节点或替换现有Activity节点
 */
public class ActivityTagVisitor extends NodeVisitor {

    private ModificationProperty.Activity activity;
    private boolean activityAdded = false;
    private boolean isReplacementMode = false;
    private String existingActivityName = null;
    private boolean activityReplaced = false;
    private Map<String, ModificationProperty.Activity> activityReplacementMap;
    private boolean nameChecked = false;

    // 构造函数1：用于添加新Activity
    public ActivityTagVisitor(NodeVisitor nv, ModificationProperty.Activity activity) {
        super(nv);
        this.activity = activity;
        this.isReplacementMode = false;
    }

    // 构造函数2：用于替换现有Activity
    public ActivityTagVisitor(NodeVisitor nv, Map<String, ModificationProperty.Activity> activityReplacementMap) {
        super(nv);
        this.activityReplacementMap = activityReplacementMap;
        this.isReplacementMode = true;
    }

    @Override
    public void attr(String ns, String name, int resourceId, int type, Object obj) {
        if (isReplacementMode) {
            handleReplacementAttribute(ns, name, resourceId, type, obj);
        } else {
            super.attr(ns, name, resourceId, type, obj);
        }
    }

    private void handleReplacementAttribute(String ns, String name, int resourceId, int type, Object obj) {
        // 首次遇到name属性时检查是否需要替换
        if (!nameChecked && "name".equals(name) && obj instanceof String) {
            existingActivityName = (String) obj;
            ModificationProperty.Activity replacementActivity = activityReplacementMap.get(existingActivityName);
            if (replacementActivity != null) {
                this.activity = replacementActivity;
                activityReplacementMap.remove(existingActivityName);
                nameChecked = true;
            }
        }

        // 检查是否为目标Activity
        boolean isTarget = isTargetActivity();
        // 如果是同名Activity，则替换属性
        boolean shouldReplace = shouldReplaceAttribute(name);
        if (shouldReplace) {
            Object newValue = getNewAttributeValue(name);
            if (newValue != null) {
                super.attr(ns, name, resourceId, type, newValue);
                activityReplaced = true;
                return;
            }
        }

        // 保留原有属性（只有在不需要替换时才保留）
        // 注意：这里不能无条件调用super.attr，否则会覆盖已设置的新值
        if (!shouldReplaceAttribute(name)) {
            super.attr(ns, name, resourceId, type, obj);
        }
    }

    @Override
    public NodeVisitor child(String ns, String name) {
        NodeVisitor child = super.child(ns, name);
        return child;
    }

    @Override
    public void end() {
        if (isReplacementMode) {
            // 如果还没有替换任何属性，且是目标Activity，则添加缺失的属性
            if (!activityReplaced && isTargetActivity()) {
                addMissingAttributes();
            }
        } else {
            if (!activityAdded) {
                setActivityAttributes();
            }
        }
        super.end();
    }

    /**
     * 判断是否应该替换指定属性
     */
    private boolean shouldReplaceAttribute(String attributeName) {
        boolean isTarget = isTargetActivity();
        if (!isTarget) {
            return false;
        }
        
        // 目前只支持替换exported属性
        if ("exported".equals(attributeName)) {
            boolean shouldReplace = activity != null && activity.getExported() != null;
            return shouldReplace;
        }
        
        return false;
    }

    /**
     * 获取新属性值
     */
    private Object getNewAttributeValue(String attributeName) {
        if (activity == null) return null;
        
        if ("exported".equals(attributeName)) {
            return activity.getExported() ? 1 : 0; // 1表示true，0表示false
        }
        
        return null;
    }

    /**
     * 判断是否为目标Activity（同名）
     */
    private boolean isTargetActivity() {
        return existingActivityName != null &&
               activity != null &&
               existingActivityName.equals(activity.getName());
    }

    /**
     * 添加新Activity中有但原Activity中没有的属性
     */
    private void addMissingAttributes() {
        if (activity == null) return;

        // 添加缺失的exported属性
        if (activity.getExported() != null) {
            super.attr("http://schemas.android.com/apk/res/android", "exported",
                0x01010010, // android:exported resource id
                18, activity.getExported() ? 1 : 0);
        }
    }

    /**
     * 设置Activity的基本属性（用于添加新模式）
     */
    private void setActivityAttributes() {
        if (activity == null || activity.getName() == null) {
            return;
        }

        // 设置android:name属性
        super.attr("http://schemas.android.com/apk/res/android", "name", 
            0x01010003, // android:name resource id
            3, activity.getName()); // 3 represents TYPE_STRING

        // 设置android:exported属性
        if (activity.getExported() != null) {
            super.attr("http://schemas.android.com/apk/res/android", "exported",
                0x01010010, // android:exported resource id
                18, activity.getExported() ? 1 : 0); // 18 represents TYPE_INT_BOOLEAN
        }
        activityAdded = true;
    }
}