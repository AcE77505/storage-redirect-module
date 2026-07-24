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
    public static boolean initAppDirectories(File dataDir, String packageName) {
        String cacheKey = packageName + ":init";
        Boolean cached = dirExistCache.get(cacheKey);
        if (cached != null) {
            XposedBridge.log(TAG + "[" + packageName + "]: initAppDirectories cached hit: " + cached);
            return cached;          // 缓存命中直接返回
        }

        Boolean result = safeCall("initAppDirectories", packageName, () -> {
            File baseDir = new File(dataDir, "Android");
            File data  = new File(baseDir, "data");
            File cache = new File(baseDir, "cache");

            /* 快速路径：已存在，跳过 */
            if (data.exists() && cache.exists()) {
                dirExistCache.put(cacheKey, true);
                return true;
            }

            /* 按需补建 base */
            if (!baseDir.exists() && !baseDir.mkdirs()) return false;
            ensurePermissions(baseDir);

            /* 按需补建 data / cache */
            boolean dataOk  = data.exists()  || (data.mkdirs()  && logMkdir(packageName, "data"));
            boolean cacheOk = cache.exists() || (cache.mkdirs() && logMkdir(packageName, "cache"));

            boolean allOk = dataOk && cacheOk;
            if (allOk) {
                ensurePermissions(data);
                ensurePermissions(cache);
            }
            dirExistCache.put(cacheKey, allOk);
            return allOk;
        });

        boolean ok = result != null && result;
        if (!ok) XposedBridge.log(TAG + "[" + packageName + "]: initAppDirectories FAILED");
        return ok;
    }

    /* 仅打印 mkdirs 成功且真实发生时的日志 */
    public static boolean logMkdir(String pkg, String type) {
        XposedBridge.log(TAG + "[" + pkg + "]: mkdirs " + type);
        return true;
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
            // 基础重定向路径：/data/user/0/包名/Android/{data|cache|files}
            File redirectDir = new File("/storage/emulated/0/Onedroid/" + effectiveSubDir + "/" + packageName);

            // 【针对超星学习通的特殊处理】
            // 如果是学习通且当前请求的是 Files 目录 ("data")，则将路径延伸至 Android/data/files
            if (CHAOXING_PKG.equals(packageName) && "data".equals(subDir)) {
                redirectDir = new File(redirectDir, "files");
            }

            // 统一检查文件夹是否存在，不存在则创建
            if (redirectDir.exists() || redirectDir.mkdirs()) {
                if (!redirectDir.exists()) {
                    ensurePermissions(redirectDir);
                }
                // 如果是超星新创建了 files 目录，打印一条日志方便调试
                if (CHAOXING_PKG.equals(packageName) && "data".equals(subDir)) {
                    XposedBridge.log(TAG + "[" + packageName + "]: target files dir checked/created: " + redirectDir.getAbsolutePath());
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
