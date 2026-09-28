package org.lsposed.lspd.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import org.apache.commons.lang3.SerializationUtilsX;
import org.lsposed.lspd.impl.LSPDataCallback;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class ModuleConfigManager {

    private static final String TAG = "ModuleConfigManager";
    private static ModuleConfigManager instance = null;

    private SQLiteDatabase db = null;

    private static Path modulePath = null;
    private static File dbPath = null;

    private final Map<Pair<String, Integer>, Map<String, HashMap<String, Object>>> cachedConfig = new ConcurrentHashMap<>();

    private static final String CREATE_MODULES_TABLE = "CREATE TABLE IF NOT EXISTS modules (" +
            "mid integer PRIMARY KEY AUTOINCREMENT," +
            "module_pkg_name text NOT NULL UNIQUE," +
            "apk_path text NOT NULL, " +
            "enabled BOOLEAN DEFAULT 0 " +
            "CHECK (enabled IN (0, 1))" +
            ");";

    private static final String CREATE_SCOPE_TABLE = "CREATE TABLE IF NOT EXISTS scope (" +
            "mid integer," +
            "app_pkg_name text NOT NULL," +
            "PRIMARY KEY (mid, app_pkg_name)," +
            "CONSTRAINT scope_module_constraint" +
            "  FOREIGN KEY (mid)" +
            "  REFERENCES modules (mid)" +
            "  ON DELETE CASCADE" +
            ");";

    private static final String CREATE_CONFIG_TABLE = "CREATE TABLE IF NOT EXISTS configs (" +
            "module_pkg_name text NOT NULL," +
            "user_id integer NOT NULL," +
            "`group` text NOT NULL," +
            "`key` text NOT NULL," +
            "data blob NOT NULL," +
            "PRIMARY KEY (module_pkg_name, user_id, `group`, `key`)," +
            "CONSTRAINT config_module_constraint" +
            "  FOREIGN KEY (module_pkg_name)" +
            "  REFERENCES modules (module_pkg_name)" +
            "  ON DELETE CASCADE" +
            ");";

    private ModuleConfigManager() {
        Path basePath = Paths.get(LSPDataCallback.getInstance().getModuleConfigPath());
        Log.d(TAG, "Module path: " + basePath);
        modulePath = basePath.resolve("modules");
        dbPath = basePath.resolve("modules_config.db").toFile();
        db = openDb();
        initDB();
    }

  public  static ModuleConfigManager getInstance() {
        if (instance == null)  instance = new ModuleConfigManager();
        return instance;
    }

    private static SQLiteDatabase openDb() {
        var params = new SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                .setErrorHandler(sqLiteDatabase -> Log.w(TAG, "database corrupted"));
        params.setSynchronousMode("NORMAL");
        return SQLiteDatabase.openDatabase(dbPath.getAbsoluteFile(), params.build());
    }

    private void initDB() {
        db.setForeignKeyConstraintsEnabled(true);
        int oldVersion = db.getVersion();
        if (oldVersion == 4) {
            // Database is already up to date.
            return;
        }

        //Log.i(TAG, "Initializing/Upgrading database from version " + oldVersion + " to 4");
        db.beginTransaction();
        try {
            db.execSQL(CREATE_MODULES_TABLE);
            db.execSQL(CREATE_SCOPE_TABLE);
            db.execSQL(CREATE_CONFIG_TABLE);
            db.setVersion(4);
            db.setTransactionSuccessful();
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize or upgrade database, transaction rolled back.", e);
        } finally {
            db.endTransaction();
        }
    }

    // ==================== Modules 表操作 ====================
    /**
     * 插入模块记录
     */
    public long upsertModule(String modulePkgName, String apkPath, boolean enabled) {
        String sql = "INSERT INTO modules (module_pkg_name, apk_path, enabled) VALUES (?, ?, ?)";
        db.beginTransaction();
        try {
            Map<String, Object> module = getModuleByPkgName(modulePkgName);
            //更新
            if(module != null){
                long mid = (long) module.get("mid");
                updateModule(mid, modulePkgName, apkPath, enabled);
                return  mid;
            }else {
                SQLiteStatement stmt = db.compileStatement(sql);
                stmt.bindString(1, modulePkgName);
                stmt.bindString(2, apkPath);
                stmt.bindLong(3, enabled ? 1 : 0);
                long rowId = stmt.executeInsert();
                db.setTransactionSuccessful();
               // Log.d(TAG, "Inserted module: " + modulePkgName + ", rowId: " + rowId);
               return  rowId;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to insert module: " + modulePkgName, e);
            return -1;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 更新模块信息
     */
    public boolean updateModule(long mid, String modulePkgName, String apkPath, Boolean enabled) {
        StringBuilder sql = new StringBuilder("UPDATE modules SET ");
        boolean hasSet = false;

        if (modulePkgName != null) {
            sql.append("module_pkg_name = ?");
            hasSet = true;
        }
        if (apkPath != null) {
            if (hasSet) sql.append(", ");
            sql.append("apk_path = ?");
            hasSet = true;
        }
        if (enabled != null) {
            if (hasSet) sql.append(", ");
            sql.append("enabled = ?");
        }

        sql.append(" WHERE mid = ?");

        if (!hasSet) return false;

        db.beginTransaction();
        try {
            SQLiteStatement stmt = db.compileStatement(sql.toString());
            int index = 1;

            if (modulePkgName != null) {
                stmt.bindString(index++, modulePkgName);
            }
            if (apkPath != null) {
                stmt.bindString(index++, apkPath);
            }
            if (enabled != null) {
                stmt.bindLong(index++, enabled ? 1 : 0);
            }
            stmt.bindLong(index, mid);

            int rowsAffected = stmt.executeUpdateDelete();
            db.setTransactionSuccessful();
            //Log.d(TAG, "Updated module mid: " + mid + ", rows affected: " + rowsAffected);
            return rowsAffected > 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to update module mid: " + mid, e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 根据包名查询模块
     */
    public Map<String, Object> getModuleByPkgName(String pkgName) {
        String sql = "SELECT mid, module_pkg_name, apk_path, enabled FROM modules WHERE module_pkg_name = ?";

        try (Cursor cursor = db.rawQuery(sql, new String[]{pkgName})) {
            if (cursor.moveToFirst()) {
                Map<String, Object> module = new HashMap<>();
                module.put("mid", cursor.getLong(0));
                module.put("module_pkg_name", cursor.getString(1));
                module.put("apk_path", cursor.getString(2));
                module.put("enabled", cursor.getInt(3) == 1);
                return module;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query module by pkg name: " + pkgName, e);
        }
        return null;
    }


    /**
     * 插入作用域记录
     */
    public boolean insertScope(long mid, String appPkgName) {
        String sql = "INSERT INTO scope (mid, app_pkg_name) VALUES (?, ?)";
        db.beginTransaction();
        try {
            Map<String, Object> scopeByAppPkgName = getScopeByAppPkgName(appPkgName);
            if(scopeByAppPkgName != null){
                Log.d(TAG, "Extend scope: mid=" + mid+ ", app=" + appPkgName);
                return true;
            }
            SQLiteStatement stmt = db.compileStatement(sql);
            stmt.bindLong(1, mid);
            stmt.bindString(2, appPkgName);
            long rowId = stmt.executeInsert();
            db.setTransactionSuccessful();
           // Log.d(TAG, "Inserted scope: mid=" + mid + ", app=" + appPkgName + ", rowId: " + rowId);
            return rowId > 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to insert scope: mid=" + mid + ", app=" + appPkgName, e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 删除作用域记录
     */
    public boolean deleteScope(long mid, String appPkgName) {
        String sql = "DELETE FROM scope WHERE mid = ? AND app_pkg_name = ?";
        db.beginTransaction();
        try {
            SQLiteStatement stmt = db.compileStatement(sql);
            stmt.bindLong(1, mid);
            stmt.bindString(2, appPkgName);
            int rowsAffected = stmt.executeUpdateDelete();
            db.setTransactionSuccessful();
            //Log.d(TAG, "Deleted scope: mid=" + mid + ", app=" + appPkgName + ", rows affected: " + rowsAffected);
            return rowsAffected > 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete scope: mid=" + mid + ", app=" + appPkgName, e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 根据包名查询作用域
     *
     */
    public Map<String, Object> getScopeByAppPkgName(String appPkgName) {
        String sql = "SELECT * FROM scope WHERE app_pkg_name = ? ";
        try (Cursor cursor = db.rawQuery(sql, new String[]{appPkgName})) {
            if (cursor.moveToFirst()) {
                Map<String, Object> module = new HashMap<>();
                module.put("mid", cursor.getLong(0));
                module.put("app_pkg_name", cursor.getString(1));
                return module;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query scope by app pkg name: " + appPkgName, e);
        }
        return null;
    }

    /**
     * 查询所有作用域包名
     */
    public List<String> getAllScope() {
        String sql = "SELECT app_pkg_name FROM scope";
        List<String> appPkgNames = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                String scope = cursor.getString(0);
                appPkgNames.add(scope);
                Log.d(TAG, "Query scope: " + scope);
            }
        }
        return appPkgNames;
    }

    public HashMap<String, Object> getModulePrefs(String moduleName, int userId, String group) {
        var config = cachedConfig.computeIfAbsent(new Pair<>(moduleName, userId), module -> fetchModuleConfig(module.first, module.second));
        return config.getOrDefault(group, new HashMap<>());
    }

    private
    Map<String, HashMap<String, Object>> fetchModuleConfig(String name, int user_id) {
        var config = new ConcurrentHashMap<String, HashMap<String, Object>>();

        try (Cursor cursor = db.query("configs", new String[]{"`group`", "`key`", "data"},
                "module_pkg_name = ? and user_id = ?", new String[]{name, String.valueOf(user_id)}, null, null, null)) {
            if (cursor == null) {
                Log.e(TAG, "db cache failed");
                return config;
            }
            int groupIdx = cursor.getColumnIndex("group");
            int keyIdx = cursor.getColumnIndex("key");
            int dataIdx = cursor.getColumnIndex("data");
            while (cursor.moveToNext()) {
                var group = cursor.getString(groupIdx);
                var key = cursor.getString(keyIdx);
                var data = cursor.getBlob(dataIdx);
                var object = SerializationUtilsX.deserialize(data);
                if (object == null) continue;
                config.computeIfAbsent(group, g -> new HashMap<>()).put(key, object);
            }
        }
        return config;
    }

    public void updateModulePrefs(String moduleName, int userId, String group, String key, Object value) {
        Map<String, Object> values = new HashMap<>();
        values.put(key, value);
        updateModulePrefs(moduleName, userId, group, values);
    }

    public void updateModulePrefs(String moduleName, int userId, String group, Map<String, Object> values) {
        var config = cachedConfig.computeIfAbsent(new Pair<>(moduleName, userId), module -> fetchModuleConfig(module.first, module.second));
        config.compute(group, (g, prefs) -> {
            HashMap<String, Object> newPrefs = prefs == null ? new HashMap<>() : new HashMap<>(prefs);
            executeInTransaction(() -> {
                for (var entry : values.entrySet()) {
                    var key = entry.getKey();
                    var value = entry.getValue();
                    if (value instanceof Serializable) {
                        newPrefs.put(key, value);
                        var contents = new ContentValues();
                        contents.put("`group`", group);
                        contents.put("`key`", key);
                        contents.put("data", SerializationUtilsX.serialize((Serializable) value));
                        contents.put("module_pkg_name", moduleName);
                        contents.put("user_id", String.valueOf(userId));
                        db.insertWithOnConflict("configs", null, contents, SQLiteDatabase.CONFLICT_REPLACE);
                    } else {
                        newPrefs.remove(key);
                        db.delete("configs", "module_pkg_name=? and user_id=? and `group`=? and `key`=?", new String[]{moduleName, String.valueOf(userId), group, key});
                    }
                }
                var bundle = new Bundle();
                bundle.putSerializable("config", (Serializable) config);
                if (bundle.size() > 1024 * 1024) {
                    throw new IllegalArgumentException("Preference too large");
                }
            });
            return newPrefs;
        });
    }

    public void deleteModulePrefs(String moduleName, int userId, String group) {
        db.delete("configs", "module_pkg_name=? and user_id=? and `group`=?", new String[]{moduleName, String.valueOf(userId), group});
        var config = cachedConfig.getOrDefault(new Pair<>(moduleName, userId), null);
        if (config != null) {
            config.remove(group);
        }
    }

    private <T> void executeInTransaction(Supplier<T> execution) {
        try {
            db.beginTransaction();
            var res = execution.get();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void executeInTransaction(Runnable execution) {
        executeInTransaction((Supplier<Void>) () -> {
            execution.run();
            return null;
        });
    }



    public Path resolveModuleDir(String packageName, String dir) throws IOException {
        var path = modulePath.resolve(packageName).resolve(dir).normalize();
        // Ensure the directory and any necessary parent directories exist.
        path.toFile().mkdirs();
        return path;
    }

    public void ensureModuleFilePath(String path) throws RemoteException {
        if (path == null || path.indexOf(File.separatorChar) >= 0 || ".".equals(path) || "..".equals(path)) {
            throw new RemoteException("Invalid path: " + path);
        }
    }

}
