package top.galqq.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import top.galqq.config.ConfigManager;
import top.galqq.utils.AiRateLimitedQueue;
import top.galqq.utils.DictionaryManager;
import top.galqq.utils.HttpAiClient;
import top.galqq.utils.MessageContextManager;
import top.galqq.utils.QAppUtils;
import java.lang.reflect.Field;
import top.galqq.utils.SendMessageHelper;

public class MessageInterceptor {

    private static final String TAG = "GalQQ.MessageInterceptor";
    private static final int OPTION_BAR_ID = 0x7F0A1234; // Custom ID for option bar
    
    // AI选项缓存：msgId -> List<String> options
    private static final java.util.Map<String, java.util.List<String>> optionsCache = 
        new java.util.LinkedHashMap<String, java.util.List<String>>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, java.util.List<String>> eldest) {
                return size() > 100; // 最多缓存100条消息的选项
            }
        };

    // 记录已请求显示选项的消息ID，防止View复用时重置回按钮状态
    private static final java.util.Set<String> requestedOptionsMsgIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    
    // 【好感度缓存】senderUin -> affinity，用于View复用时快速获取
    // 这个缓存与 AffinityManager 的缓存不同，这里是为了解决 View 复用时的显示问题
    private static final java.util.Map<String, Integer> affinityDisplayCache = 
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Integer>(200, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Integer> eldest) {
                return size() > 200; // 最多缓存200个用户的好感度
            }
        });
    
    /**
     * 清空好感度显示缓存
     * 当用户修改计算模型时需要调用此方法，以便重新计算好感度
     */
    public static void clearAffinityDisplayCache() {
        affinityDisplayCache.clear();
        de.robv.android.xposed.XposedBridge.log(TAG + ": 好感度显示缓存已清空");
    }

    public static void init(ClassLoader classLoader) {
        // Detect QQ architecture and use appropriate hook strategy
        if (top.galqq.utils.QQNTUtils.isQQNT(classLoader)) {
            XposedBridge.log(TAG + ": Detected QQNT, using QQNT hook strategy");
            hookAIOBubbleMsgItemVB(classLoader);  // QQNT architecture
            
            // 【DEBUG】Hook AIOSendMsgVMDelegate to analyze message structure
            hookDebugAIOSendMsgVMDelegate(classLoader);
        } else {
            XposedBridge.log(TAG + ": Detected legacy QQ, using TextItemBuilder hook strategy");
            hookTextItemBuilder(classLoader);      // Legacy QQ architecture
        }
    }
    
    // 标记好感度管理器是否已初始化
    private static boolean sAffinityManagerInitialized = false;
    
    /**
     * 初始化好感度管理器（需要在有 Context 时调用）
     * 只会初始化一次
     */
    private static void initAffinityManager(Context context) {
        if (sAffinityManagerInitialized) {
            return; // 已经初始化过了
        }
        
        if (ConfigManager.isAffinityEnabled()) {
            try {
                XposedBridge.log(TAG + ": [Affinity] 开始初始化好感度管理器...");
                top.galqq.utils.AffinityManager affinityManager = 
                    top.galqq.utils.AffinityManager.getInstance(context);
                // 强制刷新数据
                affinityManager.refreshData(true, new top.galqq.utils.AffinityManager.RefreshCallback() {
                    @Override
                    public void onSuccess() {
                        XposedBridge.log(TAG + ": [Affinity] ✓ 好感度数据刷新成功");
                    }
                    
                    @Override
                    public void onFailure(Exception e) {
                        XposedBridge.log(TAG + ": [Affinity] ✗ 好感度数据刷新失败: " + e.getMessage());
                    }
                });
                sAffinityManagerInitialized = true;
                XposedBridge.log(TAG + ": [Affinity] 好感度管理器初始化完成");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": [Affinity] 初始化失败: " + t.getMessage());
            }
        }
    }

    private static void hookTextItemBuilder(ClassLoader classLoader) {
        try {
            // Try more indices to handle different QQ versions
            Class<?> textItemBuilderClass = top.galqq.utils.ReflectionUtils.findClassWithSynthetics(
                "com.tencent.mobileqq.activity.aio.item.TextItemBuilder", 
                classLoader,
                10, 7, 6, 3, 8, 1, 2, 4, 5, 9, 11, 12, 13, 14, 15
            );
            
            if (textItemBuilderClass == null) {
                XposedBridge.log(TAG + ": TextItemBuilder class not found, skipping hook");
                return;
            }
            
            XposedBridge.log(TAG + ": Found TextItemBuilder class: " + textItemBuilderClass.getName());
            
            // Find the target method
            Method targetMethod = null;
            String methodName = null;
            
            // Try both method names
            for (String name : new String[]{"F", "a"}) {
                for (Method m : textItemBuilderClass.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    if (!View.class.isAssignableFrom(m.getReturnType())) continue;
                    
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 5 && 
                        View.class.isAssignableFrom(params[2]) && 
                        ViewGroup.class.isAssignableFrom(params[3])) {
                        targetMethod = m;
                        methodName = name;
                        break;
                    }
                }
                if (targetMethod != null) break;
            }

            if (targetMethod == null) {
                XposedBridge.log(TAG + ": Failed to find target method in TextItemBuilder");
                return;
            }
            
            XposedBridge.log(TAG + ": Found target method: " + methodName);
            
            // Hook the method using QQ's approach (modify BaseChatItemLayout directly)
            XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object chatMessage = param.args[0];
                        RelativeLayout baseChatItemLayout = (RelativeLayout) param.args[3];
                        
                        if (baseChatItemLayout == null) return;
                        
                        Context context = baseChatItemLayout.getContext();
                        ConfigManager.init(context);
                        
                        if (!ConfigManager.isModuleEnabled()) return;

                        boolean isSend = XposedHelpers.getBooleanField(chatMessage, "isSend");
                        if (isSend) return;
                        
                        // 如果选项条已存在，总是更新内容（解决视图复用导致的内容混乱）
                        if (baseChatItemLayout.findViewById(OPTION_BAR_ID) != null) {
                            LinearLayout existingBar = baseChatItemLayout.findViewById(OPTION_BAR_ID);
                            existingBar.removeAllViews();
                            String msgContent = (String) XposedHelpers.getObjectField(chatMessage, "msg");
                            setupOptionBarContent(context, existingBar, msgContent, chatMessage, null, null);
                            return;
                        }
                        
                        LinearLayout optionBar = createOptionBar(context, chatMessage);
                        optionBar.setId(OPTION_BAR_ID);
                        
                        // 获取屏幕宽度
                        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
                        int barWidth = screenWidth - dp2px(context, 24); // 左8+右16=24dp
                        
                        XposedBridge.log(TAG + ": Screen width=" + screenWidth + ", bar width=" + barWidth);
                        
                        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                            barWidth, // 使用计算出的具体宽度
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        );
                        params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                        params.leftMargin = dp2px(context, 8);  // 左边距8dp
                        params.rightMargin = dp2px(context, 16); // 右边距16dp（冗余但保留）
                        params.bottomMargin = dp2px(context, 5);
                        
                        baseChatItemLayout.addView(optionBar, params);
                        
                        XposedBridge.log(TAG + ": Successfully added option bar to BaseChatItemLayout");
                        
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Error in afterHook: " + t.getMessage());
                    }
                }
            });
            
            XposedBridge.log(TAG + ": ✓ Successfully hooked TextItemBuilder." + methodName);
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook TextItemBuilder: " + t.getMessage());
        }
    }

    private static LinearLayout createOptionBar(Context context, Object chatMessage) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.LEFT);
        bar.setPadding(0, dp2px(context, 5), 0, dp2px(context, 5));
        
        String msgContent = (String) XposedHelpers.getObjectField(chatMessage, "msg");
        setupOptionBarContent(context, bar, msgContent, chatMessage, null, null);
        return bar;
    }
    
    private static void updateOptionBar(Context context, LinearLayout bar, Object chatMessage) {
        String msgContent = (String) XposedHelpers.getObjectField(chatMessage, "msg");
        bar.removeAllViews();
        setupOptionBarContent(context, bar, msgContent, chatMessage, null, null);
    }

    // 公开缓存方法供队列恢复时使用
    public static void cacheOptions(String msgId, List<String> options) {
        if (msgId != null && options != null) {
            optionsCache.put(msgId, new java.util.ArrayList<>(options));
            // XposedBridge.log(TAG + ": Cached AI options for msgId=" + msgId);
        }
    }

    private static void setupOptionBarContent(Context context, LinearLayout bar, String msgContent, 
                                               Object msgObj, String msgId, String conversationId) {
        if (ConfigManager.isAiEnabled()) {
            // 添加加载指示器 (Loading Text)
            bar.removeAllViews();
            // 加载时减少顶部间距，使其更贴近消息
            bar.setPadding(0, 0, 0, dp2px(context, 5));
            
            TextView tvLoading = new TextView(context);
            tvLoading.setText("加载中");
            tvLoading.setTextSize(12); // 小字体
            tvLoading.setTextColor(Color.parseColor("#999999")); // 浅灰色
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            lp.leftMargin = dp2px(context, 2); // 稍微偏移一点
            tvLoading.setLayoutParams(lp);
            
            bar.addView(tvLoading);
            bar.setVisibility(View.VISIBLE);

            // 启动呼吸动画 "..." (变长变短)
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable animator = new Runnable() {
                int count = 0;
                @Override
                public void run() {
                    // 检查View是否还在显示，如果已被移除或隐藏则停止动画
                    if (tvLoading.getParent() == null || bar.getVisibility() != View.VISIBLE) {
                        return;
                    }
                    
                    StringBuilder sb = new StringBuilder("加载中");
                    // 动画逻辑：0->1->2->3->2->1->0 (循环)
                    int phase = count % 6;
                    int dots = phase <= 3 ? phase : 6 - phase;
                    
                    for (int i = 0; i < dots; i++) sb.append(".");
                    tvLoading.setText(sb.toString());
                    
                    count++;
                    handler.postDelayed(this, 400); // 400ms刷新一次
                }
            };
            handler.postDelayed(animator, 400);
            
            // 获取上下文消息（如果启用）
            List<MessageContextManager.ChatMessage> contextMessages = null;
            if (ConfigManager.isContextEnabled() && conversationId != null) {
                int contextCount = ConfigManager.getContextMessageCount();
                // 多获取一条，以便如果最后一条是当前消息时移除
                contextMessages = MessageContextManager.getContext(conversationId, contextCount + 1);
                
                // 去除当前消息（如果它已经被存入上下文）
                if (!contextMessages.isEmpty()) {
                    MessageContextManager.ChatMessage lastMsg = contextMessages.get(contextMessages.size() - 1);
                    // 通过msgId判断（如果msgId不为空）
                    boolean isSameMsg = false;
                    if (msgId != null && lastMsg.msgId != null) {
                        if (msgId.equals(lastMsg.msgId)) {
                            isSameMsg = true;
                        }
                    } else {
                        // 降级：通过内容和时间戳判断（防止重复）
                        // 如果内容相同且时间差在1秒内
                        if (msgContent.equals(lastMsg.content) && 
                            Math.abs(System.currentTimeMillis() - lastMsg.timestamp) < 1000) {
                            isSameMsg = true;
                        }
                    }
                    
                    if (isSameMsg) {
                        contextMessages.remove(contextMessages.size() - 1);
                        // XposedBridge.log("Removed current message from context to avoid duplication");
                    }
                }
                
                // 确保数量不超过配置
                if (contextMessages.size() > contextCount) {
                    contextMessages = contextMessages.subList(contextMessages.size() - contextCount, contextMessages.size());
                }
            }

            // 判断选项条是否在屏幕可见区域（用于设置优先级）
            android.graphics.Rect rect = new android.graphics.Rect();
            boolean isVisible = bar.getGlobalVisibleRect(rect) && bar.isShown();
            AiRateLimitedQueue.Priority priority = isVisible ? 
                AiRateLimitedQueue.Priority.HIGH : 
                AiRateLimitedQueue.Priority.NORMAL;
            
            // 【新增】提取当前消息的元数据（发送人昵称和时间戳）
            String currentSenderName = null;
            long currentTimestamp = 0;
            try {
                // 尝试从msgObj（msgRecord）提取senderName
                Object remarkNameObj = XposedHelpers.getObjectField(msgObj, "sendRemarkName");
                if (remarkNameObj != null && !String.valueOf(remarkNameObj).trim().isEmpty()) {
                    currentSenderName = String.valueOf(remarkNameObj);
                } else {
                    Object nickNameObj = XposedHelpers.getObjectField(msgObj, "sendNickName");
                    if (nickNameObj != null) {
                        currentSenderName = String.valueOf(nickNameObj);
                    }
                }
                
                // 提取时间戳
                Object msgTimeObj = XposedHelpers.getObjectField(msgObj, "msgTime");
                if (msgTimeObj != null) {
                    currentTimestamp = Long.parseLong(String.valueOf(msgTimeObj)) * 1000L; // 秒转毫秒
                }
            } catch (Throwable t) {
                // 提取失败，使用默认值（null和0）
                XposedBridge.log(TAG + ": Failed to extract current message metadata: " + t.getMessage());
            }
            
            // 提交到限流队列（带优先级、上下文和当前消息元数据）
            // 使用支持重试的回调接口
            AiRateLimitedQueue.getInstance(context).submitRequest(
                context, 
                msgContent, 
                msgId, // 传递msgId用于持久化
                priority,
                contextMessages, // 传递上下文消息
                currentSenderName, // 当前消息发送人昵称
                currentTimestamp, // 当前消息时间戳
                new HttpAiClient.AiCallbackWithRetry() {
                    @Override
                    public void onSuccess(List<String> options) {
                        // 恢复顶部间距
                        bar.setPadding(0, dp2px(context, 5), 0, dp2px(context, 5));
                        
                        // 缓存AI结果
                        cacheOptions(msgId, options);
                        populateBarAndShow(context, bar, options, msgObj);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        // AI失败时隐藏选项条（已在UI线程）
                        bar.setVisibility(View.GONE);
                    }
                    
                    @Override
                    public void onAllRetriesFailed(Runnable retryAction) {
                        // 所有重试都失败后，显示"重新加载"按钮
                        showReloadButton(context, bar, retryAction, msgObj);
                    }
                }
            );
        } else {
            // 本地词库模式：每次随机生成，不使用缓存
            useDictionaryNT(context, bar, msgObj);
        }
    }

    /**
     * 显示"重新加载"按钮（当所有重试都失败后）
     * 使用与选项按钮相同的UI风格
     */
    private static void showReloadButton(Context context, LinearLayout bar, Runnable retryAction, Object chatMessage) {
        bar.removeAllViews();
        bar.setPadding(0, dp2px(context, 5), 0, dp2px(context, 5));
        
        TextView reloadBtn = new TextView(context);
        reloadBtn.setText("重新加载");
        reloadBtn.setTextSize(13);
        reloadBtn.setPadding(dp2px(context, 12), dp2px(context, 8), dp2px(context, 12), dp2px(context, 8));
        // 使用浅红色背景表示错误状态
        reloadBtn.setBackground(getSelectableRoundedBackground(Color.parseColor("#FFEBEE"), dp2px(context, 12)));
        reloadBtn.setTextColor(Color.parseColor("#D32F2F"));
        reloadBtn.setClickable(true);
        reloadBtn.setFocusable(true);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.START;
        reloadBtn.setLayoutParams(lp);
        
        reloadBtn.setOnClickListener(v -> {
            // 点击后显示加载状态
            bar.removeAllViews();
            bar.setPadding(0, 0, 0, dp2px(context, 5));
            
            TextView tvLoading = new TextView(context);
            tvLoading.setText("重新加载中...");
            tvLoading.setTextSize(12);
            tvLoading.setTextColor(Color.parseColor("#999999"));
            LinearLayout.LayoutParams loadingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            loadingLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            loadingLp.leftMargin = dp2px(context, 2);
            tvLoading.setLayoutParams(loadingLp);
            bar.addView(tvLoading);
            
            // 执行重试
            retryAction.run();
        });
        
        bar.addView(reloadBtn);
        bar.setVisibility(View.VISIBLE);
        
        XposedBridge.log(TAG + ": Showing reload button after all retries failed");
    }

    private static void useDictionary(Context context, LinearLayout bar, Object chatMessage) {
        DictionaryManager.loadDictionary(context);
        List<String> options = DictionaryManager.pickRandomLines(3);
        populateBarAndShow(context, bar, options, chatMessage);
    }

    // 填充选项条并显示（如果有选项的话）
    private static void populateBarAndShow(Context context, LinearLayout bar, List<String> options, Object chatMessage) {
        bar.removeAllViews();
        
        XposedBridge.log(TAG + ": populateBarAndShow - options count=" + (options != null ? options.size() : "null"));
        
        if (options == null || options.isEmpty()) {
            XposedBridge.log(TAG + ": No options available, hiding bar");
            bar.setVisibility(View.GONE); // 没有选项时隐藏
            return;
        }
        
        XposedBridge.log(TAG + ": Adding " + options.size() + " options to bar");
        for (String option : options) {
            TextView tv = new TextView(context);
            tv.setText(option);
            tv.setTextSize(13);
            tv.setPadding(dp2px(context, 12), dp2px(context, 8), dp2px(context, 12), dp2px(context, 8));
            // 使用带按压状态的背景，支持视觉反馈
            tv.setBackground(getSelectableRoundedBackground(Color.parseColor("#F2F2F2"), dp2px(context, 12)));
            tv.setTextColor(Color.BLACK);
            // 启用点击和焦点，确保按压状态生效
            tv.setClickable(true);
            tv.setFocusable(true);
            
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int maxWidth = screenWidth - dp2px(context, 16);
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, 0, dp2px(context, 6));
            tv.setMaxWidth(maxWidth);
            tv.setLayoutParams(lp);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            
            // 短按：直接发送消息
            tv.setOnClickListener(v -> {
                sendMessage(context, option, chatMessage);
            });
            
            // 长按：弹出编辑对话框
            tv.setOnLongClickListener(v -> {
                // 触觉反馈
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                // 显示编辑对话框
                showEditDialog(context, option, chatMessage);
                return true; // 消费事件，防止触发 onClick
            });
            
            bar.addView(tv);
        }
        
        bar.setVisibility(View.VISIBLE); // 有选项时显示
        XposedBridge.log(TAG + ": Option bar populated and made visible");
    }


    private static void sendMessage(Context context, String text, Object originMsg) {
        try {
            // Check if it's QQNT MsgRecord
            if (originMsg.getClass().getName().contains("qqnt")) {
                // Use SendMessageHelper for QQNT
                SendMessageHelper.sendMessageNT(context, originMsg, text);
                return;
            }

            Class<?> baseAppClass = XposedHelpers.findClass(
                "com.tencent.common.app.BaseApplicationImpl", 
                context.getClassLoader()
            );
            Object app = XposedHelpers.callStaticMethod(baseAppClass, "getApplication");
            Object runtime = XposedHelpers.callMethod(app, "getRuntime");
            
            Class<?> sessionInfoClass = XposedHelpers.findClass(
                "com.tencent.mobileqq.activity.aio.SessionInfo", 
                context.getClassLoader()
            );
            Object session = sessionInfoClass.newInstance();
            
            int istroop = XposedHelpers.getIntField(originMsg, "istroop");
            String frienduin = (String) XposedHelpers.getObjectField(originMsg, "frienduin");
            
            XposedHelpers.setIntField(session, "curType", istroop);
            XposedHelpers.setObjectField(session, "curFriendUin", frienduin);
            
            Class<?> facadeClass = XposedHelpers.findClass(
                "com.tencent.mobileqq.activity.ChatActivityFacade", 
                context.getClassLoader()
            );
            XposedHelpers.callStaticMethod(facadeClass, "sendMessage", runtime, session, text);
            
            Toast.makeText(context, "已发送: " + text, Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to send message: " + e.getMessage());
            Toast.makeText(context, "发送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 显示编辑对话框，允许用户修改选项内容后发送
     * 使用自定义 UI，风格更现代美观
     * @param context 上下文
     * @param originalText 原始选项文本
     * @param chatMessage 消息对象（用于发送）
     */
    private static void showEditDialog(Context context, String originalText, Object chatMessage) {
        // 创建半透明遮罩层
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(context);
        overlay.setBackgroundColor(Color.parseColor("#80000000")); // 半透明黑色
        
        // 创建对话框容器（白色圆角卡片）
        LinearLayout dialogContainer = new LinearLayout(context);
        dialogContainer.setOrientation(LinearLayout.VERTICAL);
        dialogContainer.setBackgroundDrawable(createDialogBackground());
        dialogContainer.setPadding(dp2px(context, 20), dp2px(context, 20), dp2px(context, 20), dp2px(context, 16));
        
        // 计算对话框宽度（屏幕宽度 - 48dp 边距）
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int dialogWidth = screenWidth - dp2px(context, 48);
        
        android.widget.FrameLayout.LayoutParams dialogParams = new android.widget.FrameLayout.LayoutParams(
            dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dialogParams.gravity = Gravity.CENTER;
        dialogContainer.setLayoutParams(dialogParams);
        
        // 标题
        TextView titleView = new TextView(context);
        titleView.setText("编辑回复");
        titleView.setTextSize(18);
        titleView.setTextColor(Color.parseColor("#333333"));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = dp2px(context, 16);
        titleView.setLayoutParams(titleParams);
        dialogContainer.addView(titleView);
        
        // 输入框容器（带边框的圆角背景）
        android.widget.FrameLayout inputContainer = new android.widget.FrameLayout(context);
        inputContainer.setBackgroundDrawable(createInputBackground());
        inputContainer.setPadding(dp2px(context, 12), dp2px(context, 8), dp2px(context, 12), dp2px(context, 8));
        LinearLayout.LayoutParams inputContainerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputContainerParams.bottomMargin = dp2px(context, 20);
        inputContainer.setLayoutParams(inputContainerParams);
        
        // 输入框
        android.widget.EditText editText = new android.widget.EditText(context);
        editText.setText(originalText);
        editText.setSelection(originalText.length());
        editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setMinLines(2);
        editText.setMaxLines(6);
        editText.setTextSize(15);
        editText.setTextColor(Color.parseColor("#333333"));
        editText.setHintTextColor(Color.parseColor("#999999"));
        editText.setHint("输入回复内容...");
        editText.setBackgroundColor(Color.TRANSPARENT); // 去掉默认下划线
        editText.setPadding(0, 0, 0, 0);
        editText.setGravity(Gravity.TOP | Gravity.START);
        inputContainer.addView(editText, new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        dialogContainer.addView(inputContainer);
        
        // 按钮容器
        LinearLayout buttonContainer = new LinearLayout(context);
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonContainerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        buttonContainer.setLayoutParams(buttonContainerParams);
        
        // 创建 Dialog 引用（用于关闭）
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        
        // 取消按钮
        TextView cancelBtn = createDialogButton(context, "取消", false);
        cancelBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cancelParams.rightMargin = dp2px(context, 12);
        cancelBtn.setLayoutParams(cancelParams);
        buttonContainer.addView(cancelBtn);
        
        // 发送按钮
        TextView sendBtn = createDialogButton(context, "发送", true);
        sendBtn.setOnClickListener(v -> {
            String modifiedText = editText.getText().toString().trim();
            if (!modifiedText.isEmpty()) {
                sendMessage(context, modifiedText, chatMessage);
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            } else {
                Toast.makeText(context, "内容不能为空", Toast.LENGTH_SHORT).show();
            }
        });
        buttonContainer.addView(sendBtn);
        
        dialogContainer.addView(buttonContainer);
        
        // 将对话框添加到遮罩层
        overlay.addView(dialogContainer);
        
        // 点击遮罩层关闭对话框
        overlay.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        });
        
        // 阻止点击对话框时关闭
        dialogContainer.setOnClickListener(v -> {});
        
        // 创建 Dialog
        android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setContentView(overlay);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialogRef[0] = dialog;
        
        // 设置 Enter 键发送
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || 
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                String modifiedText = editText.getText().toString().trim();
                if (!modifiedText.isEmpty()) {
                    sendMessage(context, modifiedText, chatMessage);
                    dialog.dismiss();
                }
                return true;
            }
            return false;
        });
        
        dialog.show();
        
        // 自动显示键盘
        editText.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }
    
    /**
     * 创建对话框背景（白色圆角）
     */
    private static android.graphics.drawable.Drawable createDialogBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setColor(Color.WHITE);
        drawable.setCornerRadius(24); // 12dp 圆角
        return drawable;
    }
    
    /**
     * 创建输入框背景（浅灰色圆角边框）
     */
    private static android.graphics.drawable.Drawable createInputBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setColor(Color.parseColor("#F5F5F5"));
        drawable.setCornerRadius(16); // 8dp 圆角
        drawable.setStroke(2, Color.parseColor("#E0E0E0")); // 1dp 边框
        return drawable;
    }
    
    /**
     * 创建对话框按钮
     * @param context 上下文
     * @param text 按钮文字
     * @param isPrimary 是否为主按钮（发送按钮）
     */
    private static TextView createDialogButton(Context context, String text, boolean isPrimary) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp2px(context, 20), dp2px(context, 10), dp2px(context, 20), dp2px(context, 10));
        
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp2px(context, 20)); // 圆角按钮
        
        if (isPrimary) {
            // 主按钮：蓝色背景，白色文字
            bg.setColor(Color.parseColor("#4A90D9"));
            button.setTextColor(Color.WHITE);
        } else {
            // 次按钮：透明背景，灰色文字
            bg.setColor(Color.TRANSPARENT);
            button.setTextColor(Color.parseColor("#666666"));
        }
        
        button.setBackgroundDrawable(bg);
        button.setClickable(true);
        button.setFocusable(true);
        
        return button;
    }
    
    // ========== QQNT Architecture Hook ==========
    
    private static void hookAIOBubbleMsgItemVB(ClassLoader classLoader) {
        try {
            Class<?> kAIOBubbleMsgItemVB = Class.forName(
                "com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB",
                false,
                classLoader
            );
            
            Class<?> kAIOMsgItem = Class.forName("com.tencent.mobileqq.aio.msg.AIOMsgItem", false, classLoader);
            Method getMsgRecord = kAIOMsgItem.getMethod("getMsgRecord");
            
            // 1. Try to find handleUIState (Newer QQNT)
            Method handleUIState = null;
            try {
                for (Method m : kAIOBubbleMsgItemVB.getDeclaredMethods()) {
                    if ("handleUIState".equals(m.getName())) {
                        handleUIState = m;
                        break;
                    }
                }
            } catch (Exception e) {}

            if (handleUIState != null) {
                XposedBridge.log(TAG + ": Found handleUIState method");
                XposedBridge.hookMethod(handleUIState, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object uiState = param.args[0];
                        if (uiState == null) return;
                        
                        // QAuxiliary calls "b()" on uiState to get msgItem
                        Object msgItem = null;
                        try {
                            Method b = uiState.getClass().getMethod("b");
                            b.setAccessible(true);
                            msgItem = b.invoke(uiState);
                        } catch (Exception e) {
                            // Fallback: search for method returning AIOMsgItem
                            for (Method m : uiState.getClass().getDeclaredMethods()) {
                                if (m.getParameterCount() == 0 && kAIOMsgItem.isAssignableFrom(m.getReturnType())) {
                                    m.setAccessible(true);
                                    msgItem = m.invoke(uiState);
                                    break;
                                }
                            }
                        }
                        
                        if (msgItem == null) return;
                        
                        processQQNTMessage(param.thisObject, msgItem, getMsgRecord);
                    }
                });
                return;
            }

            // 2. Fallback to bind method (Older QQNT)
            Method bindMethod = null;
            for (Method m : kAIOBubbleMsgItemVB.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (m.getReturnType() == Void.TYPE && params.length == 4) {
                    // Check params: int, AIOMsgItem(or super), List, Bundle
                    if (params[0] == Integer.TYPE && 
                        params[1].isAssignableFrom(kAIOMsgItem) && // Check if params[1] is super of AIOMsgItem
                        params[2] == List.class &&
                        params[3] == android.os.Bundle.class) {
                        bindMethod = m;
                        break;
                    }
                }
            }
            
            if (bindMethod != null) {
                XposedBridge.log(TAG + ": Found bind method");
                XposedBridge.hookMethod(bindMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object msgItem = param.args[1];
                        processQQNTMessage(param.thisObject, msgItem, getMsgRecord);
                    }
                });
                return;
            }
            
            XposedBridge.log(TAG + ": Failed to find handleUIState or bind method in AIOBubbleMsgItemVB");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error in hookAIOBubbleMsgItemVB: " + t.getMessage());
            XposedBridge.log(t);
        }
    }

    private static void processQQNTMessage(Object aioBubbleMsgItemVB, Object msgItem, Method getMsgRecord) {
        try {
            // Get MsgRecord
            Object msgRecord = getMsgRecord.invoke(msgItem);
            
            // Get root ViewGroup via getHostView
            Method getHostView = aioBubbleMsgItemVB.getClass().getMethod("getHostView");
            ViewGroup rootView = (ViewGroup) getHostView.invoke(aioBubbleMsgItemVB);
            
            if (rootView == null || msgRecord == null) return;
            
            Context context = rootView.getContext();
            ConfigManager.init(context);
            
            // 【方法1】识别 Activity/Context 类型
            try {
                String activityName = "unknown";
                if (context instanceof Activity) {
                    activityName = context.getClass().getName();
                } else {
                    // 尝试解包 ContextWrapper
                    Context currentCtx = context;
                    while (currentCtx instanceof android.content.ContextWrapper) {
                        if (currentCtx instanceof Activity) {
                            activityName = currentCtx.getClass().getName();
                            break;
                        }
                        currentCtx = ((android.content.ContextWrapper) currentCtx).getBaseContext();
                    }
                }
                // XposedBridge.log(TAG + ": [Context] Activity/Context: " + activityName);
                
                // 【转发消息过滤】如果是转发消息详情页，则不显示AI选项条
                if (activityName != null && activityName.contains("MultiForwardActivity")) {
                    XposedBridge.log(TAG + ": ⚠️ Skipping forwarded message in MultiForwardActivity");
                    return;
                }
            } catch (Throwable t) {
                // XposedBridge.log(TAG + ": [Context] Error getting activity: " + t.getMessage());
            }
            
            // 【方法2】识别 ViewHolder 类型
            try {
                String viewHolderName = aioBubbleMsgItemVB.getClass().getName();
                // XposedBridge.log(TAG + ": [ViewHolder] Type: " + viewHolderName);
            } catch (Throwable t) {
                // XposedBridge.log(TAG + ": [ViewHolder] Error: " + t.getMessage());
            }
            
            // 【方法3】识别 RecyclerView 的 Adapter 类型 (使用反射)
            try {
                android.view.ViewParent parent = rootView.getParent();
                boolean foundRecyclerView = false;
                while (parent != null) {
                    Class<?> parentClass = parent.getClass();
                    // 检查是否是 RecyclerView (通过类名判断，避免依赖)
                    if (isRecyclerView(parentClass)) {
                        foundRecyclerView = true;
                        // 反射调用 getAdapter
                        try {
                            Method getAdapterMethod = parentClass.getMethod("getAdapter");
                            Object adapter = getAdapterMethod.invoke(parent);
                            if (adapter != null) {
                                // XposedBridge.log(TAG + ": [Adapter] Type: " + adapter.getClass().getName());
                            } else {
                                // XposedBridge.log(TAG + ": [Adapter] Adapter is null");
                            }
                        } catch (NoSuchMethodException e) {
                            // 可能是混淆后的名字，或者不是标准的RecyclerView
                            // XposedBridge.log(TAG + ": [Adapter] getAdapter method not found on " + parentClass.getName());
                        }
                        break;
                    }
                    parent = parent.getParent();
                }
                if (!foundRecyclerView) {
                    // XposedBridge.log(TAG + ": [Adapter] RecyclerView not found in parent hierarchy");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": [Adapter] Error: " + t.getMessage());
            }
            
            // Check if module is enabled
            if (!ConfigManager.isModuleEnabled()) {
                return; // Module is disabled, don't show option bar
            }
            
            // Check if it's a received message
            int sendType = XposedHelpers.getIntField(msgRecord, "sendType");
            boolean isSelf = (sendType == 1); // 1=自己发送, 0=收到的消息

            // Filter out unwanted message types
            int msgType = XposedHelpers.getIntField(msgRecord, "msgType");
            
            // 【过滤转发聊天记录容器】msgType=11且subMsgType=7是转发聊天记录的容器消息
            try {
                int subMsgType = XposedHelpers.getIntField(msgRecord, "subMsgType");
                
                // XposedBridge.log(TAG + ": ===== Message Type Analysis =====");
                // XposedBridge.log(TAG + ": msgType=" + msgType + ", subMsgType=" + subMsgType);
                // XposedBridge.log(TAG + ": sendType=" + sendType);
                
                if (msgType == 11 && subMsgType == 7) {
                    // XposedBridge.log(TAG + ": ⚠️ FORWARDED CHAT RECORD CONTAINER - SKIPPING!");
                    return; // 跳过转发聊天记录容器
                }
                
                // XposedBridge.log(TAG + ": ================================");
            } catch (Throwable t) {
                // XposedBridge.log(TAG + ": Error detecting forwarded message: " + t.getMessage());
            }
            
            // 5 = Gray Tips (Revoke), 3 = File, 7 = Video
            if (msgType == 5 || msgType == 3 || msgType == 7) {
                return;
            }

            // Filter out messages with no text content (e.g. pure images, stickers)
            String msgContent = getMessageContentNT(msgRecord);
            if (msgContent.isEmpty()) {
                return;
            }
            
            // 【关键修复】无条件清理旧选项条和好感度视图（RecyclerView的ViewHolder会复用）
            // 使用View接收，避免ClassCastException（因为可能是LinearLayout也可能是TextView）
            View existingView = rootView.findViewById(OPTION_BAR_ID);
            if (existingView != null) {
                if (existingView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) existingView.getParent()).removeView(existingView);
                } else {
                    rootView.removeView(existingView);
                }
            }
            
            // 【关键修复】清理好感度视图 - 遍历所有可能的位置
            // 好感度视图可能被添加到 rootView 或其子 ViewGroup 中
            removeAffinityViewRecursively(rootView);
            
            // 黑白名单过滤
            String senderUin = null;
            try {
                Object senderUinObj = XposedHelpers.getObjectField(msgRecord, "senderUin");
                senderUin = String.valueOf(senderUinObj);
                String filterMode = ConfigManager.getFilterMode();
                
                // XposedBridge.log(TAG + ": Filter - senderUin=" + senderUin + ", mode=" + filterMode);
                
                if ("blacklist".equals(filterMode)) {
                    if (ConfigManager.isInBlacklist(senderUin)) {
                        // XposedBridge.log(TAG + ": ✗ BLOCKED by blacklist: " + senderUin);
                        return; // 不通过过滤，不添加选项条
                    }
                } else if ("whitelist".equals(filterMode)) {
                    if (!ConfigManager.isInWhitelist(senderUin)) {
                        // XposedBridge.log(TAG + ": ✗ BLOCKED by whitelist (not in list): " + senderUin);
                        return; // 不通过过滤，不添加选项条
                    }
                }
                // XposedBridge.log(TAG + ": ✓ PASSED filter: " + senderUin);
                
                // 【详细调试日志】已禁用
                // XposedBridge.log(TAG + ": isSelf=" + isSelf);
                // XposedBridge.log(TAG + ": senderUin=" + senderUin);
                // if (msgContent != null && !msgContent.isEmpty()) {
                //     String contentPreview = msgContent.length() > 50 ? msgContent.substring(0, 50) + "..." : msgContent;
                //     XposedBridge.log(TAG + ": msgContent=" + contentPreview);
                // }
            } catch (Throwable t) {
                // XposedBridge.log(TAG + ": Error filtering by list: " + t.getMessage());
                // XposedBridge.log(t);
                return; // 过滤失败时不添加选项条
            }
            
            // 获取消息ID（用于AI缓存和上下文去重）
            String msgId = null;
            try {
                Object msgIdObj = XposedHelpers.getObjectField(msgRecord, "msgId");
                msgId = String.valueOf(msgIdObj);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to get msgId: " + t.getMessage());
            }
            
            // 保存消息到上下文缓存（带去重）
            String peerUin = null; // 提升作用域
            long msgTime = 0; // 【修复】提升作用域，供后续AI判断使用
            try {
                // 获取发送人昵称（优先使用备注名，其次QQ昵称）
                String senderName = null;
                try {
                    // 优先尝试获取备注名
                    Object remarkNameObj = XposedHelpers.getObjectField(msgRecord, "sendRemarkName");
                    if (remarkNameObj != null && !String.valueOf(remarkNameObj).trim().isEmpty()) {
                        senderName = String.valueOf(remarkNameObj);
                    } else {
                        // 备注名为空，尝试获取QQ昵称
                        Object nickNameObj = XposedHelpers.getObjectField(msgRecord, "sendNickName");
                        if (nickNameObj != null && !String.valueOf(nickNameObj).trim().isEmpty()) {
                            senderName = String.valueOf(nickNameObj);
                        }
                    }
                } catch (Throwable t) {
                    // 字段获取失败
                    XposedBridge.log(TAG + ": Failed to get sender name: " + t.getMessage());
                }
                
                // 如果所有尝试都失败，使用UIN
                if (senderName == null || senderName.trim().isEmpty()) {
                    senderName = senderUin != null ? senderUin : "未知";
                }
                
                // 获取peerUin（会话ID）
                try {
                    Object peerUinObj = XposedHelpers.getObjectField(msgRecord, "peerUin");
                    peerUin = String.valueOf(peerUinObj);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": Failed to get peerUin: " + t.getMessage());
                }

                // 使用peerUin作为conversationId（群聊时为群号，私聊时为对方QQ）
                // 这样可以确保群聊中不同用户的消息被聚合到同一个上下文中
                if (peerUin != null && !msgContent.isEmpty()) {
                    // 获取消息时间戳
                    try {
                        Object msgTimeObj = XposedHelpers.getObjectField(msgRecord, "msgTime");
                        if (msgTimeObj != null) {
                            // msgTime通常是秒，转换为毫秒
                            msgTime = Long.parseLong(String.valueOf(msgTimeObj)) * 1000L;
                        }
                    } catch (Throwable t) {
                        // 失败则使用当前时间（降级）
                        msgTime = System.currentTimeMillis();
                    }
                    
                    // 【新增】提取引用回复的内容并整合到消息
                    try {
                        List<?> elements = (List<?>) XposedHelpers.getObjectField(msgRecord, "elements");
                        if (elements != null && !elements.isEmpty()) {
                            // 【调试日志已禁用】
                            // XposedBridge.log(TAG + ": Elements count: " + elements.size());
                            // for (int i = 0; i < elements.size(); i++) {
                            //     Object element = elements.get(i);
                            //     XposedBridge.log(TAG + ":   [" + i + "] " + element.getClass().getSimpleName());
                            //     
                            //     // 尝试所有可能的引用字段名
                            //     for (String fieldName : new String[]{"replyElement", "g", "h"}) {
                            //         try {
                            //             Object field = XposedHelpers.getObjectField(element, fieldName);
                            //             if (field != null) {
                            //                 XposedBridge.log(TAG + ":     ." + fieldName + " exists: " + field.getClass().getSimpleName());
                            //             }
                            //         } catch (Throwable ignored) {}
                            //     }
                            // }
                            
                            for (Object element : elements) {
                                try {
                                    // 尝试获取replyElement
                                    Object replyElement = XposedHelpers.getObjectField(element, "replyElement");
                                    if (replyElement != null) {
                                        // 提取引用的消息文本
                                        String replyText = null;
                                        try {
                                            Object replyTextObj = XposedHelpers.getObjectField(replyElement, "sourceMsgText");
                                            if (replyTextObj != null) {
                                                replyText = String.valueOf(replyTextObj);
                                            }
                                        } catch (Throwable ignored) {}
                                        
                                        // 提取引用消息的发送人
                                        String replySenderName = null;
                                        try {
                                            Object senderShowNameObj = XposedHelpers.getObjectField(replyElement, "senderShowName");
                                            if (senderShowNameObj != null) {
                                                replySenderName = String.valueOf(senderShowNameObj);
                                            }
                                        } catch (Throwable ignored) {}
                                        
                                        // 降级策略1：尝试从当前消息内容中解析 "@昵称 "
                                        if (replySenderName == null && msgContent != null) {
                                            String trimmedContent = msgContent.trim();
                                            if (trimmedContent.startsWith("@")) {
                                                int spaceIndex = trimmedContent.indexOf(' ');
                                                if (spaceIndex > 1) {
                                                    // 提取 @ 和 空格 之间的内容作为名字
                                                    String potentialName = trimmedContent.substring(1, spaceIndex);
                                                    // 简单的合法性检查（避免提取到过长的错误内容）
                                                    if (potentialName.length() < 20) {
                                                        replySenderName = potentialName;
                                                        // XposedBridge.log(TAG + ": Extracted reply sender from content: " + replySenderName);
                                                    }
                                                }
                                            }
                                        }
                                        
                                        // 降级策略2：使用 senderUid
                                        if (replySenderName == null) {
                                            try {
                                                long senderUid = XposedHelpers.getLongField(replyElement, "senderUid");
                                                if (senderUid > 0) {
                                                    replySenderName = String.valueOf(senderUid);
                                                } else {
                                                    // 尝试 senderUidStr
                                                    Object senderUidStrObj = XposedHelpers.getObjectField(replyElement, "senderUidStr");
                                                    if (senderUidStrObj != null) {
                                                        replySenderName = String.valueOf(senderUidStrObj);
                                                    }
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                        
                                        // 最终兜底
                                        if (replySenderName == null) {
                                            replySenderName = "某人";
                                        }
                                        
                                        // 如果成功提取引用内容，整合到当前消息内容中
                                        if (replyText != null && !replyText.trim().isEmpty()) {
                                            if (replySenderName == null || replySenderName.trim().isEmpty()) {
                                                replySenderName = "某人";
                                            }
                                            
                                            // 将引用信息附加到消息内容
                                            // 格式: 原消息内容 (回复 @被引用者: "被引用内容")
                                            msgContent = msgContent + " (回复 @" + replySenderName + ": \"" + replyText + "\")";
                                            
                                            XposedBridge.log(TAG + ": ✓ 已将引用信息整合到消息内容");
                                        }
                                        break; // 只处理第一个replyElement
                                    }
                                } catch (Throwable ignored) {
                                    // replyElement字段不存在或获取失败，继续下一个element
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Error extracting reply content: " + t.getMessage());
                    }
                    
                    // 【修改自己消息的显示格式为"昵称[我]"】
                    if (isSelf && senderName != null && !senderName.isEmpty()) {
                        senderName = senderName + "[我]";
                    }
                    
                    MessageContextManager.addMessage(peerUin, senderName, msgContent, isSelf, msgId, msgTime);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Error saving message to context: " + t.getMessage());
            }
            
            // 【只为对方消息创建选项栏和好感度视图，自己的消息不显示UI】
            // 注意：好感度视图已在前面统一清理过了
            if (!isSelf) {
                // 【好感度显示】如果启用，添加好感度视图
                final String finalSenderUin = senderUin;
                if (ConfigManager.isAffinityEnabled() && finalSenderUin != null && !finalSenderUin.isEmpty()) {
                    try {
                        // 初始化好感度管理器（首次调用时会触发数据获取）
                        initAffinityManager(context);
                        
                        // 【View复用修复】优先从显示缓存获取，避免重复计算
                        int affinity;
                        if (affinityDisplayCache.containsKey(finalSenderUin)) {
                            affinity = affinityDisplayCache.get(finalSenderUin);
                        } else {
                            // 从 AffinityManager 获取并缓存
                            top.galqq.utils.AffinityManager affinityManager = 
                                top.galqq.utils.AffinityManager.getInstance(context);
                            affinity = affinityManager.getAffinity(finalSenderUin);
                            
                            // 只缓存有效的好感度值（非-1）
                            if (affinity >= 0) {
                                affinityDisplayCache.put(finalSenderUin, affinity);
                            }
                        }
                        
                        // 【调试日志】打印好感度获取结果
                        // XposedBridge.log(TAG + ": [Affinity] senderUin=" + finalSenderUin + ", affinity=" + affinity);
                        
                        // 【修改】只有当好感度有效时才创建视图，无好感度数据的用户不显示
                        if (affinity >= 0) {
                            // 创建好感度视图
                            TextView affinityView = top.galqq.utils.AffinityViewHelper.createAffinityView(context, affinity);
                            
                            // 添加到布局
                            addAffinityViewToLayout(context, rootView, affinityView, msgRecord);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": [Affinity] Error: " + t.getMessage());
                    }
                }
                
                // 【关键修复】防止加载历史记录时触发AI刷屏
                // 动态获取配置的阈值（秒转毫秒）
                int thresholdSeconds = ConfigManager.getHistoryThreshold();
                long thresholdMs = thresholdSeconds * 1000L;
                
                // 检查是否已缓存AI选项（如果有缓存，即使超过阈值也显示）
                boolean hasCachedOptions = (msgId != null && optionsCache.containsKey(msgId));
                
                long currentTime = System.currentTimeMillis();
                if (!hasCachedOptions && Math.abs(currentTime - msgTime) > thresholdMs) {
                    // XposedBridge.log(TAG + ": ⏳ Skipping option bar for old message without cache (diff=" + (currentTime - msgTime) + "ms)");
                    return; 
                }


                // 检查是否自动显示选项
                // 检查是否自动显示选项
                boolean autoShow = ConfigManager.isAutoShowOptionsEnabled();
                // 确保 conversationId 在作用域内
                String conversationId = (peerUin != null && !peerUin.isEmpty()) ? peerUin : senderUin;

                // 检查是否用户手动点击过显示
                boolean hasRequested = (msgId != null && requestedOptionsMsgIds.contains(msgId));
                // 检查是否有缓存结果（有结果也应该直接显示）
                boolean hasCache = (msgId != null && optionsCache.containsKey(msgId));
                
                if (autoShow || hasRequested || hasCache) {
                    // 自动显示模式：创建选项条并立即填充
                    // XposedBridge.log(TAG + ": AutoShow mode - creating option bar");
                    LinearLayout optionBar = createEmptyOptionBarNT(context);
                    optionBar.setId(OPTION_BAR_ID);
                    
                    Class<?> constraintLayoutClass = null;
                    try {
                        constraintLayoutClass = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintLayout", context.getClassLoader());
                    } catch (Throwable t) {
                        // Ignore if class not found
                    }

                    if (constraintLayoutClass != null && constraintLayoutClass.isAssignableFrom(rootView.getClass())) {
                        // XposedBridge.log(TAG + ": Using ConstraintLayout handler");
                        handleConstraintLayout(context, rootView, optionBar, msgRecord);
                    } else if (rootView.getClass().getName().contains("ConstraintLayout")) {
                        // XposedBridge.log(TAG + ": Using ConstraintLayout handler (fallback)");
                        handleConstraintLayout(context, rootView, optionBar, msgRecord);
                    } else {
                        // XposedBridge.log(TAG + ": Using LegacyLayout handler");
                        handleLegacyLayout(context, rootView, optionBar);
                    }
                    
                    // XposedBridge.log(TAG + ": Filling option bar content");
                    fillOptionBarContent(context, optionBar, msgRecord, msgId, conversationId);
                    // XposedBridge.log(TAG + ": AutoShow option bar created and filled successfully");
                } else {
                    // 按需显示模式：仅显示按钮
                    View button = createShowOptionsButton(context, msgRecord, msgId, conversationId, rootView);
                    
                    Class<?> constraintLayoutClass = null;
                    try {
                        constraintLayoutClass = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintLayout", context.getClassLoader());
                    } catch (Throwable t) {
                        // Ignore if class not found
                    }

                    if (constraintLayoutClass != null && constraintLayoutClass.isAssignableFrom(rootView.getClass())) {
                        handleConstraintLayout(context, rootView, button, msgRecord);
                    } else if (rootView.getClass().getName().contains("ConstraintLayout")) {
                        handleConstraintLayout(context, rootView, button, msgRecord);
                    } else {
                        handleLegacyLayout(context, rootView, button);
                    }
                }
            }
            
            // XposedBridge.log(TAG + ": Successfully added option bar to QQNT message");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error processing QQNT message: " + t.getMessage());
            XposedBridge.log(t);
        }
    }

    /**
     * 递归清理好感度视图
     * 由于 View 复用，好感度视图可能被添加到不同的位置
     */
    private static void removeAffinityViewRecursively(ViewGroup parent) {
        if (parent == null) return;
        
        int affinityViewId = top.galqq.utils.AffinityViewHelper.AFFINITY_VIEW_ID;
        
        // 先检查直接子 View
        View affinityView = parent.findViewById(affinityViewId);
        if (affinityView != null) {
            ViewGroup actualParent = (ViewGroup) affinityView.getParent();
            if (actualParent != null) {
                actualParent.removeView(affinityView);
            }
        }
        
        // 遍历所有子 View，查找并移除
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child.getId() == affinityViewId) {
                parent.removeViewAt(i);
            } else if (child instanceof ViewGroup) {
                // 递归检查子 ViewGroup
                removeAffinityViewRecursively((ViewGroup) child);
            }
        }
    }

    /**
     * 添加好感度视图到布局
     * 使用与选项条完全相同的方式：找到消息气泡，定位到气泡上方
     * 不使用任何降级逻辑，只支持 ConstraintLayout
     */
    private static void addAffinityViewToLayout(Context context, ViewGroup rootView, View affinityView, Object msgRecord) {
        try {
            // 1. 添加视图到 ConstraintLayout（与选项条相同）
            Class<?> constraintLayoutParamsClass = XposedHelpers.findClass(
                "androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", 
                context.getClassLoader()
            );
            ViewGroup.LayoutParams clp = (ViewGroup.LayoutParams) constraintLayoutParamsClass
                .getConstructor(int.class, int.class)
                .newInstance(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            
            rootView.addView(affinityView, clp);
            
            // 2. 使用 ConstraintSet 定位（与选项条相同）
            Class<?> constraintSetClass = XposedHelpers.findClass(
                "androidx.constraintlayout.widget.ConstraintSet", 
                context.getClassLoader()
            );
            Object constraintSet = constraintSetClass.newInstance();
            XposedHelpers.callMethod(constraintSet, "clone", rootView);
            
            // 3. 查找消息气泡（与选项条使用完全相同的逻辑）
            int msgBubbleId = -1;
            
            // 3.1 BubbleLayout Class Name Search (Priority)
            for (int i = 0; i < rootView.getChildCount(); i++) {
                View child = rootView.getChildAt(i);
                if (child.getClass().getName().contains("BubbleLayout") && child.getId() != View.NO_ID) {
                    msgBubbleId = child.getId();
                    break;
                }
            }
            
            // 3.2 Text Content Search (Fallback)
            if (msgBubbleId == -1) {
                String msgContent = getMessageContentNT(msgRecord);
                if (!msgContent.isEmpty()) {
                    View textContainer = findViewWithText(rootView, msgContent);
                    if (textContainer != null) {
                        View bubble = textContainer;
                        while (bubble.getParent() != rootView && bubble.getParent() instanceof View) {
                            bubble = (View) bubble.getParent();
                        }
                        
                        if (bubble.getParent() == rootView && bubble.getId() != View.NO_ID) {
                            msgBubbleId = bubble.getId();
                        }
                    }
                }
            }
            
            // 3.3 LinearLayout Fallback (Last Resort)
            if (msgBubbleId == -1) {
                for (int i = 0; i < rootView.getChildCount(); i++) {
                    View child = rootView.getChildAt(i);
                    if (child instanceof LinearLayout && child.getId() != View.NO_ID) {
                        msgBubbleId = child.getId();
                        break;
                    }
                }
            }
            
            // 4. 如果找不到气泡，不添加好感度视图（不降级）
            if (msgBubbleId == -1) {
                // 移除已添加的视图
                rootView.removeView(affinityView);
                XposedBridge.log(TAG + ": [Affinity] Could not find message bubble, skipping affinity view");
                return;
            }
            
            // 5. 设置约束（定位到气泡上方，左侧对齐父容器）
            int viewId = affinityView.getId();
            int parentId = 0; // ConstraintLayout.LayoutParams.PARENT_ID = 0
            // 约束常量: TOP=3, BOTTOM=4, LEFT=1, RIGHT=2, START=6, END=7
            int TOP = 3, BOTTOM = 4, LEFT = 1;
            
            // 底部连接到气泡顶部，增加间距使其更靠上（8dp）
            XposedHelpers.callMethod(constraintSet, "connect", viewId, BOTTOM, msgBubbleId, TOP, dp2px(context, 8));
            // 左侧对齐父容器左边，留4dp间距（显示在头像上方位置）
            XposedHelpers.callMethod(constraintSet, "connect", viewId, LEFT, parentId, LEFT, dp2px(context, 4));
            
            // 6. 应用约束
            XposedHelpers.callMethod(constraintSet, "applyTo", rootView);
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [Affinity] Error adding affinity view: " + t.getMessage());
            // 出错时移除视图
            try {
                rootView.removeView(affinityView);
            } catch (Throwable ignored) {}
        }
    }
    
    private static void handleLegacyLayout(Context context, ViewGroup rootView, View optionBar) {
        ViewGroup.LayoutParams lp;
        if (rootView instanceof RelativeLayout) {
            RelativeLayout.LayoutParams rlp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rlp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            rlp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            rlp.leftMargin = dp2px(context, 15);
            rlp.bottomMargin = dp2px(context, 5);
            lp = rlp;
        } else if (rootView instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            flp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            flp.leftMargin = dp2px(context, 15);
            flp.bottomMargin = dp2px(context, 5);
            lp = flp;
        } else {
            ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            mlp.leftMargin = dp2px(context, 15);
            lp = mlp;
        }
        rootView.addView(optionBar, lp);
    }

    private static void handleConstraintLayout(Context context, ViewGroup rootView, View optionBar, Object msgRecord) {
        try {
            // 1. Add view to ConstraintLayout first (needed for ConstraintSet to work)
            // 判断是否是按钮（TextView），如果是则用WRAP_CONTENT，否则用MATCH_CONSTRAINT(0)
            boolean isButton = (optionBar instanceof TextView);
            int widthParam = isButton ? ViewGroup.LayoutParams.WRAP_CONTENT : 0; // 0 = MATCH_CONSTRAINT

            // Use ConstraintLayout.LayoutParams if possible
            Class<?> constraintLayoutParamsClass = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", context.getClassLoader());
            ViewGroup.LayoutParams clp = (ViewGroup.LayoutParams) constraintLayoutParamsClass.getConstructor(int.class, int.class)
                .newInstance(widthParam, ViewGroup.LayoutParams.WRAP_CONTENT);
            
            rootView.addView(optionBar, clp);
            
            // 2. Use ConstraintSet to position it
            Class<?> constraintSetClass = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintSet", context.getClassLoader());
            Object constraintSet = constraintSetClass.newInstance();
            Class<?> constraintLayoutClass = rootView.getClass();
            
            // clone(ConstraintLayout)
            XposedHelpers.callMethod(constraintSet, "clone", rootView);
            
            // Find anchor views (Message Bubble)
            int msgBubbleId = -1;

            // 1. BubbleLayout Class Name Search (Priority)
            // XposedBridge.log(TAG + ": Trying to find bubble via BubbleLayout class name...");
            for (int i = 0; i < rootView.getChildCount(); i++) {
                View child = rootView.getChildAt(i);
                if (child.getClass().getName().contains("BubbleLayout") && child.getId() != View.NO_ID) {
                    msgBubbleId = child.getId();
                    // XposedBridge.log(TAG + ": Found bubble via class name, ID: " + msgBubbleId);
                    break;
                }
            }

            // 2. Text Content Search (Fallback)
            if (msgBubbleId == -1) {
                String msgContent = getMessageContentNT(msgRecord);
                if (!msgContent.isEmpty()) {
                    XposedBridge.log(TAG + ": BubbleLayout not found, trying text content: " + msgContent);
                    View textContainer = findViewWithText(rootView, msgContent);
                    if (textContainer != null) {
                        View bubble = textContainer;
                        while (bubble.getParent() != rootView && bubble.getParent() instanceof View) {
                            bubble = (View) bubble.getParent();
                        }
                        
                        if (bubble.getParent() == rootView && bubble.getId() != View.NO_ID) {
                            msgBubbleId = bubble.getId();
                            XposedBridge.log(TAG + ": Found bubble via text content, ID: " + msgBubbleId + ", Class: " + bubble.getClass().getName());
                        }
                    }
                }
            }

            // 3. LinearLayout Fallback (Last Resort)
            if (msgBubbleId == -1) {
                XposedBridge.log(TAG + ": Text search failed, trying LinearLayout fallback...");
                for (int i = 0; i < rootView.getChildCount(); i++) {
                    View child = rootView.getChildAt(i);
                    if (child instanceof LinearLayout && child.getId() != View.NO_ID) {
                        msgBubbleId = child.getId();
                        XposedBridge.log(TAG + ": Found bubble via LinearLayout fallback, ID: " + msgBubbleId);
                        break;
                    }
                }
            }
            
            if (msgBubbleId != -1) {
                // Connect TOP of OptionBar to BOTTOM of MessageBubble
                // connect(int startID, int startSide, int endID, int endSide, int margin)
                // Sides: TOP=3, BOTTOM=4, LEFT=1, RIGHT=2, START=6, END=7
                int TOP = 3, BOTTOM = 4, LEFT = 1, RIGHT = 2, START = 6, END = 7;
                
                XposedHelpers.callMethod(constraintSet, "connect", 
                    OPTION_BAR_ID, TOP, msgBubbleId, BOTTOM, dp2px(context, 5));
                
                // Align START (Left) of OptionBar to START (Left) of MessageBubble with 8dp margin
                // 向左移动选项条
                XposedHelpers.callMethod(constraintSet, "connect", 
                    OPTION_BAR_ID, START, msgBubbleId, START, dp2px(context, 8)); // 8dp左边距
                
                // 仅对非按钮（即选项条）添加右侧约束，避免按钮被拉伸
                if (!isButton) {
                    // 限制右边界到parent的END，留16dp margin
                    XposedHelpers.callMethod(constraintSet, "connect",
                        OPTION_BAR_ID, END, 0 /* PARENT_ID */, END, dp2px(context, 16)); // 16dp右边距
                }
                
                // Apply constraints
                XposedHelpers.callMethod(constraintSet, "applyTo", rootView);
                // XposedBridge.log(TAG + ": Applied ConstraintSet layout with margins");
            } else {
                XposedBridge.log(TAG + ": Could not find message bubble ID for ConstraintLayout");
            }
            
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error handling ConstraintLayout: " + e.getMessage());
            XposedBridge.log(e);
            // Fallback to simple add if failed
            if (optionBar.getParent() == null) {
                rootView.addView(optionBar);
            }
        }
    }

    // Helper to find view with text
    private static View findViewWithText(ViewGroup root, String text) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) {
                String viewText = ((TextView) child).getText().toString();
                // Check if viewText contains the msgContent (or vice versa, to be safe)
                if (viewText.contains(text) || text.contains(viewText)) {
                    return child;
                }
            } else if (child instanceof ViewGroup) {
                View found = findViewWithText((ViewGroup) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
    
    // 创建空的选项条（稍后填充内容）
    private static LinearLayout createEmptyOptionBarNT(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.LEFT);
        bar.setPadding(0, dp2px(context, 5), 0, dp2px(context, 5));
        bar.setVisibility(View.GONE); // 初始隐藏，等有内容后再显示
        return bar;
    }
    
    // 创建"显示选项"按钮
    private static View createShowOptionsButton(
        Context context, 
        Object msgRecord, 
        String msgId, 
        String conversationId,
        ViewGroup rootView
    ) {
        TextView button = new TextView(context);
        button.setText("显示选项");
        button.setTextSize(12);
        // 减小Padding，使其更紧凑
        button.setPadding(dp2px(context, 8), dp2px(context, 4), dp2px(context, 8), dp2px(context, 4));
        
        // 样式优化：模仿加载中，使用极淡背景或无背景
        // 这里使用一个非常淡的灰色背景，带圆角，类似Tag
        button.setBackground(getRoundedBackground(Color.parseColor("#F5F5F5"), dp2px(context, 10)));
        button.setTextColor(Color.parseColor("#333333")); // 深灰色，比黑色淡一点
        button.setGravity(Gravity.CENTER);
        
        button.setOnClickListener(v -> {
            // 记录该消息已请求显示选项
            if (msgId != null) {
                requestedOptionsMsgIds.add(msgId);
            }

            // 1. 移除按钮本身
            if (button.getParent() instanceof ViewGroup) {
                ((ViewGroup)button.getParent()).removeView(button);
            }
            
            // 创建选项条并填充
            LinearLayout optionBar = createEmptyOptionBarNT(context);
            optionBar.setId(OPTION_BAR_ID);
            
            // 添加到相同位置
            Class<?> constraintLayoutClass = null;
            try {
                constraintLayoutClass = XposedHelpers.findClass(
                    "androidx.constraintlayout.widget.ConstraintLayout", 
                    context.getClassLoader()
                );
            } catch (Throwable t) {
                // Ignore
            }
            
            if (constraintLayoutClass != null && constraintLayoutClass.isAssignableFrom(rootView.getClass())) {
                handleConstraintLayout(context, rootView, optionBar, msgRecord);
            } else if (rootView.getClass().getName().contains("ConstraintLayout")) {
                handleConstraintLayout(context, rootView, optionBar, msgRecord);
            } else {
                handleLegacyLayout(context, rootView, optionBar);
            }
            
            // 填充选项
            fillOptionBarContent(context, optionBar, msgRecord, msgId, conversationId);
        });
        
        button.setId(OPTION_BAR_ID); // 使用相同ID避免冲突
        return button;
    }
    
    // 填充选项条内容（AI或本地词库）
    private static void fillOptionBarContent(Context context, LinearLayout bar, Object msgRecord, 
                                             String msgId, String conversationId) {
        String msgContent = getMessageContentNT(msgRecord);
        
        // XposedBridge.log(TAG + ": fillOptionBarContent - msgId=" + msgId + 
        //     ", aiEnabled=" + ConfigManager.isAiEnabled() + 
        //     ", hasCache=" + (msgId != null && optionsCache.containsKey(msgId)) +
        //     ", msgContent=" + msgContent);
        
        // 【AI缓存优化】如果启用AI且缓存中有选项，直接使用缓存数据
        // 【AI缓存优化】如果启用AI且缓存中有选项，直接使用缓存数据
        if (ConfigManager.isAiEnabled() && msgId != null && optionsCache.containsKey(msgId)) {
            // XposedBridge.log(TAG + ": Using cached AI options for msgId=" + msgId);
            List<String> cachedOptions = optionsCache.get(msgId);
            populateBarAndShow(context, bar, cachedOptions, msgRecord);
            return;
        }
        
        // 否则重新获取选项（AI或本地词库）
        // XposedBridge.log(TAG + ": Setting up option bar content for msgContent=" + msgContent);
        setupOptionBarContent(context, bar, msgContent, msgRecord, msgId, conversationId);
    }
    
    private static void useDictionaryNT(Context context, LinearLayout bar, Object msgRecord) {
        DictionaryManager.loadDictionary(context);
        List<String> options = DictionaryManager.pickRandomLines(3);
        populateBarAndShow(context, bar, options, msgRecord);
    }
    
    private static String getMessageContentNT(Object msgRecord) {
        try {
            List<?> elements = (List<?>) XposedHelpers.getObjectField(msgRecord, "elements");
            if (elements == null || elements.isEmpty()) {
                return "";
            }
            
            StringBuilder content = new StringBuilder();
            for (Object element : elements) {
                Object textElement = XposedHelpers.getObjectField(element, "textElement");
                if (textElement != null) {
                    String text = (String) XposedHelpers.getObjectField(textElement, "content");
                    if (text != null) {
                        content.append(text);
                    }
                }
            }
            
            return content.toString();
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to extract message content: " + e.getMessage());
            return "";
        }
    }

    // Helper method for DP to PX conversion
    private static int dp2px(Context context, float dp) {
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    // Helper to create rounded background
    private static android.graphics.drawable.Drawable getRoundedBackground(int color, int radiusPx) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    /**
     * 将颜色加深指定比例
     * @param color 原始颜色
     * @param factor 加深因子 (0.0-1.0)，例如 0.1 表示加深 10%
     * @return 加深后的颜色
     */
    private static int darkenColor(int color, float factor) {
        int a = Color.alpha(color);
        int r = Math.max(0, (int) (Color.red(color) * (1 - factor)));
        int g = Math.max(0, (int) (Color.green(color) * (1 - factor)));
        int b = Math.max(0, (int) (Color.blue(color) * (1 - factor)));
        return Color.argb(a, r, g, b);
    }

    /**
     * 创建带按压状态的圆角背景
     * 普通状态使用原始颜色，按压状态使用加深的颜色
     * @param color 背景颜色
     * @param radiusPx 圆角半径（像素）
     * @return StateListDrawable
     */
    private static android.graphics.drawable.Drawable getSelectableRoundedBackground(int color, int radiusPx) {
        // 创建普通状态背景
        android.graphics.drawable.GradientDrawable normalDrawable = new android.graphics.drawable.GradientDrawable();
        normalDrawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        normalDrawable.setColor(color);
        normalDrawable.setCornerRadius(radiusPx);
        
        // 创建按压状态背景（颜色加深 10%）
        android.graphics.drawable.GradientDrawable pressedDrawable = new android.graphics.drawable.GradientDrawable();
        pressedDrawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        pressedDrawable.setColor(darkenColor(color, 0.1f));
        pressedDrawable.setCornerRadius(radiusPx);
        
        // 创建 StateListDrawable
        android.graphics.drawable.StateListDrawable stateListDrawable = new android.graphics.drawable.StateListDrawable();
        stateListDrawable.addState(new int[]{android.R.attr.state_pressed}, pressedDrawable);
        stateListDrawable.addState(new int[]{}, normalDrawable);
        
        return stateListDrawable;
    }

    // 辅助方法：判断类是否是 RecyclerView 或其子类
    private static boolean isRecyclerView(Class<?> clazz) {
        if (clazz == null) return false;
        if ("androidx.recyclerview.widget.RecyclerView".equals(clazz.getName()) || 
            "android.support.v7.widget.RecyclerView".equals(clazz.getName())) {
            return true;
        }
        return isRecyclerView(clazz.getSuperclass());
    }

    // 【DEBUG】Hook AIO消息发送相关类的所有方法以分析调用流程
    private static void hookDebugAIOSendMsgVMDelegate(ClassLoader classLoader) {
        String[] targetClasses = {
            // 尝试两个可能的包路径
            "com.tencent.mobileqq.aio.input.sendmsg.AIOSendMsgVMDelegate",
            "com.tencent.mobileqq.aio.msg.AIOSendMsgVMDelegate",
            "com.tencent.mobileqq.aio.msg.AIOSendMsgViewModel",
            "com.tencent.mobileqq.aio.input.sendmsg.AIOSendMsgViewModel"
        };

        for (String className : targetClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                
                int hookedCount = 0;
                // 遍历所有方法并 Hook
                for (Method method : clazz.getDeclaredMethods()) {
                    // 过滤掉常见的基础方法以减少日志噪音
                    String methodName = method.getName();
                    if (methodName.equals("toString") || methodName.equals("hashCode") || 
                        methodName.equals("equals") || methodName.equals("getClass")) {
                        continue;
                    }
                    
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                logMethodCall(param);
                            }
                        });
                        hookedCount++;
                    } catch (Throwable hookError) {
                        // 单个方法 Hook 失败不影响整体
                    }
                }
                
                // XposedBridge.log(TAG + ": [DEBUG] Hooked " + hookedCount + " methods in " + className);
            } catch (Throwable t) {
                // 类不存在或其他错误，跳过
            }
        }
    }




    private static void logMethodCall(XC_MethodHook.MethodHookParam param) {
        // DEBUG 日志已禁用
        /*
        try {
            String methodName = param.method.getName();
            String className = param.thisObject != null ? param.thisObject.getClass().getSimpleName() : "static";
            
            XposedBridge.log(TAG + ": [DEBUG] " + className + "." + methodName + " called");
            XposedBridge.log(TAG + ":   Args count: " + param.args.length);
            
            for (int i = 0; i < param.args.length; i++) {
                Object arg = param.args[i];
                String type = arg != null ? arg.getClass().getName() : "null";
                XposedBridge.log(TAG + ":   arg[" + i + "] (" + type + "): " + arg);
                
                // 如果参数是 List，尝试打印第一个元素的详细信息
                if (arg instanceof List) {
                    List<?> list = (List<?>) arg;
                    if (!list.isEmpty()) {
                        Object firstItem = list.get(0);
                        if (firstItem != null) {
                            XposedBridge.log(TAG + ":     List[0] class: " + firstItem.getClass().getName());
                            // 反射打印字段
                            try {
                                for (Field f : firstItem.getClass().getDeclaredFields()) {
                                    f.setAccessible(true);
                                    Object val = f.get(firstItem);
                                    String fieldType = f.getType().getName();
                                    XposedBridge.log(TAG + ":       Field '" + f.getName() + "' (" + fieldType + "): " + val);
                                    
                                    if (val != null && val.getClass().getName().contains("AIOElementType")) {
                                         XposedBridge.log(TAG + ":         -> Found potential element wrapper: " + val.getClass().getName());
                                    }
                                }
                            } catch (Exception e) {
                                XposedBridge.log(TAG + ":       Error inspecting list item: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": [DEBUG] Error logging method call: " + t.getMessage());
        }
        */
        
    }
}
