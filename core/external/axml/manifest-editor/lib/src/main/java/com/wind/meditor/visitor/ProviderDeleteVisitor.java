package com.wind.meditor.visitor;

import com.wind.meditor.utils.NodeValue;

import java.util.ArrayList;
import java.util.List;

import pxb.android.axml.NodeVisitor;

/**
 * 根据 android:authorities 值删除 Provider
 * <p>
 * 实现原理：延迟创建 writer 节点。在遍历到 target authorities 之前缓冲所有 attr 调用，
 * 一旦确定不需要删除（不匹配），立即创建 writer 节点并回放缓冲的 attr。
 * 如果匹配删除规则，则直接丢弃缓冲区，实现 Provider 的"删除"效果。
 */
public class ProviderDeleteVisitor extends NodeVisitor {

    private final NodeVisitor parentWriter;     // application 节点的 writer NodeImpl
    private final List<String> deleteAuthorities;
    private final String elementNs;
    private final String elementName;

    private NodeVisitor writerNode;             // provider 的 writer 节点（延迟创建）
    private boolean shouldDelete = false;
    private boolean decided = false;

    private final List<AttrRecord> pendingAttrs = new ArrayList<>();
    private int lineNumber = -1;

    private static class AttrRecord {
        final String ns;
        final String name;
        final int resourceId;
        final int type;
        final Object obj;

        AttrRecord(String ns, String name, int resourceId, int type, Object obj) {
            this.ns = ns;
            this.name = name;
            this.resourceId = resourceId;
            this.type = type;
            this.obj = obj;
        }
    }

    /**
     * @param parentWriter      application 节点的 writer NodeImpl，用于延迟创建 provider 子节点
     * @param deleteAuthorities 要删除的 authorities 列表（精确匹配）
     * @param ns                元素的命名空间
     * @param name              元素名（provider）
     */
    ProviderDeleteVisitor(NodeVisitor parentWriter, List<String> deleteAuthorities,
                          String ns, String name) {
        this.parentWriter = parentWriter;
        this.deleteAuthorities = deleteAuthorities;
        this.elementNs = ns;
        this.elementName = name;
    }

    @Override
    public void line(int ln) {
        this.lineNumber = ln;
    }

    @Override
    public void attr(String ns, String name, int resourceId, int type, Object obj) {
        if (decided && shouldDelete) {
            return;
        }

        // 检查是否已到 authorities 属性
        if (!decided && NodeValue.Application.Provider.AUTHORITIES.equals(name) && obj instanceof String) {
            String authorities = (String) obj;
            shouldDelete = isAuthorityMatched(authorities);
            decided = true;

            if (shouldDelete) {
                pendingAttrs.clear();
                return;
            }

            // 不删除：创建 writer 节点，回放缓冲 attr，然后写入当前 attr
            ensureWriterCreated();
            flushPendingAttrs();
            writerNode.attr(ns, name, resourceId, type, obj);
            return;
        }

        if (!decided) {
            // 还没遇到 authorities，缓冲起来
            pendingAttrs.add(new AttrRecord(ns, name, resourceId, type, obj));
            return;
        }

        // 已决定不删除，直接转发
        if (writerNode == null) {
            ensureWriterCreated();
        }
        writerNode.attr(ns, name, resourceId, type, obj);
    }

    @Override
    public NodeVisitor child(String ns, String name) {
        // 此时所有 attr 已完成处理，decided 必然为 true
        // 除非 authorities 根本不在属性列表中（极端情况），此时当作不删除处理
        if (!decided) {
            shouldDelete = false;
            decided = true;
            ensureWriterCreated();
            flushPendingAttrs();
        }
        if (shouldDelete) {
            return null; // reader 会替换为 EMPTY_VISITOR，跳过整个子树
        }
        if (writerNode != null) {
            return writerNode.child(ns, name);
        }
        return null;
    }

    @Override
    public void end() {
        // 如果从头到尾没遇到 authorities 属性，保持该 provider
        if (!decided) {
            shouldDelete = false;
            decided = true;
            ensureWriterCreated();
            flushPendingAttrs();
        }

        if (!shouldDelete && writerNode != null) {
            writerNode.end();
        }
        // 如果 shouldDelete，writerNode 未创建，直接丢弃
    }

    private boolean isAuthorityMatched(String authorities) {
        for (String delAuth : deleteAuthorities) {
            if (delAuth.equals(authorities)) {
                return true;
            }
        }
        return false;
    }

    private void ensureWriterCreated() {
        if (writerNode == null) {
            writerNode = parentWriter.child(elementNs, elementName);
            if (lineNumber >= 0) {
                writerNode.line(lineNumber);
            }
        }
    }

    private void flushPendingAttrs() {
        for (AttrRecord ar : pendingAttrs) {
            writerNode.attr(ar.ns, ar.name, ar.resourceId, ar.type, ar.obj);
        }
        pendingAttrs.clear();
    }
}
