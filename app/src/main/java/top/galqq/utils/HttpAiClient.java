package top.galqq.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import top.galqq.config.ConfigManager;

/**
 * AI客户端 - 支持多种模型和JSON格式响应
 */
public class HttpAiClient {

    private static final String TAG = "GalQQ.AI";
    private static final int MAX_RETRY_COUNT = 5; // 最大重试次数
    private static OkHttpClient client;
    private static Handler mainHandler = new Handler(Looper.getMainLooper());

    private static synchronized OkHttpClient getClient() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    public interface AiCallback {
        void onSuccess(List<String> options);
        void onFailure(Exception e);
    }

    /**
     * 扩展回调接口 - 支持重试失败后显示重新加载按钮
     */
    public interface AiCallbackWithRetry extends AiCallback {
        /**
         * 所有重试都失败后调用，提供重新加载的Runnable
         * @param retryAction 点击"重新加载"按钮时执行的动作
         */
        void onAllRetriesFailed(Runnable retryAction);
    }

    /**
     * 获取AI生成的回复选项（无上下文和元数据，向后兼容）
     */
    public static void fetchOptions(Context context, String userMessage, AiCallback callback) {
        fetchOptions(context, userMessage, null, 0, null, callback);
    }

    /**
     * 获取AI生成的回复选项（带自动重试功能）
     * 格式错误时自动重试，最多重试MAX_RETRY_COUNT次
     * 
     * @param context Android上下文
     * @param userMessage 当前用户消息内容
     * @param currentSenderName 当前消息发送人昵称
     * @param currentTimestamp 当前消息时间戳
     * @param contextMessages 历史上下文消息（可为null）
     * @param callback 支持重试的回调
     */
    public static void fetchOptionsWithRetry(Context context, String userMessage,
                                              String currentSenderName, long currentTimestamp,
                                              List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                                              AiCallbackWithRetry callback) {
        fetchOptionsWithRetryInternal(context, userMessage, currentSenderName, currentTimestamp, 
                                       contextMessages, callback, 0);
    }

    /**
     * 内部重试实现
     */
    private static void fetchOptionsWithRetryInternal(Context context, String userMessage,
                                                       String currentSenderName, long currentTimestamp,
                                                       List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                                                       AiCallbackWithRetry callback, int retryCount) {
        
        // 创建重试动作
        Runnable retryAction = () -> {
            Log.d(TAG, "用户点击重新加载");
            fetchOptionsWithRetryInternal(context, userMessage, currentSenderName, currentTimestamp,
                                          contextMessages, callback, 0);
        };

        fetchOptionsInternal(context, userMessage, currentSenderName, currentTimestamp, 
                            contextMessages, null, new AiCallback() {
            @Override
            public void onSuccess(List<String> options) {
                callback.onSuccess(options);
            }

            @Override
            public void onFailure(Exception e) {
                // 检查是否是格式错误（可重试的错误）
                boolean isFormatError = e.getMessage() != null && 
                    (e.getMessage().contains("格式") || e.getMessage().contains("选项不足"));
                
                if (isFormatError && retryCount < MAX_RETRY_COUNT - 1) {
                    // 还有重试机会，静默重试
                    int nextRetry = retryCount + 1;
                    Log.d(TAG, "格式错误，自动重试 (" + nextRetry + "/" + MAX_RETRY_COUNT + ")");
                    
                    // 延迟500ms后重试，避免请求过快
                    mainHandler.postDelayed(() -> {
                        fetchOptionsWithRetryInternal(context, userMessage, currentSenderName, 
                                                      currentTimestamp, contextMessages, callback, nextRetry);
                    }, 500);
                } else if (isFormatError) {
                    // 达到最大重试次数，通知显示重新加载按钮
                    Log.w(TAG, "达到最大重试次数 (" + MAX_RETRY_COUNT + ")，显示重新加载按钮");
                    logError(context, ConfigManager.getAiProvider(), ConfigManager.getAiModel(), 
                            ConfigManager.getApiUrl(), 
                            "AI返回格式错误，已重试" + MAX_RETRY_COUNT + "次仍失败");
                    callback.onAllRetriesFailed(retryAction);
                } else {
                    // 非格式错误（如网络错误），直接失败
                    callback.onFailure(e);
                }
            }
        }, retryCount > 0); // 重试时不显示Toast
    }

