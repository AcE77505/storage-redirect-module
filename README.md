# StorageRedirect

一个轻量级 Xposed 模块，**把应用的外部存储目录重定向到自身私有数据目录**，从而彻底绕过 Android 11+ 默认的 FUSE 转发层，获得原生速度；Android 10 及以下亦可用。

---

## 重定向原理

在 `ContextImpl` 层面对以下 4 个方法进行 **无感知拦截**：

- `getExternalFilesDir(String)`
- `getExternalFilesDirs(String)`
- `getExternalCacheDir()`
- `getExternalCacheDirs()`

当应用调用上述 API 时，模块立即返回 **提前创建好的私有目录**， **直接命中 `/data/user/0/<package_name>/Android/data|cache`**。

---

## 重定向目标路径

| 原 API 返回值（未重定向） | 重定向后真实路径 |
|---|---|
| `/sdcard/Android/data/<package_name>/files` | `/data/user/0/<package_name>/Android/data` |
| `/sdcard/Android/data/<package_name>/cache` | `/data/user/0/<package_name>/Android/cache` |

&gt; 路径随多用户/工作资料库自动适配，无需手动干预。

---

## 使用方法

1. 在 **Lsposed / EdXposed 勾选列表里仅勾选你需要重定向的应用**  
   ❌ **禁止勾选「系统框架（android）」**！
2. 重启目标应用即可生效，**无需额外配置**。
3. 卸载模块或取消勾选即可恢复原路径。

---

## 系统要求

- Android 5.0+  (9.0及以下未经测试)
- 已正确安装并激活的 **Lsposed 框架**
