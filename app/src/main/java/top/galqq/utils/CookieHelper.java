package top.galqq.utils;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import top.galqq.hook.CookieHookManager;

/**
 * Cookie 获取器
 * 从 QQ 运行时获取认证 Cookie，用于 API 请求
 * 
 * 获取优先级：
 * 1. 内存缓存（通过Hook获取）
 * 2. SQLite数据库
 * 3. WebView CookieManager
 */
public class CookieHelper {

    private static final String TAG = "GalQQ.CookieHelper";
    
    /**
     * Cookie获取来源枚举
     */
    public enum CookieSource {
        MEMORY,     // 从内存Hook获取
        SQLITE,     // 从SQLite数据库获取
        WEBVIEW,    // 从WebView CookieManager获取
        FAILED      // 获取失败
    }
    
    // 最后一次获取的来源
    private static CookieSource sLastSource = CookieSource.FAILED;
    
    // QQ 空间域名
    private static final String QZONE_DOMAIN = "qzone.qq.com";
    
    // QQ WebView Cookie 数据库可能的相对路径
    private static final String[] COOKIE_DB_RELATIVE_PATHS = {
        "app_webview_tool/Default/Cookies",
        "app_webview/Default/Cookies",
        "app_xwalk/Default/Cookies",
        "databases/webview.db"
    };

    // 调试开关：禁用SQLite兜底方案
    private static final boolean DISABLE_SQLITE_FALLBACK = false;
    