    /**
     * 获取AI生成的回复选项（带上下文和当前消息元数据）
     * 
     * @param context Android上下文
     * @param userMessage 当前用户消息内容
     * @param currentSenderName 当前消息发送人昵称
     * @param currentTimestamp 当前消息时间戳
     * @param contextMessages 历史上下文消息（可为null）
     * @param callback 回调
     */
    public static void fetchOptions(Context context, String userMessage,
                                    String currentSenderName, long currentTimestamp,
                                    List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                                    AiCallback callback) {
        fetchOptionsInternal(context, userMessage, currentSenderName, currentTimestamp, 
                            contextMessages, null, callback, false);
    }
    
    /**
     * 获取AI生成的回复选项（带自定义提示词）
     * 
     * @param context Android上下文
     * @param userMessage 当前用户消息内容
     * @param currentSenderName 当前消息发送人昵称
     * @param currentTimestamp 当前消息时间戳
     * @param contextMessages 历史上下文消息（可为null）
     * @param customPrompt 自定义提示词内容（如果为null则使用默认）
     * @param callback 回调
     */
    public static void fetchOptionsWithPrompt(Context context, String userMessage,
                                    String currentSenderName, long currentTimestamp,
                                    List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                                    String customPrompt,
                                    AiCallback callback) {
        fetchOptionsInternal(context, userMessage, currentSenderName, currentTimestamp, 
                            contextMessages, customPrompt, callback, false);
    }

