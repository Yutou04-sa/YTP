package com.wind.meditor.visitor;

import com.wind.meditor.property.AttributeMapper;
import com.wind.meditor.property.PermissionMapper;
import com.wind.meditor.utils.NodeValue;
import com.wind.meditor.utils.PermissionType;
import pxb.android.axml.NodeVisitor;

import java.util.Arrays;

import static java.util.stream.Collectors.joining;

public class ApplicationComponentTagVisitor extends NodeVisitor {
    private final PermissionMapper permissionMapper;
    private final AttributeMapper<String> authorityMapper;
    private final AttributeMapper<String> componentNameMapper;

    ApplicationComponentTagVisitor(NodeVisitor nv,
                                   PermissionMapper permissionMapper,
                                   AttributeMapper<String> authorityMapper,
                                   AttributeMapper<String> componentNameMapper) {
        super(nv);
        this.permissionMapper = permissionMapper;
        this.authorityMapper = authorityMapper;
        this.componentNameMapper = componentNameMapper;
    }

    @Override
    public void attr(String ns, String name, int resourceId, int type, Object obj) {
        if (obj instanceof String) {
            if (componentNameMapper != null && isComponentName(name)) {
                obj = componentNameMapper.map((String) obj);
            }
            if (isPermissionTag(name) && permissionMapper != null) {
                obj = permissionMapper.map(PermissionType.COMPONENT_PERMISSION, (String) obj);
            }
            if (NodeValue.Application.Provider.AUTHORITIES.equals(name) && authorityMapper != null) {
                obj = mapAuthorities((String) obj);
            }
        }
        super.attr(ns, name, resourceId, type, obj);
    }

    private boolean isComponentName(String name) {
        return NodeValue.Application.Component.NAME.equals(name) || "targetActivity".equals(name);
    }

    private Object mapAuthorities(String authorities) {
        return !authorities.contains(";") ? authorityMapper.map(authorities) :
                Arrays.stream(authorities.split(";")).map(s -> authorityMapper.map(s.trim())).collect(joining("; "));
    }

    private boolean isPermissionTag(String name) {
        return name.equals(NodeValue.Application.Component.PERMISSION) ||
               name.equals(NodeValue.Application.Provider.READ_PERMISSION) ||
               name.equals(NodeValue.Application.Provider.WRITE_PERMISSION);
    }
}