    /**
     * 获取所有需要的 Cookie 字符串
     * @param context 上下文
     * @return Cookie 字符串，格式如 "uin=xxx; skey=xxx; p_uin=xxx; p_skey=xxx"
     */
    public static String getCookies(Context context) {
        // 打印当前内存缓存状态（调试用）
        XposedBridge.log(TAG + ": ========== Cookie获取开始 ==========");
        XposedBridge.log(TAG + ": [DEBUG] 内存缓存状态:");
        XposedBridge.log(TAG + ":   skey: " + (CookieHookManager.getCachedSkey() != null ? "有值" : "空"));
        XposedBridge.log(TAG + ":   p_skey: " + (CookieHookManager.getCachedPSkey() != null ? "有值" : "空"));
        XposedBridge.log(TAG + ":   uin: " + (CookieHookManager.getCachedUin() != null ? CookieHookManager.getCachedUin() : "空"));
        XposedBridge.log(TAG + ":   p_uid: " + (CookieHookManager.getCachedPUid() != null ? "有值" : "空"));
        XposedBridge.log(TAG + ":   isCacheValid: " + CookieHookManager.isCacheValid());
        
        // 方法0：如果内存缓存无效，尝试主动触发Cookie获取
        if (!CookieHookManager.isCacheValid()) {
            XposedBridge.log(TAG + ": [TRIGGER] 内存缓存无效，尝试主动触发获取...");
            triggerCookieFetch(context);
            
            // 再次检查缓存状态
            XposedBridge.log(TAG + ": [DEBUG] 触发后内存缓存状态:");
            XposedBridge.log(TAG + ":   skey: " + (CookieHookManager.getCachedSkey() != null ? "有值" : "空"));
            XposedBridge.log(TAG + ":   p_skey: " + (CookieHookManager.getCachedPSkey() != null ? "有值" : "空"));
            XposedBridge.log(TAG + ":   uin: " + (CookieHookManager.getCachedUin() != null ? CookieHookManager.getCachedUin() : "空"));
        }
        
        // 方法1：优先从内存缓存获取（通过Hook）
        try {
            if (CookieHookManager.isCacheValid()) {
                String memoryCookies = buildCookiesFromMemory();
                if (memoryCookies != null && !memoryCookies.isEmpty()) {
                    sLastSource = CookieSource.MEMORY;
                    if (CookieHookManager.isCachePossiblyExpired()) {
                        XposedBridge.log(TAG + ": [MEMORY] Cookie可能已过期，但仍然使用");
                    }
                    XposedBridge.log(TAG + ": [MEMORY] ✓ 从内存缓存获取到 Cookie: " + memoryCookies.length() + " 字符");
                    XposedBridge.log(TAG + ": ========== Cookie获取完成 ==========");
                    return memoryCookies;
                }
            } else {
                XposedBridge.log(TAG + ": [MEMORY] ✗ 内存缓存无效");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [MEMORY] 内存Cookie获取失败: " + t.getMessage());
        }
        
        // 方法2：SQLite兜底（可禁用）
        if (!DISABLE_SQLITE_FALLBACK) {
            try {
                String sqliteCookies = getCookiesFromSqlite(context);
                if (sqliteCookies != null && !sqliteCookies.isEmpty() && sqliteCookies.contains("p_skey")) {
                    sLastSource = CookieSource.SQLITE;
                    XposedBridge.log(TAG + ": [SQLITE] 从数据库获取到 Cookie: " + sqliteCookies.length() + " 字符");
                    return sqliteCookies;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": [SQLITE] Cookie获取失败: " + t.getMessage());
            }
        } else {
            XposedBridge.log(TAG + ": [SQLITE] ⚠ SQLite兜底已禁用（调试模式）");
        }
        
        // 方法3：尝试从 WebView CookieManager 获取完整 Cookie
        try {
            android.webkit.CookieManager webCookieManager = android.webkit.CookieManager.getInstance();
            String allCookies = webCookieManager.getCookie("https://" + QZONE_DOMAIN);
            if (allCookies != null && !allCookies.isEmpty()) {
                sLastSource = CookieSource.WEBVIEW;
                XposedBridge.log(TAG + ": [WEBVIEW] 获取到完整 Cookie: " + allCookies.length() + " 字符");
                return allCookies;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [WEBVIEW] Cookie获取失败: " + t.getMessage());
        }
        
        // 方法4：降级手动拼接 Cookie
        StringBuilder cookies = new StringBuilder();
        
        String uin = getPUin(context);
        String skey = getSkey(context);
        String pSkey = getPSkey(context);
        
        // uin 字段（不带 o 前缀）
        if (uin != null && !uin.isEmpty()) {
            String cleanUin = uin.startsWith("o") ? uin.substring(1) : uin;
            cookies.append("uin=o").append(cleanUin).append("; ");
        }
        
        if (skey != null && !skey.isEmpty()) {
            cookies.append("skey=").append(skey).append("; ");
        }
        
        // p_uin 字段（带 o 前缀）
        if (uin != null && !uin.isEmpty()) {
            String cleanUin = uin.startsWith("o") ? uin.substring(1) : uin;
            cookies.append("p_uin=o").append(cleanUin).append("; ");
        }
        
        if (pSkey != null && !pSkey.isEmpty()) {
            cookies.append("p_skey=").append(pSkey).append("; ");
        }
        
        // 移除末尾的 "; "
        String result = cookies.toString();
        if (result.endsWith("; ")) {
            result = result.substring(0, result.length() - 2);
        }
        
        if (result.isEmpty()) {
            sLastSource = CookieSource.FAILED;
            XposedBridge.log(TAG + ": [FAILED] 所有方法都无法获取Cookie");
        }
        
        return result;
    }
    
    /**
     * 主动触发Cookie获取（调用QQ的方法来触发Hook）
     */
    private static void triggerCookieFetch(Context context) {
        String cachedUin = CookieHookManager.getCachedUin();
        
        // 如果已经有完整的缓存，不需要触发
        if (CookieHookManager.isCacheValid()) {
            return;
        }
        
        // 优先尝试使用缓存的TicketManager实例
        if (cachedUin != null && !cachedUin.isEmpty()) {
            XposedBridge.log(TAG + ": [TRIGGER] 已有缓存UIN: " + cachedUin + "，尝试主动获取skey/p_skey");
            
            // 方法0：使用缓存的TicketManager实例（最可靠）
            if (CookieHookManager.getCachedTicketManager() != null) {
                XposedBridge.log(TAG + ": [TRIGGER] 使用缓存的TicketManager实例");
                if (CookieHookManager.fetchSkeyAndPskeyFromTicketManager(cachedUin)) {
                    XposedBridge.log(TAG + ": [TRIGGER] ✓ 从缓存TicketManager获取成功");
                    return;
                }
            } else {
                XposedBridge.log(TAG + ": [TRIGGER] TicketManager实例未缓存，尝试其他方法");
            }
            
            tryFetchSkeyAndPskey(context, cachedUin);
            return;
        }
        
        // 尝试从多个来源获取UIN
        String uin = null;
        
        // 方法1：通过AppRuntimeHelper获取（模仿QAuxiliary）
        try {
            Object appRuntime = AppRuntimeHelper.getAppRuntime(context);
            if (appRuntime != null) {
                Object uinObj = XposedHelpers.callMethod(appRuntime, "getCurrentAccountUin");
                if (uinObj != null) {
                    uin = String.valueOf(uinObj);
                    XposedBridge.log(TAG + ": [TRIGGER] 从AppRuntime获取到UIN: " + uin);
                    // 同时更新CookieHookManager的缓存
                    CookieHookManager.setCachedUin(uin);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TRIGGER] AppRuntime获取UIN失败: " + t.getMessage());
        }
        
        // 方法2：从getCurrentUin获取
        if (uin == null || uin.isEmpty()) {
            uin = getCurrentUin(context);
            if (uin != null && !uin.isEmpty()) {
                CookieHookManager.setCachedUin(uin);
            }
        }
        
        // 方法3：从SQLite的p_uin获取（不是降级，只是获取UIN值）
        if (uin == null || uin.isEmpty()) {
            uin = getUinFromSqlite(context);
            if (uin != null && !uin.isEmpty()) {
                CookieHookManager.setCachedUin(uin);
            }
        }
        
        if (uin == null || uin.isEmpty()) {
            XposedBridge.log(TAG + ": [TRIGGER] 无法获取UIN，跳过主动触发");
            return;
        }
        
        XposedBridge.log(TAG + ": [TRIGGER] 尝试主动触发Cookie获取, uin=" + uin);
        
        // 尝试通过多种方式获取TicketManager
        Object appRuntime = null;
        
        // 方式1：通过AppRuntimeHelper获取AppRuntime
        try {
            appRuntime = AppRuntimeHelper.getAppRuntime(context);
            if (appRuntime != null) {
                Object ticketManager = XposedHelpers.callMethod(appRuntime, "getManager", 2);
                if (ticketManager != null) {
                    triggerWithTicketManager(ticketManager, uin);
                    return;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TRIGGER] 通过AppRuntime获取TicketManager失败: " + t.getMessage());
        }
        
        // 方式2：通过HostInfo.getApplication
        try {
            android.app.Application app = HostInfo.getApplication();
            if (app != null) {
                Object runtime = XposedHelpers.callMethod(app, "getRuntime");
                Object ticketManager = XposedHelpers.callMethod(runtime, "getManager", 2);
                if (ticketManager != null) {
                    triggerWithTicketManager(ticketManager, uin);
                    return;
                }
            }
        } catch (Throwable t) {
            // 继续
        }
        
        XposedBridge.log(TAG + ": [TRIGGER] 无法获取TicketManager，等待QQ自动调用");
    }
    
    /**
     * 尝试获取skey和p_skey（当已有UIN时）
     */
    private static void tryFetchSkeyAndPskey(Context context, String uin) {
        // 方式1：通过AppRuntimeHelper获取AppRuntime
        try {
            Object appRuntime = AppRuntimeHelper.getAppRuntime(context);
            if (appRuntime != null) {
                Object ticketManager = XposedHelpers.callMethod(appRuntime, "getManager", 2);
                if (ticketManager != null) {
                    triggerWithTicketManager(ticketManager, uin);
                    return;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TRIGGER] 通过AppRuntime获取TicketManager失败: " + t.getMessage());
        }
        
        // 方式2：通过HostInfo.getApplication
        try {
            android.app.Application app = HostInfo.getApplication();
            if (app != null) {
                Object runtime = XposedHelpers.callMethod(app, "getRuntime");
                Object ticketManager = XposedHelpers.callMethod(runtime, "getManager", 2);
                if (ticketManager != null) {
                    triggerWithTicketManager(ticketManager, uin);
                    return;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TRIGGER] 通过HostInfo获取TicketManager失败: " + t.getMessage());
        }
        
        XposedBridge.log(TAG + ": [TRIGGER] 无法获取TicketManager，等待QQ自动调用");
    }
    
    /**
     * 使用TicketManager触发Cookie获取
     */
    private static void triggerWithTicketManager(Object ticketManager, String uin) {
        XposedBridge.log(TAG + ": [TRIGGER] TicketManager类型: " + ticketManager.getClass().getName());
        
        try {
            Object skey = XposedHelpers.callMethod(ticketManager, "getSkey", uin);
            XposedBridge.log(TAG + ": [TRIGGER] getSkey返回类型: " + (skey != null ? skey.getClass().getName() : "null"));
            if (skey instanceof String && !((String) skey).isEmpty()) {
                CookieHookManager.setCachedSkey((String) skey);
                String preview = ((String) skey).length() > 10 ? ((String) skey).substring(0, 10) + "..." : (String) skey;
                XposedBridge.log(TAG + ": [TRIGGER] ✓ getSkey调用成功: " + preview);
            } else {
                XposedBridge.log(TAG + ": [TRIGGER] ✗ getSkey调用成功，但返回值为空或非String");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TRIGGER] getSkey调用失败: " + t.getClass().getName() + ": " + t.getMessage());
        }
        
        try {
            Object pskey = XposedHelpers.callMethod(ticketManager, "getPskey", uin, QZONE_DOMAIN);
            XposedBridge.log(TAG + ": [TRIGGER] getPskey返回类型: " + (pskey != null ? pskey.getClass().getName() : "null"));
            if (pskey instanceof String && !((String) pskey).isEmpty()) {
                CookieHookManager.setCachedPSkey((String) pskey);
                String preview = ((String) pskey).length() > 10 ? ((String) pskey).substring(0, 10) + "..." : (String) pskey;
                XposedBridge.log(TAG + ": [TRIGGER] ✓ getPskey调用成功: " + preview);
            } else {
                XposedBridge.log(TAG + ": [TRIGGER] ✗ getPskey调用成功，但返回值为空或非String");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [TRIGGER] getPskey调用失败: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
    
    /**
     * 从SQLite数据库获取UIN（仅用于获取UIN值，不是降级方案）
     */
    private static String getUinFromSqlite(Context context) {
        try {
            File dbFile = findCookieDatabase(context);
            if (dbFile == null) return null;
            
            SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor cursor = db.rawQuery("SELECT value FROM cookies WHERE name='p_uin' AND host_key LIKE ? LIMIT 1", 
                new String[]{"%" + QZONE_DOMAIN});
            
            String uin = null;
            if (cursor.moveToFirst()) {
                uin = cursor.getString(0);
                if (uin != null && uin.startsWith("o")) {
                    uin = uin.substring(1);
                }
            }
            cursor.close();
            db.close();
            
            if (uin != null) {
                XposedBridge.log(TAG + ": [TRIGGER] 从SQLite获取到UIN: " + uin);
            }
            return uin;
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * 从内存缓存构建Cookie字符串
     * 格式: uin=o{uin}; skey={skey}; p_uin=o{uin}; p_uid={p_uid}; p_skey={p_skey}
     */
    private static String buildCookiesFromMemory() {
        String skey = CookieHookManager.getCachedSkey();
        String pSkey = CookieHookManager.getCachedPSkey();
        String uin = CookieHookManager.getCachedUin();
        String pUid = CookieHookManager.getCachedPUid();
        
        if (skey == null || pSkey == null || uin == null) {
            return null;
        }
        
        StringBuilder cookies = new StringBuilder();
        
        // 清理uin（移除可能的o前缀）
        String cleanUin = uin.startsWith("o") ? uin.substring(1) : uin;
        
        cookies.append("uin=o").append(cleanUin).append("; ");
        cookies.append("skey=").append(skey).append("; ");
        cookies.append("p_uin=o").append(cleanUin).append("; ");
        
        // p_uid是可选的
        if (pUid != null && !pUid.isEmpty()) {
            cookies.append("p_uid=").append(pUid).append("; ");
        }
        
        cookies.append("p_skey=").append(pSkey);
        
        return cookies.toString();
    }
    
    /**
     * 获取最后一次Cookie获取的来源
     */
    public static CookieSource getLastCookieSource() {
        return sLastSource;
    }
    
    /**
     * 获取Cookie获取状态描述（用于UI显示）
     */
    public static String getCookieStatusDescription(Context context) {
        StringBuilder sb = new StringBuilder();
        
        // 来源信息
        sb.append("获取来源: ");
        switch (sLastSource) {
            case MEMORY:
                sb.append("内存缓存 ✓");
                break;
            case SQLITE:
                sb.append("SQLite数据库 ✓");
                break;
            case WEBVIEW:
                sb.append("WebView ✓");
                break;
            case FAILED:
                sb.append("获取失败 ✗");
                break;
        }
        sb.append("\n\n");
        
        // 内存缓存状态
        sb.append("内存缓存状态:\n");
        sb.append("  skey: ").append(CookieHookManager.getCachedSkey() != null ? "已缓存" : "未缓存").append("\n");
        sb.append("  p_skey: ").append(CookieHookManager.getCachedPSkey() != null ? "已缓存" : "未缓存").append("\n");
        sb.append("  uin: ").append(CookieHookManager.getCachedUin() != null ? CookieHookManager.getCachedUin() : "未缓存").append("\n");
        sb.append("  p_uid: ").append(CookieHookManager.getCachedPUid() != null ? "已缓存" : "未缓存").append("\n");
        
        // 过期状态
        if (CookieHookManager.getLastUpdateTime() > 0) {
            long ageMinutes = (System.currentTimeMillis() - CookieHookManager.getLastUpdateTime()) / 60000;
            sb.append("  缓存时间: ").append(ageMinutes).append("分钟前");
            if (CookieHookManager.isCachePossiblyExpired()) {
                sb.append(" (可能已过期)");
            }
        } else {
            sb.append("  缓存时间: 从未更新");
        }
        
        return sb.toString();
    }
    
    /**
     * 从 QQ WebView 的 SQLite Cookie 数据库读取 Cookie
     * 
     * 表结构: cookies(creation_utc, host_key, top_frame_site_key, name, value, encrypted_value, 
     *                 path, expires_utc, is_secure, is_httponly, last_access_utc, has_expires, 
     *                 is_persistent, priority, samesite, source_scheme, source_port, 
     *                 last_update_utc, source_type, has_cross_site_ancestor)
     */
    private static String getCookiesFromSqlite(Context context) {
        // 动态查找 Cookie 数据库
        File dbFile = findCookieDatabase(context);
        if (dbFile == null) {
            XposedBridge.log(TAG + ": 未找到 Cookie 数据库");
            return null;
        }
        
        XposedBridge.log(TAG + ": 尝试读取 Cookie 数据库: " + dbFile.getAbsolutePath());
        
        SQLiteDatabase db = null;
        Cursor cursor = null;
        // 使用 Map 存储 Cookie，key 为 name，value 为 CookieEntry（包含值和更新时间）
        Map<String, CookieEntry> cookieMap = new HashMap<>();
        
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            
            // 查询 qzone.qq.com 域名下的所有 Cookie
            // 按 last_update_utc 排序，确保获取最新的值
            // name 和 value 可能重复，所以需要用 last_update_utc 来判断哪个是最新的
            String query = "SELECT name, value, last_update_utc FROM cookies WHERE host_key LIKE ? ORDER BY last_update_utc DESC";
            cursor = db.rawQuery(query, new String[]{"%" + QZONE_DOMAIN});
            
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String value = cursor.getString(1);
                long lastUpdate = cursor.getLong(2);
                
                if (name != null && value != null && !value.isEmpty()) {
                    // 只保留最新的 Cookie（因为已按 last_update_utc DESC 排序，第一个就是最新的）
                    if (!cookieMap.containsKey(name)) {
                        cookieMap.put(name, new CookieEntry(value, lastUpdate));
                    }
                }
            }
            
            XposedBridge.log(TAG + ": 从 SQLite 读取到 " + cookieMap.size() + " 个唯一 Cookie");
            
            // 打印关键 Cookie 用于调试
            for (String key : new String[]{"uin", "skey", "p_uin", "p_uid", "p_skey"}) {
                if (cookieMap.containsKey(key)) {
                    String val = cookieMap.get(key).value;
                    String preview = val.length() > 15 ? val.substring(0, 15) + "..." : val;
                    XposedBridge.log(TAG + ":   " + key + " = " + preview);
                }
            }
            
            // 将p_uid缓存到CookieHookManager（如果存在）
            if (cookieMap.containsKey("p_uid")) {
                CookieHookManager.setCachedPUid(cookieMap.get("p_uid").value);
                XposedBridge.log(TAG + ": [SQLITE] 已缓存p_uid到内存");
            }
            
            // 按照正确的顺序拼接 Cookie
            StringBuilder sb = new StringBuilder();
            String[] orderedKeys = {"uin", "skey", "p_uin", "p_uid", "uskey", "p_skey"};
            
            for (String key : orderedKeys) {
                if (cookieMap.containsKey(key)) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(key).append("=").append(cookieMap.get(key).value);
                }
            }
            
            // 添加其他可能需要的 Cookie（排除已添加的）
            java.util.Set<String> orderedKeySet = new java.util.HashSet<>(java.util.Arrays.asList(orderedKeys));
            for (Map.Entry<String, CookieEntry> entry : cookieMap.entrySet()) {
                String key = entry.getKey();
                if (!orderedKeySet.contains(key)) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(key).append("=").append(entry.getValue().value);
                }
            }
            
            return sb.toString();
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": 读取 SQLite Cookie 失败: " + e.getMessage());
            return null;
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }
    
    /**
     * Cookie 条目，包含值和最后更新时间
     */
    private static class CookieEntry {
        String value;
        long lastUpdate;
        
        CookieEntry(String value, long lastUpdate) {
            this.value = value;
            this.lastUpdate = lastUpdate;
        }
    }
    
    /**
     * 动态查找 Cookie 数据库文件
     * @param context 上下文
     * @return 数据库文件，如果未找到返回 null
     */
    private static File findCookieDatabase(Context context) {
        String dataDir = context.getApplicationInfo().dataDir;
        XposedBridge.log(TAG + ": 应用数据目录: " + dataDir);
        
        // 尝试已知的相对路径
        for (String relativePath : COOKIE_DB_RELATIVE_PATHS) {
            File dbFile = new File(dataDir, relativePath);
            if (dbFile.exists() && dbFile.isFile()) {
                XposedBridge.log(TAG + ": 找到 Cookie 数据库: " + dbFile.getAbsolutePath());
                return dbFile;
            }
        }
        
        // 递归搜索包含 "Cookies" 的文件
        File foundDb = searchCookieDatabase(new File(dataDir), 0);
        if (foundDb != null) {
            XposedBridge.log(TAG + ": 搜索找到 Cookie 数据库: " + foundDb.getAbsolutePath());
            return foundDb;
        }
        
        // 尝试其他用户目录（多用户场景）
        String[] userDirs = {"/data/user/0/com.tencent.mobileqq", "/data/data/com.tencent.mobileqq"};
        for (String userDir : userDirs) {
            if (!userDir.equals(dataDir)) {
                for (String relativePath : COOKIE_DB_RELATIVE_PATHS) {
                    File dbFile = new File(userDir, relativePath);
                    if (dbFile.exists() && dbFile.isFile()) {
                        XposedBridge.log(TAG + ": 在其他目录找到 Cookie 数据库: " + dbFile.getAbsolutePath());
                        return dbFile;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 递归搜索 Cookie 数据库文件
     * @param dir 搜索目录
     * @param depth 当前深度
     * @return 找到的数据库文件，未找到返回 null
     */
    private static File searchCookieDatabase(File dir, int depth) {
        // 限制搜索深度，避免性能问题
        if (depth > 4 || dir == null || !dir.isDirectory()) {
            return null;
        }
        
        try {
            File[] files = dir.listFiles();
            if (files == null) return null;
            
            for (File file : files) {
                if (file.isFile() && file.getName().equals("Cookies")) {
                    // 验证是否是 SQLite 数据库
                    if (isValidCookieDatabase(file)) {
                        return file;
                    }
                } else if (file.isDirectory() && !file.getName().startsWith(".")) {
                    File found = searchCookieDatabase(file, depth + 1);
                    if (found != null) return found;
                }
            }
        } catch (Exception e) {
            // 忽略权限错误
        }
        
        return null;
    }
    
    /**
     * 验证文件是否是有效的 Cookie 数据库
     */
    private static boolean isValidCookieDatabase(File file) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            // 检查是否有 cookies 表
            Cursor cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='cookies'", null);
            boolean hasTable = cursor.moveToFirst();
            cursor.close();
            return hasTable;
        } catch (Exception e) {
            return false;
        } finally {
            if (db != null) db.close();
        }
    }

    /**
     * 获取 skey Cookie
     * @param context 上下文
     * @return skey 值，获取失败返回 null
     */
    public static String getSkey(Context context) {
        return getCookie(context, "skey");
    }

    /**
     * 获取 p_skey Cookie
     * @param context 上下文
     * @return p_skey 值，获取失败返回 null
     */
    public static String getPSkey(Context context) {
        return getCookie(context, "p_skey");
    }

    /**
     * 获取 p_uin Cookie（当前登录的 QQ 号，不带 o 前缀）
     * @param context 上下文
     * @return QQ 号（纯数字），获取失败返回 null
     */
    public static String getPUin(Context context) {
        try {
            // 方法1：从 AppRuntime 获取当前登录账号
            String uin = getCurrentUin(context);
            if (uin != null && !uin.isEmpty()) {
                // 移除可能的 o 前缀
                return uin.startsWith("o") ? uin.substring(1) : uin;
            }
            
            // 方法2：从 Cookie 获取
            String cookieUin = getCookie(context, "p_uin");
            if (cookieUin != null && !cookieUin.isEmpty()) {
                // 移除可能的 o 前缀
                return cookieUin.startsWith("o") ? cookieUin.substring(1) : cookieUin;
            }
            
            // 方法3：从 uin Cookie 获取
            String uinCookie = getCookie(context, "uin");
            if (uinCookie != null && !uinCookie.isEmpty()) {
                return uinCookie.startsWith("o") ? uinCookie.substring(1) : uinCookie;
            }
            
            return null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 获取 p_uin 失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 获取指定名称的 Cookie
     * @param context 上下文
     * @param name Cookie 名称
     * @return Cookie 值，获取失败返回 null
     */
    public static String getCookie(Context context, String name) {
        try {
            // 尝试通过 QQ 的 CookieManager 获取
            ClassLoader classLoader = context.getClassLoader();
            
            // 方法1：使用 Tencent 的 CookieManager
            try {
                Class<?> cookieManagerClass = XposedHelpers.findClass(
                    "com.tencent.mobileqq.webview.CookieManager", 
                    classLoader
                );
                Object cookieManager = XposedHelpers.callStaticMethod(cookieManagerClass, "getInstance");
                String cookie = (String) XposedHelpers.callMethod(cookieManager, "getCookie", QZONE_DOMAIN, name);
                if (cookie != null && !cookie.isEmpty()) {
                    return cookie;
                }
            } catch (Throwable t) {
                // 方法1失败，尝试其他方法
            }
            
            // 方法2：使用 Android WebView 的 CookieManager
            try {
                android.webkit.CookieManager webCookieManager = android.webkit.CookieManager.getInstance();
                String allCookies = webCookieManager.getCookie("https://" + QZONE_DOMAIN);
                if (allCookies != null) {
                    String[] cookiePairs = allCookies.split(";");
                    for (String pair : cookiePairs) {
                        String[] keyValue = pair.trim().split("=", 2);
                        if (keyValue.length == 2 && keyValue[0].trim().equals(name)) {
                            return keyValue[1].trim();
                        }
                    }
                }
            } catch (Throwable t) {
                // 方法2失败
            }
            
            // 方法3：通过 QQ 的 TicketManager 获取
            try {
                Class<?> ticketManagerClass = XposedHelpers.findClass(
                    "com.tencent.mobileqq.app.TicketManager",
                    classLoader
                );
                
                // 获取 AppRuntime
                Class<?> baseAppClass = XposedHelpers.findClass(
                    "com.tencent.common.app.BaseApplicationImpl",
                    classLoader
                );
                Object app = XposedHelpers.callStaticMethod(baseAppClass, "getApplication");
                Object runtime = XposedHelpers.callMethod(app, "getRuntime");
                
                // 获取 TicketManager
                Object ticketManager = XposedHelpers.callMethod(runtime, "getManager", 2); // 2 = TicketManager
                
                if ("skey".equals(name)) {
                    String skey = (String) XposedHelpers.callMethod(ticketManager, "getSkey", getCurrentUin(context));
                    if (skey != null && !skey.isEmpty()) {
                        return skey;
                    }
                } else if ("p_skey".equals(name)) {
                    String pskey = (String) XposedHelpers.callMethod(ticketManager, "getPskey", getCurrentUin(context), QZONE_DOMAIN);
                    if (pskey != null && !pskey.isEmpty()) {
                        return pskey;
                    }
                }
            } catch (Throwable t) {
                // 方法3失败
            }
            
            XposedBridge.log(TAG + ": 无法获取 Cookie: " + name);
            return null;
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 获取 Cookie 失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 获取当前登录的 QQ 号
     */
    private static String getCurrentUin(Context context) {
        try {
            ClassLoader classLoader = context.getClassLoader();
            
            // 通过 AppRuntime 获取
            Class<?> baseAppClass = XposedHelpers.findClass(
                "com.tencent.common.app.BaseApplicationImpl",
                classLoader
            );
            Object app = XposedHelpers.callStaticMethod(baseAppClass, "getApplication");
            Object runtime = XposedHelpers.callMethod(app, "getRuntime");
            String uin = (String) XposedHelpers.callMethod(runtime, "getCurrentAccountUin");
            
            return uin;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 获取当前 UIN 失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 检查 Cookie 是否可用
     * @param context 上下文
     * @return true 如果所有必需的 Cookie 都可用
     */
    public static boolean isCookiesAvailable(Context context) {
        String skey = getSkey(context);
        String pSkey = getPSkey(context);
        String pUin = getPUin(context);
        
        // 【调试日志】打印 Cookie 获取结果
        XposedBridge.log(TAG + ": ========== Cookie 检查 ==========");
        XposedBridge.log(TAG + ": skey: " + (skey != null ? skey.substring(0, Math.min(10, skey.length())) + "..." : "NULL"));
        XposedBridge.log(TAG + ": p_skey: " + (pSkey != null ? pSkey.substring(0, Math.min(10, pSkey.length())) + "..." : "NULL"));
        XposedBridge.log(TAG + ": p_uin: " + (pUin != null ? pUin : "NULL"));
        XposedBridge.log(TAG + ": ==================================");
        
        boolean available = skey != null && !skey.isEmpty()
                && pSkey != null && !pSkey.isEmpty()
                && pUin != null && !pUin.isEmpty();
        
        if (!available) {
            XposedBridge.log(TAG + ": Cookie 不可用 - skey: " + (skey != null) 
                    + ", p_skey: " + (pSkey != null) 
                    + ", p_uin: " + (pUin != null));
        } else {
            XposedBridge.log(TAG + ": Cookie 检查通过 ✓");
        }
        
        return available;
    }
}