    /**
     * 内部实现 - 获取AI生成的回复选项
     * 
     * @param context Android上下文
     * @param userMessage 当前用户消息内容
     * @param currentSenderName 当前消息发送人昵称
     * @param currentTimestamp 当前消息时间戳
     * @param contextMessages 历史上下文消息（可为null）
     * @param customPrompt 自定义提示词内容（如果为null则使用默认）
     * @param callback 回调
     * @param suppressToast 是否抑制Toast提示（重试时使用）
     */
    private static void fetchOptionsInternal(Context context, String userMessage,
                                    String currentSenderName, long currentTimestamp,
                                    List<top.galqq.utils.MessageContextManager.ChatMessage> contextMessages,
                                    String customPrompt,
                                    AiCallback callback, boolean suppressToast) {
        String apiUrl = ConfigManager.getApiUrl();
        String apiKey = ConfigManager.getApiKey();
        // 使用自定义提示词或默认提示词
        String sysPrompt = (customPrompt != null && !customPrompt.isEmpty()) 
                ? customPrompt : ConfigManager.getSysPrompt();
        String model = ConfigManager.getAiModel();
        String provider = ConfigManager.getAiProvider();
        float temperature = ConfigManager.getAiTemperature();
        int maxTokens = ConfigManager.getAiMaxTokens();

        // 验证配置
        if (TextUtils.isEmpty(apiUrl) || TextUtils.isEmpty(apiKey)) {
            String error = "API配置不完整";
            logError(context, provider, model, apiUrl, error);
            showToast(context, "AI服务未配置 😢");
            callback.onFailure(new IllegalArgumentException(error));
            return;
        }

        try {
            // 构建请求体
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", model);
            
            // 可选参数：只在合理范围内添加
            if (temperature > 0 && temperature <= 2.0) {
                jsonBody.put("temperature", temperature);
            }
            if (maxTokens > 0 && maxTokens <= 4096) {
                jsonBody.put("max_tokens", maxTokens);
            }

            JSONArray messages = new JSONArray();
            
            // 系统提示词
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", sysPrompt);
            messages.put(sysMsg);

            // 添加历史上下文（如果有）
            if (contextMessages != null && !contextMessages.isEmpty()) {
                // 创建时间格式化器
                java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
                
                for (top.galqq.utils.MessageContextManager.ChatMessage msg : contextMessages) {
                    JSONObject ctxMsg = new JSONObject();
                    // 对方的消息作为"user"，自己的消息作为"assistant"
                    ctxMsg.put("role", msg.isSelf ? "assistant" : "user");
                    
                    // 格式化时间戳
                    String timeStr = timeFormat.format(new java.util.Date(msg.timestamp));
                    
                    // 格式化为 "发送人 [时间]: 消息内容"
                    // 格式化为 "发送人 [时间]: 消息内容"
                    String formattedContent = msg.senderName + " [" + timeStr + "]: " + msg.content;
                    ctxMsg.put("content", formattedContent);
                    messages.put(ctxMsg);
                }
                Log.i(TAG, "Added " + contextMessages.size() + " context messages");
            }

            // 当前用户消息（添加特殊标注）
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            
            // 格式化当前消息：添加[当前需添加选项信息]标签
            String formattedCurrentMsg;
            if (currentSenderName != null && !currentSenderName.isEmpty() && currentTimestamp > 0) {
                // 创建时间格式化器
                java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
                String currentTimeStr = timeFormat.format(new java.util.Date(currentTimestamp));
                
                // 格式：[当前需添加选项信息] 昵称 [时间]: 内容
                formattedCurrentMsg = "[当前需添加选项信息] " + currentSenderName + " [" + currentTimeStr + "]: " + userMessage;
            } else {
                // 降级：如果没有元数据，仅添加标签
                formattedCurrentMsg = "[当前需添加选项信息] " + userMessage;
            }
            
            userMsg.put("content", formattedCurrentMsg);
            messages.put(userMsg);

            jsonBody.put("messages", messages);

            RequestBody body = RequestBody.create(
                    jsonBody.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            // 记录完整的请求信息到日志（仅在启用详细日志时）
            if (ConfigManager.isVerboseLogEnabled()) {
                String requestLog = buildRequestLog(provider, model, apiUrl, apiKey, jsonBody.toString());
                Log.d(TAG, "发送AI请求:\n" + requestLog);
                AiLogManager.addLog(context, "AI请求\n" + requestLog);
            } else {
                Log.d(TAG, "发送AI请求: " + provider + " / " + model);
            }

            getClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    String error = e.getMessage();
                    Log.e(TAG, "AI请求失败: " + error, e);
                    logError(context, provider, model, apiUrl, error);
                    if (!suppressToast) {
                        showToast(context, "网络连接失败 😢");
                    }
                    callback.onFailure(e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = null;
                    try {
                        if (!response.isSuccessful()) {
                            int code = response.code();
                            String error = "HTTP " + code + ": " + response.message();
                            responseBody = response.body() != null ? response.body().string() : "";
                            
                            // 特殊处理429速率限制错误（静默处理，不显示Toast）
                            if (code == 429) {
                                Log.w(TAG, "速率限制: " + error);
                                logError(context, provider, model, apiUrl, "Rate Limit (429)\n" + responseBody);
                                // 不调用showToast，静默失败
                                callback.onFailure(new IOException("Rate limit reached"));
                                return;
                            }
                            
                            // 其他错误正常处理
                            logError(context, provider, model, apiUrl, error + "\n" + responseBody);
                            if (!suppressToast) {
                                showToast(context, "AI服务暂时不可用 😢");
                            }
                            callback.onFailure(new IOException(error));
                            return;
                        }

                        responseBody = response.body().string();
                        Log.d(TAG, "AI响应: " + responseBody.substring(0, Math.min(200, responseBody.length())));

                        // 解析JSON格式的响应
                        List<String> options = parseJsonResponse(responseBody);
                        
                        if (options == null || options.size() < 3) {
                            // 改进的错误日志记录
                            int actualCount = options != null ? options.size() : 0;
                            String error;
                            if (options == null) {
                                error = "AI返回格式无法识别，请检查系统提示词配置";
                            } else {
                                error = "AI返回选项不足: 期望3个，实际" + actualCount + "个";
                            }
                            
                            // 重试时不记录详细日志，避免日志过多
                            if (!suppressToast) {
                                String fullLog = error + "\n" +
                                    "=== 原始响应内容 ===\n" + responseBody + "\n" +
                                    "=== 响应内容结束 ===\n" +
                                    "提示: 如果AI返回格式不正确，请检查系统提示词是否要求返回JSON格式";
                                logError(context, provider, model, apiUrl, fullLog);
                                showToast(context, "AI返回格式错误 😢");
                            }
                            callback.onFailure(new Exception(error));
                            return;
                        }

                        // 成功
                        AiLogManager.logAiSuccess(context, provider, model, userMessage, options.size());
                        callback.onSuccess(options);

                    } catch (Exception e) {
                        Log.e(TAG, "解析失败", e);
                        String error = "解析错误: " + e.getMessage();
                        if (!suppressToast) {
                            logError(context, provider, model, apiUrl, error + "\n响应: " + responseBody);
                            showToast(context, "AI返回格式错误 😢");
                        }
                        callback.onFailure(e);
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "请求构建失败", e);
            logError(context, provider, model, apiUrl, "请求构建失败: " + e.getMessage());
            if (!suppressToast) {
                showToast(context, "AI请求失败 😢");
            }
            callback.onFailure(e);
        }
    }

    /**
     * 解析JSON格式的AI响应（重构版）
     * 支持多种格式的智能解析，按优先级依次尝试：
     * 1. 直接JSON格式（响应本身就是options JSON）
     * 2. OpenAI标准格式（choices[0].message.content）
     * 3. 从content中提取：Markdown代码块、混合文本JSON、列表、纯文本
     */
    private static List<String> parseJsonResponse(String responseBody) {
        // 边界情况处理
        if (responseBody == null || responseBody.trim().isEmpty()) {
            Log.w(TAG, "响应为空");
            return null;
        }
        
        List<String> result = null;
        
        try {
            JSONObject jsonResponse = new JSONObject(responseBody);
            
            // 策略1: 直接包含options等字段
            result = parseOptionsJson(responseBody);
            if (result != null && result.size() >= 3) {
                Log.d(TAG, "解析成功: 直接JSON格式");
                return result;
            }
            
            // 策略2: OpenAI标准格式
            if (jsonResponse.has("choices")) {
                JSONArray choices = jsonResponse.getJSONArray("choices");
                if (choices.length() > 0) {
                    String content = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");
                    
                    // 从content中尝试多种解析策略
                    result = parseContentWithStrategies(content);
                    if (result != null && result.size() >= 3) {
                        return result;
                    }
                }
            }
            
        } catch (Exception e) {
            // 响应本身不是有效JSON，尝试作为纯文本解析
            Log.d(TAG, "响应不是标准JSON，尝试其他解析策略");
            result = parseContentWithStrategies(responseBody);
            if (result != null && result.size() >= 3) {
                return result;
            }
        }
        
        Log.w(TAG, "所有解析策略均失败，请检查系统提示词配置");
        return null;
    }

    /**
     * 使用多种策略解析content内容
     * @param content AI返回的content字符串
     * @return 解析出的选项列表
     */
    private static List<String> parseContentWithStrategies(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        
        List<String> result = null;
        
        // 策略A: 直接作为JSON解析（支持多种字段名）
        result = parseOptionsJson(content);
        if (result != null && result.size() >= 3) {
            Log.d(TAG, "解析成功: content直接JSON");
            return result;
        }
        
        // 策略B: 从Markdown代码块中提取JSON
        String markdownJson = extractJsonFromMarkdown(content);
        if (markdownJson != null) {
            result = parseOptionsJson(markdownJson);
            if (result != null && result.size() >= 3) {
                Log.d(TAG, "解析成功: Markdown代码块");
                return result;
            }
            // 尝试从不完整的JSON中提取选项
            result = extractOptionsFromIncompleteJson(markdownJson);
            if (result != null && result.size() >= 3) {
                Log.d(TAG, "解析成功: 不完整Markdown JSON");
                return result;
            }
        }
        
        // 策略C: 从混合文本中提取JSON
        String textJson = extractJsonFromText(content);
        if (textJson != null) {
            result = parseOptionsJson(textJson);
            if (result != null && result.size() >= 3) {
                Log.d(TAG, "解析成功: 混合文本JSON");
                return result;
            }
            // 尝试从不完整的JSON中提取选项
            result = extractOptionsFromIncompleteJson(textJson);
            if (result != null && result.size() >= 3) {
                Log.d(TAG, "解析成功: 不完整混合文本JSON");
                return result;
            }
        }
        
        // 策略D: 尝试从整个content中提取不完整JSON的选项
        result = extractOptionsFromIncompleteJson(content);
        if (result != null && result.size() >= 3) {
            Log.d(TAG, "解析成功: 不完整JSON提取");
            return result;
        }
        
        // 策略E: 旧格式（|||分隔）
        result = parseLegacyFormat(content);
        if (result != null && result.size() >= 3) {
            Log.d(TAG, "解析成功: |||分隔格式");
            return result;
        }
        
        // 策略F: 编号/项目符号列表
        result = parseNumberedList(content);
        if (result != null && result.size() >= 3) {
            Log.d(TAG, "解析成功: 编号列表格式");
            return result;
        }
        
        // 策略G: 纯文本行（最后的备选方案）
        result = parsePlainLines(content);
        if (result != null && result.size() >= 3) {
            Log.d(TAG, "解析成功: 纯文本行格式");
            return result;
        }
        
        return null;
    }

    /**
     * 将JSONArray转换为List<String>
     */
    private static List<String> jsonArrayToList(JSONArray array) throws Exception {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String option = array.getString(i).trim();
            if (!option.isEmpty()) {
                result.add(option);
            }
        }
        return result;
    }

    /**
     * 解析旧格式（|||分隔）
     */
    private static List<String> parseLegacyFormat(String content) {
        String[] parts = content.split("\\|\\|\\|");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.size() >= 3 ? result : null;
    }

    // ==================== 新增解析辅助方法 ====================

    /**
     * 从markdown代码块中提取JSON
     * 支持格式：```json ... ``` 或 ``` ... ```
     * @param content 包含markdown代码块的内容
     * @return 提取的JSON字符串，如果没有找到则返回null
     */
    private static String extractJsonFromMarkdown(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        // 匹配 ```json ... ``` 或 ``` ... ``` 格式
        // 使用非贪婪匹配，取第一个代码块
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(content);
        
        if (matcher.find()) {
            String extracted = matcher.group(1);
            if (extracted != null) {
                return extracted.trim();
            }
        }
        
        return null;
    }

    /**
     * 从混合文本中提取JSON对象
     * 查找第一个 { 和最后一个匹配的 } 之间的内容
     * @param content 可能包含JSON的混合文本
     * @return 提取的JSON字符串，如果没有找到则返回null
     */
    private static String extractJsonFromText(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        int firstBrace = content.indexOf('{');
        if (firstBrace == -1) {
            return null;
        }
        
        // 找到匹配的闭合大括号（处理嵌套）
        int depth = 0;
        int lastBrace = -1;
        for (int i = firstBrace; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    lastBrace = i;
                    break;
                }
            }
        }
        
