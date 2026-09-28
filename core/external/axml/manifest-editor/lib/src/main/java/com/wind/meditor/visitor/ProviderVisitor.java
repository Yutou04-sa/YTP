package com.wind.meditor.visitor;

import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;

import java.util.ArrayList;
import java.util.List;

import pxb.android.axml.NodeVisitor;

public class ProviderVisitor extends ModifyAttributeVisitor{
    ProviderVisitor(NodeVisitor nv, ModificationProperty.Provider provider) {
        super(nv, convertToAttr(provider), true);

        // Create meta-data children
        List<ModificationProperty.MetaData> metaDataList = provider.getMetaDataList();
        if (metaDataList != null) {
            for (ModificationProperty.MetaData md : metaDataList) {
                NodeVisitor mdVisitor = super.child(null, "meta-data");
                List<AttributeItem> attrList = new ArrayList<>();
                attrList.add(new AttributeItem("name", md.getName()));
                if (md.getValue() != null) {
                    attrList.add(new AttributeItem("value", md.getValue()));
                }
                if (md.getResourceId() > 0) {
                    attrList.add(new AttributeItem("resource", md.getResourceId())
                            .setType(com.wind.meditor.utils.TypedValue.TYPE_REFERENCE));
                }
                new ModifyAttributeVisitor(mdVisitor, attrList, true);
            }
        }

        // Create intent-filter child (only if filterNameValue is set)
        if (provider.getFilterNameValue() != null) {
            NodeVisitor intentFilter = super.child(null, "intent-filter");
            NodeVisitor action = intentFilter.child(null, "action");

            List<AttributeItem> list = new ArrayList<>();
            list.add(new AttributeItem("name", provider.getFilterNameValue()));
            new ModifyAttributeVisitor(action, list,true);
        }
    }

    private static List<AttributeItem> convertToAttr(ModificationProperty.Provider provider) {
        if (provider == null) {
            return null;
        }
        ArrayList<AttributeItem> list = new ArrayList<>();
        for (String keys : provider.getNameValue().keySet()){
           list.add(new AttributeItem(keys, provider.getNameValue().get(keys)));
        }
        return list;
    }
}
