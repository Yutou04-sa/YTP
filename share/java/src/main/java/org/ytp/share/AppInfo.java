package org.ytp.share;

public class AppInfo {
    String packageName;
    String label;
    String versionName;
    String description;
    String targetApiVersion;
    boolean disabled = false;

      public AppInfo(){}

  public AppInfo(String packageName, String label) {
        this.packageName = packageName;
        this.label = label;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public String getTargetApiVersion() {
        return targetApiVersion;
    }

    public void setTargetApiVersion(String targetApiVersion) {
        this.targetApiVersion = targetApiVersion;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }
}