        if (lastBrace == -1) {
            return null;
        }
        
        return content.substring(firstBrace, lastBrace + 1);
    }

    /**
     * 解析options JSON对象
     * 支持多种字段名：options, choices, replies, answers, responses
     * @param jsonStr JSON字符串
     * @return 选项列表，如果解析失败返回null
     */
    private static List<String> parseOptionsJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        
        try {
            JSONObject json = new JSONObject(jsonStr);
            
            // 尝试多种字段名
            String[] fieldNames = {"options", "choices", "replies", "answers", "responses"};
            for (String fieldName : fieldNames) {
                if (json.has(fieldName)) {
                    Object value = json.get(fieldName);
                    if (value instanceof JSONArray) {
                        return jsonArrayToList((JSONArray) value);
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            Log.d(TAG, "parseOptionsJson失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析编号/项目符号列表
     * 支持格式：1. xxx, 1、xxx, 1) xxx, - xxx, * xxx, • xxx
     * @param content 列表文本
     * @return 选项列表，如果解析失败返回null
     */
    private static List<String> parseNumberedList(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        List<String> result = new ArrayList<>();
        String[] lines = content.split("\\n");
        
        // 匹配编号或项目符号的正则
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "^\\s*(?:\\d+[.、)\\]]|[-*•])\\s*(.+)$"
        );
        
        for (String line : lines) {
            java.util.regex.Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String item = matcher.group(1);
                if (item != null) {
                    item = item.trim();
                    if (!item.isEmpty()) {
                        result.add(item);
                    }
                }
            }
        }
        
        return result.size() >= 3 ? result : null;
    }

    /**
     * 解析纯文本行
     * 将非空行作为选项，但过滤掉JSON/代码格式的行
     * @param content 文本内容
     * @return 选项列表，如果行数不足返回null
     */
    private static List<String> parsePlainLines(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        List<String> result = new ArrayList<>();
        String[] lines = content.split("\\n");
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && isValidOptionLine(trimmed)) {
                result.add(trimmed);
            }
        }
        
        return result.size() >= 3 ? result : null;
    }

    /**
     * 从不完整的JSON中提取选项
     * 用于处理AI返回被截断的JSON情况
     * @param content 可能不完整的JSON内容
     * @return 提取的选项列表
     */
    private static List<String> extractOptionsFromIncompleteJson(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        List<String> result = new ArrayList<>();
        
        // 使用正则匹配JSON数组中的字符串元素
        // 匹配 "内容" 或 "内容", 格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "\"([^\"]+)\"\\s*,?",
            java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher matcher = pattern.matcher(content);
        
        // 跳过字段名（如 "options", "choices" 等）
        java.util.Set<String> fieldNames = new java.util.HashSet<>();
        fieldNames.add("options");
        fieldNames.add("choices");
        fieldNames.add("replies");
        fieldNames.add("answers");
        fieldNames.add("responses");
        
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isEmpty()) {
                // 跳过字段名
                if (fieldNames.contains(value.toLowerCase())) {
                    continue;
                }
                // 跳过太短的内容（可能是JSON语法）
                if (value.length() < 2) {
                    continue;
                }
                result.add(value.trim());
            }
        }
        
        return result.size() >= 3 ? result : null;
    }

    /**
     * 判断一行是否是有效的选项内容
     * 过滤掉JSON/代码格式的行
     * @param line 要检查的行
     * @return 如果是有效选项返回true
     */
    private static boolean isValidOptionLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        
        // 过滤markdown代码块标记
        if (line.startsWith("```")) {
            return false;
        }
        
        // 过滤纯JSON语法字符的行
        String stripped = line.replaceAll("[\\s\\[\\]{}:,\"]", "");
        if (stripped.isEmpty()) {
            return false;
        }
        
        // 过滤JSON字段名行（如 "options": [ 或 "choices": [）
        if (line.matches("^\"?\\w+\"?\\s*:\\s*\\[?\\s*$")) {
            return false;
        }
        
        // 过滤只有单个大括号或方括号的行
        if (line.equals("{") || line.equals("}") || line.equals("[") || line.equals("]") ||
            line.equals("{,") || line.equals("},") || line.equals("[,") || line.equals("],")) {
            return false;
        }
        
        // 过滤JSON数组元素格式（如 "选项内容", 或 "选项内容"）
        // 但要保留实际内容，所以提取引号内的内容
        // 这里不过滤，让后面的清理逻辑处理
        
        return true;
    }

    /**
     * 记录错误日志
     */
    private static void logError(Context context, String provider, String model, String url, String error) {
        AiLogManager.logAiError(context, provider, model, url, error);
    }

    /**
     * 构建请求日志（用于调试）
     */
    private static String buildRequestLog(String provider, String model, String url, String apiKey, String body) {
        StringBuilder log = new StringBuilder();
        log.append("Provider: ").append(provider).append("\n");
        log.append("Model: ").append(model).append("\n");
        log.append("URL: ").append(url).append("\n");
        log.append("Headers:\n");
        log.append("  Authorization: Bearer ").append(maskApiKey(apiKey)).append("\n");
        log.append("  Content-Type: application/json\n");
        log.append("Body:\n");
        
        // 格式化JSON body
        try {
            JSONObject jsonBody = new JSONObject(body);
            log.append(jsonBody.toString(2)); // 缩进2个空格
        } catch (Exception e) {
            log.append(body);
        }
        
        return log.toString();
    }

    /**
     * 遮蔽API Key（只显示前4位和后4位）
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 显示Toast提示
     */
    private static void showToast(Context context, String message) {
        mainHandler.post(() -> {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 测试API连接
     */
    public static void testApiConnection(Context context, AiCallback callback) {
        fetchOptions(context, "你好", callback);
    }
}
