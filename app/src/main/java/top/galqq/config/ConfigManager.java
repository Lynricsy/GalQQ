package top.galqq.config;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tencent.mmkv.MMKV;
import java.io.File;

public class ConfigManager {

    private static final String MMKV_ID = "GalQQ";
    private static MMKV sMmkv;
    private static boolean sInitialized = false;
    
    // Keys
    public static final String KEY_ENABLED = "gal_enabled";
    public static final String KEY_AI_ENABLED = "gal_ai_enabled";
    public static final String KEY_SYS_PROMPT = "gal_sys_prompt";
    public static final String KEY_PROMPT_LIST = "gal_prompt_list";
    public static final String KEY_CURRENT_PROMPT_INDEX = "gal_current_prompt_index";
    public static final String KEY_API_URL = "gal_api_url";
    public static final String KEY_API_KEY = "gal_api_key";
    public static final String KEY_AI_MODEL = "gal_ai_model";
    public static final String KEY_AI_PROVIDER = "gal_ai_provider";
    public static final String KEY_AI_TEMPERATURE = "gal_ai_temperature";
    public static final String KEY_AI_MAX_TOKENS = "gal_ai_max_tokens";
    public static final String KEY_DICT_PATH = "gal_dict_path";
    public static final String KEY_FILTER_MODE = "gal_filter_mode";
    public static final String KEY_WHITELIST = "gal_whitelist";
    public static final String KEY_VERBOSE_LOG = "gal_verbose_log";
    
    // Context Keys
    public static final String KEY_CONTEXT_ENABLED = "gal_context_enabled";
    public static final String KEY_CONTEXT_MESSAGE_COUNT = "gal_context_message_count";
    public static final String KEY_HISTORY_THRESHOLD = "gal_history_threshold";
    public static final String KEY_AUTO_SHOW_OPTIONS = "gal_auto_show_options";
    
    // Affinity Keys (好感度功能)
    public static final String KEY_AFFINITY_ENABLED = "gal_affinity_enabled";
    public static final String KEY_AFFINITY_MODEL = "gal_affinity_model";
    
    // Affinity Model Constants
    public static final int AFFINITY_MODEL_MUTUAL = 0;      // 双向奔赴模型
    public static final int AFFINITY_MODEL_BALANCED = 1;    // 加权平衡模型
    public static final int AFFINITY_MODEL_EGOCENTRIC = 2;  // 综合加权模型
    public static final int DEFAULT_AFFINITY_MODEL = AFFINITY_MODEL_EGOCENTRIC; // 默认使用综合加权模型

    // AI Providers
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_KIMI = "kimi";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_QWEN = "qwen";
    public static final String PROVIDER_GLM = "glm";
    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_BAIDU = "baidu";
    public static final String PROVIDER_SPARK = "spark";
    public static final String PROVIDER_BAICHUAN = "baichuan";
    public static final String PROVIDER_DOUBAO = "doubao";
    public static final String PROVIDER_SENSENOVA = "sensenova";
    public static final String PROVIDER_LINKAI = "linkai";
    public static final String PROVIDER_GROQ = "groq";
    public static final String PROVIDER_TOGETHER = "together";
    public static final String PROVIDER_FIREWORKS = "fireworks";
    public static final String PROVIDER_DEEPINFRA = "deepinfra";
    public static final String PROVIDER_DASHSCOPE = "dashscope";
    public static final String PROVIDER_SILICONFLOW = "siliconflow";
    public static final String PROVIDER_CUSTOM = "custom";

    // Default Values
    public static final String DEFAULT_SYS_PROMPT = "你是一个Galgame恋爱攻略助手，请根据对话上下文，为主人公（用户）生成3个能增加好感度的回复选项 (★^O^★)。\n\n关于消息格式的说明 (📝)：\n系统发送的消息格式为“[当前需添加选项信息]昵称[我][时间]：信息”，其含义如下：\n- [当前需添加选项信息]：表示这是主人公（用户）当前收到的、需要你生成回复选项的目标消息。\n- 昵称：发送这条消息的角色名字。\n- [我]：如果名字后面带有[我]标记，说明这是主人公自己发送的消息（用于理解上下文）。\n- [时间]：消息发送的具体时间。\n\n回复要求：\n1. 风格要像Galgame选项一样有趣，可以是温柔体贴、傲娇毒舌或者幽默风趣\n2. 多使用颜文字（如 (*/ω＼*)）来增强语气，尽量少用普通Emoji\n3. **强制系统命令**必须返回恰好3个选项\n4. **强制系统命令**严格遵守JSON格式返回：\n\n{\n  \"options\": [\n    \"选项一\",\n    \"选项二\",\n    \"选项三\"\n  ]\n}\n**强制系统命令**仅允许返回json内容，不允许返回其他任何内容";
    public static final String DEFAULT_MODEL = "gpt-3.5-turbo";
    public static final String DEFAULT_PROVIDER = PROVIDER_OPENAI;
    public static final float DEFAULT_TEMPERATURE = 0.8f;
    public static final int DEFAULT_MAX_TOKENS = 120;
    public static final String DEFAULT_FILTER_MODE = "blacklist";
    
