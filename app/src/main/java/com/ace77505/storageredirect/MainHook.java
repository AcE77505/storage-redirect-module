package com.ace77505.storageredirect;

import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {

    public static final String SYSTEM_FRAMEWORK = "android";
    public static final String TAG = "StorageRedirect";

    // 超星学习通包名常量
    private static final String CHAOXING_PKG = "com.chaoxing.mobile";

    /* ---------------- 缓存容器 ---------------- */
    public static final ConcurrentSkipListSet<String> hookedPackages = new ConcurrentSkipListSet<>();
    public static final ConcurrentHashMap<String, File> dataDirCache   = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, Boolean> dirExistCache = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, File> redirectDirCache = new ConcurrentHashMap<>();

    /* ---------------- 工具方法 ---------------- */
    public static boolean shouldHook(String pkg) {
        return !SYSTEM_FRAMEWORK.equals(pkg) && !hookedPackages.contains(pkg);
    }

    public interface SafeBlock<T> { T run(); }

    public static <T> T safeCall(String op, String pkg, SafeBlock<T> block) {
        try { return block.run(); }
        catch (SecurityException e) {
            XposedBridge.log(TAG + "[" + pkg + "]: SecurityException in " + op + " - " + e.getMessage());
        } catch (NoSuchMethodError | Exception e) {
            XposedBridge.log(TAG + "[" + pkg + "]: " + e.getClass().getSimpleName() + " in " + op + " - " + e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void ensurePermissions(File dir) {
        try {
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        } catch (SecurityException ignore) {}
    }

    /* ---------------- 目录初始化 ---------------- */
    /* 只保证 <dataDir>/Android 这一层存在。
     * 具体子目录（files / cache，以及学习通专用的 data）由 getRedirectDir() 按需创建，
     * 不再像 1.1 那样无条件预建 Android/data + Android/cache ——
     * 那对非学习通应用来说就是两个永远不会被任何 API 返回的空目录。 */
    public static boolean initAppDirectories(File dataDir, String packageName) {
        String cacheKey = packageName + ":init";
        Boolean cached = dirExistCache.get(cacheKey);
        if (cached != null) {
            if (!cached) return false;
            // 命中缓存也要确认目录没被外部清理掉
            if (new File(dataDir, "Android").isDirectory()) return true;
            XposedBridge.log(TAG + "[" + packageName + "]: Android base dir missing, re-init");
            dirExistCache.remove(cacheKey);
        }

        File baseDir = new File(dataDir, "Android");
        Boolean result = safeCall("initAppDirectories", packageName, () -> {
            if (!baseDir.isDirectory() && !baseDir.mkdirs()) return false;
            ensurePermissions(baseDir);
            return true;
        });

        boolean ok = result != null && result;
        dirExistCache.put(cacheKey, ok);
        if (!ok) XposedBridge.log(TAG + "[" + packageName + "]: initAppDirectories FAILED");
        return ok;
    }

    public static File getAppDataDir(Object context, String packageName) {
        if (context == null) return null;
        File cached = dataDirCache.get(packageName);
        if (cached != null && cached.exists()) return cached;
        return safeCall("getAppDataDir", packageName, () -> {
            File dir = (File) XposedHelpers.callMethod(context, "getDataDir");
            if (dir != null && dir.exists()) dataDirCache.put(packageName, dir);
            return dir;
        });
    }

    public static File getRedirectDir(Object context, String subDir, String packageName) {
        File dataDir = getAppDataDir(context, packageName);
        if (dataDir == null) return null;
        if (!initAppDirectories(dataDir, packageName)) return null;

        String cacheKey = packageName + ":" + subDir;
        File cached = redirectDirCache.get(cacheKey);
        if (cached != null && cached.exists()) return cached;

        return safeCall("getRedirectDir", packageName, () -> {
            // 解析实际子目录：
            //   - 非学习通应用：getExternalFilesDir → Android/files，不走 Android/data
            //   - 学习通应用：保持原有行为 → Android/data → 再嵌套 files
            //   - cache 不受影响 → Android/cache
            String effectiveSubDir = subDir;
            if ("data".equals(subDir) && !CHAOXING_PKG.equals(packageName)) {
                effectiveSubDir = "files";
            }
            // 基础重定向路径：/data/user/0/包名/Android/{files|cache}
            File redirectDir = new File(new File(dataDir, "Android"), effectiveSubDir);

            // 【针对超星学习通的特殊处理】路径延伸至 Android/data/files
            if (CHAOXING_PKG.equals(packageName) && "data".equals(subDir)) {
                redirectDir = new File(redirectDir, "files");
            }

            // 只创建真正要用到的目录
            boolean created = !redirectDir.isDirectory() && redirectDir.mkdirs();
            if (redirectDir.isDirectory()) {
                ensurePermissions(redirectDir);
                if (created) {
                    XposedBridge.log(TAG + "[" + packageName + "]: mkdir " + redirectDir.getAbsolutePath());
                }
                redirectDirCache.put(cacheKey, redirectDir);
                return redirectDir;
            }
            return null;
        });
    }

    public static File getRedirectDirFast(String packageName, String subDir) {
        String cacheKey = packageName + ":" + subDir;
        File f = redirectDirCache.get(cacheKey);
        return (f != null && f.exists()) ? f : null;
    }

    public static XC_MethodHook createHookCallback(String methodName, String subDir, String packageName) {
        return new XC_MethodHook() {
            public final boolean isArray = methodName.endsWith("s") || methodName.contains("Dirs");
            @Override protected void afterHookedMethod(MethodHookParam param) {
                File cached = getRedirectDirFast(packageName, subDir);
                if (cached != null) {
                    param.setResult(isArray ? new File[]{cached} : cached);
                    return;
                }
                File dir = getRedirectDir(param.thisObject, subDir, packageName);
                if (dir != null) param.setResult(isArray ? new File[]{dir} : dir);
            }
        };
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String packageName = lpparam.packageName;
        if (!shouldHook(packageName)) return;
        try {
            hookStorageMethods(lpparam, packageName);
            hookedPackages.add(packageName);
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Error hooking " + packageName + ": " + e.getMessage());
        }
    }

    public static void hookStorageMethods(XC_LoadPackage.LoadPackageParam lpparam, String packageName) {
        ClassLoader cl = lpparam.classLoader;
        class HookMethod {
            final String name;
            final Class<?> paramType;
            final String subDir;
            HookMethod(String n, Class<?> p, String s) { name = n; paramType = p; subDir = s; }
        }
        List<HookMethod> list = java.util.Arrays.asList(
                new HookMethod("getExternalFilesDir",  String.class, "data"),
                new HookMethod("getExternalFilesDirs", String.class, "data"),
                new HookMethod("getExternalCacheDir",  null,         "cache"),
                new HookMethod("getExternalCacheDirs", null,         "cache")
        );
        for (HookMethod m : list) {
            try {
                XC_MethodHook cb = createHookCallback(m.name, m.subDir, packageName);
                if (m.paramType != null)
                    XposedHelpers.findAndHookMethod("android.app.ContextImpl", cl, m.name, m.paramType, cb);
                else
                    XposedHelpers.findAndHookMethod("android.app.ContextImpl", cl, m.name, cb);
            } catch (NoSuchMethodError ignore) {
                XposedBridge.log(TAG + ": Method " + m.name + " not found, skipping");
            } catch (Throwable e) {
                XposedBridge.log(TAG + ": Error hooking " + m.name + ": " + e.getMessage());
            }
        }
    }
}
