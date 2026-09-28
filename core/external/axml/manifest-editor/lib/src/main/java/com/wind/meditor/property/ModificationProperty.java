package com.wind.meditor.property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 修改的参数
 *
 * @author windysha
 */
public class ModificationProperty {
    
    private List<String> usesPermissionList = new ArrayList<>();
    private List<MetaData> metaDataList = new ArrayList<>();
    private List<MetaData> deleteMetaDataList = new ArrayList<>();

    private List<AttributeItem> applicationAttributeList = new ArrayList<>();
    private List<AttributeItem> manifestAttributeList = new ArrayList<>();
    private List<AttributeItem> usesSdkAttributeList = new ArrayList<>();
    private List<Activity> activityList = new ArrayList<>();

    private PermissionMapper permissionMapper;
    private AttributeMapper<String> providerAuthorityMapper;
    private AttributeMapper<String> componentNameMapper;
    private List<Provider> providerList = new ArrayList<>();
    private List<String> deleteProviderAuthorities = new ArrayList<>();

    public List<String> getUsesPermissionList() {
        return usesPermissionList;
    }

    public ModificationProperty addUsesPermission(String permissionName) {
        usesPermissionList.add(permissionName);
        return this;
    }

    public List<AttributeItem> getApplicationAttributeList() {
        return applicationAttributeList;
    }

    public ModificationProperty addApplicationAttribute(AttributeItem item) {
        applicationAttributeList.add(item);
        return this;
    }

    public ModificationProperty addProvider(HashMap<String,String> nameValue,String filterNameValue){
        providerList.add(new Provider(nameValue,filterNameValue));
        return this;
    }

    public ModificationProperty addProvider(HashMap<String,String> nameValue,String filterNameValue, List<MetaData> metaDataList){
        Provider provider = new Provider(nameValue,filterNameValue);
        provider.setMetaDataList(metaDataList);
        providerList.add(provider);
        return this;
    }

    public ModificationProperty addActivity(Activity activity) {
        activityList.add(activity);
        return this;
    }

    public List<MetaData> getMetaDataList() {
        return metaDataList;
    }

    public ModificationProperty addMetaData(MetaData data) {
        metaDataList.add(data);
        return this;
    }

    public List<AttributeItem> getManifestAttributeList() {
        return manifestAttributeList;
    }

    public ModificationProperty addManifestAttribute(AttributeItem item) {
        manifestAttributeList.add(item);
        return this;
    }

    public List<AttributeItem> getUsesSdkAttributeList() {
        return usesSdkAttributeList;
    }

    public ModificationProperty addUsesSdkAttribute(AttributeItem item) {
        usesSdkAttributeList.add(item);
        return this;
    }

    public List<MetaData> getDeleteMetaDataList() {
        return deleteMetaDataList;
    }

    public List<String> getDeleteProviderAuthorities() {
        return deleteProviderAuthorities;
    }

    public ModificationProperty addDeleteProviderAuthorities(String authorities) {
        this.deleteProviderAuthorities.add(authorities);
        return this;
    }

    public List<Provider> getProviderList(){
        return providerList;
    }

    public List<Activity> getActivityList() {
        return activityList;
    }

    public ModificationProperty addDeleteMetaData(String name) {
        this.deleteMetaDataList.add(new MetaData(name, ""));
        return this;
    }

    public PermissionMapper getPermissionMapper() {
        return permissionMapper;
    }

    public ModificationProperty setPermissionMapper(PermissionMapper mapper) {
        this.permissionMapper = mapper;
        return this;
    }

    public AttributeMapper<String> getAuthorityMapper() {
        return providerAuthorityMapper;
    }

    public ModificationProperty setAuthorityMapper(AttributeMapper<String> mapper) {
        this.providerAuthorityMapper = mapper;
        return this;
    }

    public AttributeMapper<String> getComponentNameMapper() {
        return componentNameMapper;
    }

    public ModificationProperty setComponentNameMapper(AttributeMapper<String> mapper) {
        this.componentNameMapper = mapper;
        return this;
    }

    public static class MetaData {
        private String name;
        private String value;
        private int resourceId;

        public MetaData(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public MetaData(String name, String value, int resourceId) {
            this.name = name;
            this.value = value;
            this.resourceId = resourceId;
        }

        public String getName() {
            return name;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public int getResourceId() {
            return resourceId;
        }

        public void setResourceId(int resourceId) {
            this.resourceId = resourceId;
        }
    }

    public static class Provider{
        private HashMap<String,String> nameValue;
        private String filterNameValue;
        private List<MetaData> metaDataList;
        public Provider(HashMap<String,String> nameValue,String filterNameValue){
            this.nameValue = nameValue;
            this.filterNameValue = filterNameValue;
        }
        public HashMap<String,String> getNameValue(){
            return nameValue;
        }
        public String getFilterNameValue(){
            return filterNameValue;
        }
        public List<MetaData> getMetaDataList() {
            return metaDataList;
        }
        public void setMetaDataList(List<MetaData> metaDataList) {
            this.metaDataList = metaDataList;
        }
    }

    public static class Activity {
        private String name;
        private Boolean exported;


        public Activity(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Boolean getExported() {
            return exported;
        }

        public void setExported(Boolean exported) {
            this.exported = exported;
        }
    }
}