    // Context Default Values
    public static final boolean DEFAULT_CONTEXT_ENABLED = true;
    public static final int DEFAULT_CONTEXT_MESSAGE_COUNT = 15; // 从10改为15
    public static final int DEFAULT_HISTORY_THRESHOLD = 600; // 历史消息阈值（秒），默认10分钟
    public static final boolean DEFAULT_AUTO_SHOW_OPTIONS = false;
    
    // QPS Default Value
    public static final float DEFAULT_AI_QPS = 3.0f;
    public static final String KEY_AI_QPS = "gal_ai_qps";

    /**
     * Initialize MMKV with MULTI_PROCESS_MODE for cross-process access
     * This MUST be called before any other operations
     */
    public static synchronized void init(Context context) {
        if (sInitialized) {
            return;
        }
        
        try {
            // Create MMKV directory in QQ's files directory
            File filesDir = context.getFilesDir();
            File mmkvDir = new File(filesDir, "galqq_mmkv");
            if (!mmkvDir.exists()) {
                mmkvDir.mkdirs();
            }
            
            // Create .tmp cache directory (required by MMKV)
            File cacheDir = new File(mmkvDir, ".tmp");
            if (!cacheDir.exists()) {
                cacheDir.mkdir();
            }
            
            // Initialize MMKV
            String rootDir = MMKV.initialize(context, mmkvDir.getAbsolutePath());
            
            // Get MMKV instance with MULTI_PROCESS_MODE (critical for cross-process access!)
            sMmkv = MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE);
            
            sInitialized = true;
            
            android.util.Log.i("GalQQ.ConfigManager", "MMKV initialized successfully at: " + rootDir);
        } catch (Exception e) {
            android.util.Log.e("GalQQ.ConfigManager", "Failed to initialize MMKV: " + e.getMessage(), e);
            throw new RuntimeException("MMKV initialization failed", e);
        }
    }

    // Alias for backwards compatibility
    public static void initPref(Context context) {
        init(context);
    }

    @NonNull
    private static MMKV getMmkv() {
        if (sMmkv == null) {
            throw new IllegalStateException("ConfigManager not initialized. Call init() first.");
        }
        return sMmkv;
    }

    // ========== Boolean Methods ==========
    
    public static boolean isModuleEnabled() {
        return getMmkv().decodeBool(KEY_ENABLED, true);
    }
    
    public static void setModuleEnabled(boolean enabled) {
        getMmkv().encode(KEY_ENABLED, enabled);
    }

    public static boolean isAiEnabled() {
        return getMmkv().decodeBool(KEY_AI_ENABLED, false);
    }
    
    public static void setAiEnabled(boolean enabled) {
        getMmkv().encode(KEY_AI_ENABLED, enabled);
    }

    // ========== String Methods ==========
    
    public static String getSysPrompt() {
        return getMmkv().decodeString(KEY_SYS_PROMPT, DEFAULT_SYS_PROMPT);
    }
    
    public static void setSysPrompt(String prompt) {
        getMmkv().encode(KEY_SYS_PROMPT, prompt);
    }

    // ========== Prompt List Methods (提示词列表管理) ==========
    
    /**
     * 获取提示词列表（JSON数组格式存储）
     * @return 提示词列表
     */
    public static java.util.List<PromptItem> getPromptList() {
        String json = getMmkv().decodeString(KEY_PROMPT_LIST, "");
        java.util.List<PromptItem> list = new java.util.ArrayList<>();
        if (json == null || json.isEmpty()) {
            // 默认添加一个提示词
            list.add(new PromptItem("默认提示词", DEFAULT_SYS_PROMPT));
            savePromptList(list);
            return list;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                String name = obj.getString("name");
                String content = obj.getString("content");
                // 兼容旧数据：缺少字段时默认为空字符串/true/false
                String whitelist = obj.optString("whitelist", "");
                String blacklist = obj.optString("blacklist", "");
                boolean enabled = obj.optBoolean("enabled", true);
                boolean whitelistEnabled = obj.optBoolean("whitelistEnabled", false);
                boolean blacklistEnabled = obj.optBoolean("blacklistEnabled", false);
                list.add(new PromptItem(name, content, whitelist, blacklist, enabled, whitelistEnabled, blacklistEnabled));
            }
        } catch (Exception e) {
            list.add(new PromptItem("默认提示词", DEFAULT_SYS_PROMPT));
        }
        return list;
    }
    
    /**
     * 保存提示词列表
     * @param list 提示词列表
     */
    public static void savePromptList(java.util.List<PromptItem> list) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (PromptItem item : list) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("name", item.name);
                obj.put("content", item.content);
                obj.put("whitelist", item.whitelist != null ? item.whitelist : "");
                obj.put("blacklist", item.blacklist != null ? item.blacklist : "");
                obj.put("enabled", item.enabled);
                obj.put("whitelistEnabled", item.whitelistEnabled);
                obj.put("blacklistEnabled", item.blacklistEnabled);
                arr.put(obj);
            }
            getMmkv().encode(KEY_PROMPT_LIST, arr.toString());
        } catch (Exception e) {
            android.util.Log.e("GalQQ.ConfigManager", "Failed to save prompt list", e);
        }
    }
    
    /**
     * 获取当前选中的提示词索引
     * @return 索引
     */
    public static int getCurrentPromptIndex() {
        return getMmkv().decodeInt(KEY_CURRENT_PROMPT_INDEX, 0);
    }
    
    /**
     * 设置当前选中的提示词索引
     * @param index 索引
     */
    public static void setCurrentPromptIndex(int index) {
        getMmkv().encode(KEY_CURRENT_PROMPT_INDEX, index);
        // 同时更新当前使用的提示词
        java.util.List<PromptItem> list = getPromptList();
        if (index >= 0 && index < list.size()) {
            setSysPrompt(list.get(index).content);
        }
    }
    
    /**
     * 提示词项
     */
    public static class PromptItem {
        public String name;
        public String content;
        public String whitelist;  // 逗号分隔的QQ号，白名单
        public String blacklist;  // 逗号分隔的QQ号，黑名单
        public boolean enabled;   // 是否启用，禁用时黑白名单都不触发
        public boolean whitelistEnabled;  // 白名单功能是否启用
        public boolean blacklistEnabled;  // 黑名单功能是否启用
        
        public PromptItem(String name, String content) {
            this(name, content, "", "", true, false, false);
        }
        
        public PromptItem(String name, String content, String whitelist, String blacklist) {
            this(name, content, whitelist, blacklist, true, false, false);
        }
        
        public PromptItem(String name, String content, String whitelist, String blacklist, boolean enabled) {
            this(name, content, whitelist, blacklist, enabled, false, false);
        }
        
        public PromptItem(String name, String content, String whitelist, String blacklist, 
                         boolean enabled, boolean whitelistEnabled, boolean blacklistEnabled) {
            this.name = name;
            this.content = content;
            this.whitelist = whitelist != null ? whitelist : "";
            this.blacklist = blacklist != null ? blacklist : "";
            this.enabled = enabled;
            this.whitelistEnabled = whitelistEnabled;
            this.blacklistEnabled = blacklistEnabled;
        }
        
        /**
         * 检查指定QQ号是否在白名单中
         * @param qq QQ号
         * @return true 如果在白名单中且白名单功能启用
         */
        public boolean isInWhitelist(String qq) {
            if (!whitelistEnabled) {
                return false;
            }
            if (qq == null || qq.isEmpty() || whitelist == null || whitelist.isEmpty()) {
                return false;
            }
            java.util.Set<String> validQQs = parseQQList(whitelist);
            return validQQs.contains(qq.trim());
        }
        
        /**
         * 检查指定QQ号是否在黑名单中
         * @param qq QQ号
         * @return true 如果在黑名单中且黑名单功能启用
         */
        public boolean isInBlacklist(String qq) {
            if (!blacklistEnabled) {
                return false;
            }
            if (qq == null || qq.isEmpty() || blacklist == null || blacklist.isEmpty()) {
                return false;
            }
            java.util.Set<String> validQQs = parseQQList(blacklist);
            return validQQs.contains(qq.trim());
        }
        
        /**
         * 解析QQ号列表，过滤无效条目
         * 有效QQ号：5-11位纯数字
         * @param list 逗号分隔的QQ号字符串
         * @return 有效QQ号集合
         */
        private java.util.Set<String> parseQQList(String list) {
            java.util.Set<String> result = new java.util.HashSet<>();
            if (list == null || list.isEmpty()) {
                return result;
            }
            String[] parts = list.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                // 有效QQ号：5-11位纯数字
                if (trimmed.matches("\\d{5,11}")) {
                    result.add(trimmed);
                }
            }
            return result;
        }
    }

    public static String getApiUrl() {
        return getMmkv().decodeString(KEY_API_URL, "");
    }
    
    public static void setApiUrl(String url) {
        getMmkv().encode(KEY_API_URL, url);
    }

    public static String getApiKey() {
        return getMmkv().decodeString(KEY_API_KEY, "");
    }
    
    public static void setApiKey(String key) {
        getMmkv().encode(KEY_API_KEY, key);
    }

    public static String getAiModel() {
        return getMmkv().decodeString(KEY_AI_MODEL, DEFAULT_MODEL);
    }
    
    public static void setAiModel(String model) {
        getMmkv().encode(KEY_AI_MODEL, model);
    }

    public static String getAiProvider() {
        return getMmkv().decodeString(KEY_AI_PROVIDER, DEFAULT_PROVIDER);
    }
    
    public static void setAiProvider(String provider) {
        getMmkv().encode(KEY_AI_PROVIDER, provider);
    }

    public static float getAiTemperature() {
        return getMmkv().decodeFloat(KEY_AI_TEMPERATURE, DEFAULT_TEMPERATURE);
    }
    
    public static void setAiTemperature(float temperature) {
        getMmkv().encode(KEY_AI_TEMPERATURE, temperature);
    }

    public static int getAiMaxTokens() {
        return getMmkv().decodeInt(KEY_AI_MAX_TOKENS, DEFAULT_MAX_TOKENS);
    }
    
    public static void setAiMaxTokens(int maxTokens) {
        getMmkv().encode(KEY_AI_MAX_TOKENS, maxTokens);
    }

    public static float getAiQps() {
        return getMmkv().decodeFloat(KEY_AI_QPS, DEFAULT_AI_QPS);
    }
    
    public static void setAiQps(float qps) {
        getMmkv().encode(KEY_AI_QPS, qps);
    }

    public static String getDictPath() {
        return getMmkv().decodeString(KEY_DICT_PATH, "");
    }
    
    public static void setDictPath(String path) {
        getMmkv().encode(KEY_DICT_PATH, path);
    }

    // Filter Mode
    public static String getFilterMode() {
        return getMmkv().decodeString(KEY_FILTER_MODE, DEFAULT_FILTER_MODE);
    }
    
    public static void setFilterMode(String mode) {
        getMmkv().encode(KEY_FILTER_MODE, mode);
    }
    
    // Blacklist
    public static final String KEY_BLACKLIST = "gal_blacklist";
    
    public static String getBlacklist() {
        String blacklist = getMmkv().decodeString(KEY_BLACKLIST, "");
        // 默认包含2854196310
        if (blacklist.isEmpty()) {
            return "2854196310";
        }
        // 确保2854196310在黑名单中
        if (!blacklist.contains("2854196310")) {
            return blacklist + ",2854196310";
        }
        return blacklist;
    }
    
    public static void setBlacklist(String blacklist) {
        getMmkv().encode(KEY_BLACKLIST, blacklist);
    }
    
    public static boolean isInBlacklist(String qqNumber) {
        String blacklist = getBlacklist();
        if (blacklist == null || blacklist.trim().isEmpty()) {
            return false;
        }
        String[] numbers = blacklist.split(",");
        for (String num : numbers) {
            if (num.trim().equals(qqNumber)) {
                return true;
            }
        }
        return false;
    }
    
    // Whitelist
    public static String getWhitelist() {
        return getMmkv().decodeString(KEY_WHITELIST, "");
    }
    
    public static void setWhitelist(String whitelist) {
        getMmkv().encode(KEY_WHITELIST, whitelist);
    }
    
    public static boolean isInWhitelist(String qqNumber) {
        String whitelist = getWhitelist();
        if (whitelist == null || whitelist.trim().isEmpty()) {
            return false;
        }
        String[] numbers = whitelist.split(",");
        for (String num : numbers) {
            if (num.trim().equals(qqNumber)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isVerboseLogEnabled() {
        return getMmkv().decodeBool(KEY_VERBOSE_LOG, false);
    }
    
    public static void setVerboseLogEnabled(boolean enabled) {
        getMmkv().encode(KEY_VERBOSE_LOG, enabled);
    }

    // ========== Context Methods ==========
    
    public static boolean isContextEnabled() {
        return getMmkv().decodeBool(KEY_CONTEXT_ENABLED, DEFAULT_CONTEXT_ENABLED);
    }
    
    public static void setContextEnabled(boolean enabled) {
        getMmkv().encode(KEY_CONTEXT_ENABLED, enabled);
    }
    
    public static int getContextMessageCount() {
        int count = getMmkv().decodeInt(KEY_CONTEXT_MESSAGE_COUNT, DEFAULT_CONTEXT_MESSAGE_COUNT);
        // 限制在1-30之间（从20改为30）
        return Math.max(1, Math.min(30, count));
    }
    
    public static void setContextMessageCount(int count) {
        getMmkv().encode(KEY_CONTEXT_MESSAGE_COUNT, count);
    }
    
    public static int getHistoryThreshold() {
        return getMmkv().decodeInt(KEY_HISTORY_THRESHOLD, DEFAULT_HISTORY_THRESHOLD);
    }
    
    public static void setHistoryThreshold(int seconds) {
        getMmkv().encode(KEY_HISTORY_THRESHOLD, seconds);
    }
    
    public static boolean isAutoShowOptionsEnabled() {
        return getMmkv().decodeBool(KEY_AUTO_SHOW_OPTIONS, DEFAULT_AUTO_SHOW_OPTIONS);
    }
    
    public static void setAutoShowOptionsEnabled(boolean enabled) {
        getMmkv().encode(KEY_AUTO_SHOW_OPTIONS, enabled);
    }

    // ========== Affinity Methods (好感度功能) ==========
    
    /**
     * 检查好感度显示功能是否启用
     * @return true 如果启用
     */
    public static boolean isAffinityEnabled() {
        return getMmkv().decodeBool(KEY_AFFINITY_ENABLED, false);
    }
    
    /**
     * 设置好感度显示功能开关
     * @param enabled 是否启用
     */
    public static void setAffinityEnabled(boolean enabled) {
        getMmkv().encode(KEY_AFFINITY_ENABLED, enabled);
    }
    
    /**
     * 获取好感度计算模型
     * @return 模型ID (0=双向奔赴, 1=加权平衡, 2=综合加权)
     */
    public static int getAffinityModel() {
        return getMmkv().decodeInt(KEY_AFFINITY_MODEL, DEFAULT_AFFINITY_MODEL);
    }
    
    /**
     * 设置好感度计算模型
     * @param model 模型ID
     */
    public static void setAffinityModel(int model) {
        getMmkv().encode(KEY_AFFINITY_MODEL, model);
    }
    
    /**
     * 获取好感度模型名称
     * @param model 模型ID
     * @return 模型名称
     */
    public static String getAffinityModelName(int model) {
        switch (model) {
            case AFFINITY_MODEL_MUTUAL:
                return "双向奔赴模型";
            case AFFINITY_MODEL_BALANCED:
                return "加权平衡模型";
            case AFFINITY_MODEL_EGOCENTRIC:
            default:
                return "综合加权模型";
        }
    }

    // ========== Generic Methods ==========
    
    public static boolean getBoolean(String key, boolean defaultValue) {
        return getMmkv().decodeBool(key, defaultValue);
    }
    
    public static void putBoolean(String key, boolean value) {
        getMmkv().encode(key, value);
    }
    
    public static int getInt(String key, int defaultValue) {
        return getMmkv().decodeInt(key, defaultValue);
    }
    
    public static void putInt(String key, int value) {
        getMmkv().encode(key, value);
    }
    
    public static long getLong(String key, long defaultValue) {
        return getMmkv().decodeLong(key, defaultValue);
    }
    
    public static void putLong(String key, long value) {
        getMmkv().encode(key, value);
    }
    
    public static String getString(String key, String defaultValue) {
        return getMmkv().decodeString(key, defaultValue);
    }
    
    public static void putString(String key, String value) {
        getMmkv().encode(key, value);
    }
    
    public static boolean contains(String key) {
        return getMmkv().contains(key);
    }
    
    public static void remove(String key) {
        getMmkv().remove(key);
    }
    
    public static void clear() {
        getMmkv().clearAll();
    }

    /**
     * Get the MMKV file for debugging
     */
    @Nullable
    public static File getConfigFile() {
        if (!sInitialized) {
            return null;
        }
        String rootDir = MMKV.getRootDir();
        if (rootDir == null) {
            return null;
        }
        return new File(rootDir, MMKV_ID);
    }

    /**
     * 根据服务商获取默认API URL
     * @param provider 服务商标识
     * @return 对应的API端点URL，未知服务商返回空字符串
     */
    @NonNull
    public static String getDefaultApiUrl(String provider) {
        if (provider == null) {
            return "";
        }
        switch (provider) {
            case PROVIDER_KIMI:
                return "https://api.moonshot.cn/v1/chat/completions";
            case PROVIDER_BAIDU:
                return "https://qianfan.baidubce.com/v2/chat/completions";
            case PROVIDER_GLM:
                return "https://open.bigmodel.cn/api/paas/v4/chat/completions";
            case PROVIDER_SPARK:
                return "https://spark-api-open.xf-yun.com/v1/chat/completions";
            case PROVIDER_BAICHUAN:
                return "https://api.baichuan-ai.com/v1/chat/completions";
            case PROVIDER_DOUBAO:
                return "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
            case PROVIDER_SENSENOVA:
                return "https://api.sensenova.cn/compatible-mode/v1/chat/completions";
            case PROVIDER_OPENAI:
                return "https://api.openai.com/v1/chat/completions";
            case PROVIDER_LINKAI:
                return "https://api.link-ai.tech/v1/chat/completions";
            case PROVIDER_GROQ:
                return "https://api.groq.com/openai/v1/chat/completions";
            case PROVIDER_TOGETHER:
                return "https://api.together.xyz/v1/chat/completions";
            case PROVIDER_FIREWORKS:
                return "https://api.fireworks.ai/inference/v1/chat/completions";
            case PROVIDER_DEEPINFRA:
                return "https://api.deepinfra.com/v1/openai/chat/completions";
            case PROVIDER_DEEPSEEK:
                return "https://api.deepseek.com/v1/chat/completions";
            case PROVIDER_DASHSCOPE:
                return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
            case PROVIDER_SILICONFLOW:
                return "https://api.siliconflow.cn/v1/chat/completions";
            case PROVIDER_OLLAMA:
                return "http://localhost:11434/v1/chat/completions";
            case PROVIDER_QWEN:
                return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
            default:
                return "";
        }
    }

    /**
     * 获取服务商显示名称
     * @param provider 服务商标识
     * @return 服务商的中文显示名称
     */
    @NonNull
    public static String getProviderDisplayName(String provider) {
        if (provider == null) {
            return "未知";
        }
        switch (provider) {
            case PROVIDER_KIMI:
                return "月之暗面 (Kimi)";
            case PROVIDER_BAIDU:
                return "百度千帆 (文心)";
            case PROVIDER_GLM:
                return "智谱AI (GLM-4)";
            case PROVIDER_SPARK:
                return "讯飞星火 (Spark)";
            case PROVIDER_BAICHUAN:
                return "百川智能 (Baichuan)";
            case PROVIDER_DOUBAO:
                return "字节豆包 (Doubao)";
            case PROVIDER_SENSENOVA:
                return "商汤日日新 (SenseNova)";
            case PROVIDER_OPENAI:
                return "OpenAI";
            case PROVIDER_LINKAI:
                return "LinkAI";
            case PROVIDER_GROQ:
                return "Groq";
            case PROVIDER_TOGETHER:
                return "Together.ai";
            case PROVIDER_FIREWORKS:
                return "Fireworks.ai";
            case PROVIDER_DEEPINFRA:
                return "DeepInfra";
            case PROVIDER_DEEPSEEK:
                return "DeepSeek";
            case PROVIDER_DASHSCOPE:
                return "阿里云DashScope";
            case PROVIDER_SILICONFLOW:
                return "硅基流动 (SiliconFlow)";
            case PROVIDER_OLLAMA:
                return "Ollama (本地)";
            case PROVIDER_QWEN:
                return "通义千问 (Qwen)";
            case PROVIDER_CUSTOM:
                return "自定义";
            default:
                return "未知";
        }
    }
}
