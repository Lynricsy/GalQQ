package top.galqq.utils;

import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * QQNT 消息发送助手 - 自动降级策略
 * 先尝试新版本方法，失败后自动回退到旧版本
 */
public class SendMessageHelper {
    private static final String TAG = "GalQQ.SendMessage";
    
    // 保存AIOSendMsgVMDelegate实例
    private static Object sAIOSendMsgVMDelegate = null;
    
    public static void setAIOSendMsgVMDelegate(Object vmDelegate) {
        sAIOSendMsgVMDelegate = vmDelegate;
        XposedBridge.log(TAG + ": 保存AIOSendMsgVMDelegate实例");
    }
    
    public static void sendMessageNT(Context context, Object msgRecord, String textToSend) {
        try {
            Object peerUid = XposedHelpers.getObjectField(msgRecord, "peerUid");
            String peerUidStr = String.valueOf(peerUid);
            
            XposedBridge.log(TAG + ": sendMessageNT called - peerUid=" + peerUidStr + ", text=" + textToSend);
            
            boolean success = sendTextMessage(context, peerUidStr, textToSend);
            if (!success) {
                android.widget.Toast.makeText(context, "发送失败", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sendMessageNT 失败");
            XposedBridge.log(t);
            android.widget.Toast.makeText(context, "发送失败: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    
    public static boolean sendTextMessage(Context context, String peerUid, String messageText) {
        try {
            //XposedBridge.log(TAG + ": 准备发送消息到 " + peerUid);
            //XposedBridge.log(TAG + ": 消息内容: " + messageText);
            
            ClassLoader classLoader = context.getClassLoader();
            
            // 1. 创建 TextElement 对象
            Object textElement = createTextElement(classLoader, messageText);
            if (textElement == null) {
                return false;
            }
            
            // 2. 创建 msg.data.a 对象（自动降级策略）
            Object msgData = createMsgDataWithTextAutoFallback(classLoader, textElement);
            if (msgData == null) {
                return false;
            }
            
            // 3. 创建消息列表
            List<Object> msgDataList = new ArrayList<>();
            msgDataList.add(msgData);
            
            // 4. 创建Bundle
            Bundle bundle = new Bundle();
            bundle.putString("input_text", messageText);
            bundle.putBoolean("from_send_btn", true);
            bundle.putInt("clearInputStatus", 1);
            
            // 5. 获取 AIOSendMsgVMDelegate 实例
            Object vmDelegate = getAIOSendMsgVMDelegate(context);
            if (vmDelegate == null) {
                return false;
            }
            
            // 6. 动态查找发送方法
            Method sendMethod = findSendMethod(vmDelegate.getClass());
            if (sendMethod == null) {
                XposedBridge.log(TAG + ": 未找到符合特征的发送方法(List, Bundle, Long, String)");
                return false;
            }
            
            sendMethod.setAccessible(true);
            sendMethod.invoke(vmDelegate, msgDataList, bundle, null, "");
            
            // XposedBridge.log(TAG + ": ✓ 消息发送成功！(Method: " + sendMethod.getName() + ")");
            return true;
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ✗ 发送消息失败");
            XposedBridge.log(t);
            return false;
        }
    }
    
    /**
     * 根据参数特征动态查找发送方法
     * 特征: (List, Bundle, Long, String)
     */
    private static Method findSendMethod(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 4 && 
                paramTypes[0] == List.class && 
                paramTypes[1] == Bundle.class && 
                paramTypes[2] == Long.class && 
                paramTypes[3] == String.class) {
                return method;
            }
        }
        return null;
    }
    
    private static Object createTextElement(ClassLoader classLoader, String content) {
        try {
            Class<?> textElementClass = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.nativeinterface.TextElement", classLoader);
            
            Constructor<?> constructor = textElementClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object textElement = constructor.newInstance();
            
            XposedHelpers.setObjectField(textElement, "content", content);
            XposedHelpers.setIntField(textElement, "atType", 0);
            XposedHelpers.setLongField(textElement, "atUid", 0L);
            XposedHelpers.setLongField(textElement, "atTinyId", 0L);
            XposedHelpers.setObjectField(textElement, "atNtUid", "");
            
            // XposedBridge.log(TAG + ": 创建TextElement成功: " + textElement);
            return textElement;
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 创建TextElement失败: " + t.getMessage());
            XposedBridge.log(t);
            return null;
        }
    }

    // ==================== 自动降级策略 ====================
    
    /**
     * 自动降级策略：先尝试新版本，失败后回退到旧版本
     */
    private static Object createMsgDataWithTextAutoFallback(ClassLoader classLoader, Object textElement) {
        //XposedBridge.log(TAG + ": [自动降级] 开始创建消息数据");
        
        // 提取 TextElement 字段
        String content = (String) XposedHelpers.getObjectField(textElement, "content");
        int atType = XposedHelpers.getIntField(textElement, "atType");
        long atUid = XposedHelpers.getLongField(textElement, "atUid");
        long atTinyId = XposedHelpers.getLongField(textElement, "atTinyId");
        String atNtUid = (String) XposedHelpers.getObjectField(textElement, "atNtUid");
        
        //XposedBridge.log(TAG + ": TextElement字段: content=" + content + ", atType=" + atType + 
        //    ", atUid=" + atUid + ", atTinyId=" + atTinyId + ", atNtUid=" + atNtUid);
        
        // 创建 msg.data.a 实例
        Object msgData = createMsgDataInstance(classLoader);
        if (msgData == null) {
            return null;
        }
        
        // 策略1: 尝试 AIOElementType$i (新版本)
        Object element = tryCreateAIOElementTypeI(classLoader, content, atType, atUid, atTinyId, atNtUid);
        if (element != null) {
            try {
                XposedHelpers.setIntField(msgData, "a", 1);
                XposedHelpers.setObjectField(msgData, "b", element);
                // XposedBridge.log(TAG + ": [策略1-新版本$i] 成功设置 msgData.a=1, msgData.b=AIOElementType$i");
                return msgData;
            } catch (Throwable t) {
                //XposedBridge.log(TAG + ": [策略1] 设置字段失败: " + t.getMessage());
            }
        }
        
        // 策略2: 尝试 AIOElementType$h (旧版本) 设置到字段 c
        element = tryCreateAIOElementTypeH(classLoader, content, atType, atUid, atTinyId, atNtUid);
        if (element != null) {
            try {
                XposedHelpers.setIntField(msgData, "a", 1);
                XposedHelpers.setIntField(msgData, "b", 0);
                XposedHelpers.setObjectField(msgData, "c", element);
                // XposedBridge.log(TAG + ": [策略2-旧版本$h] 成功设置 msgData.a=1, msgData.b=0, msgData.c=AIOElementType$h");
                return msgData;
            } catch (Throwable t) {
                //XposedBridge.log(TAG + ": [策略2] 设置字段失败: " + t.getMessage());
            }
        }
        
        // 策略3: 尝试 AIOElementType$i 设置到字段 c (QQ 9.2.25 版本)
        // 在这个版本中，msgData.b 是 int 类型，msgData.c 是 AIOElementType$i 类型
        element = tryCreateAIOElementTypeI(classLoader, content, atType, atUid, atTinyId, atNtUid);
        if (element != null) {
            try {
                XposedHelpers.setIntField(msgData, "a", 1);
                XposedHelpers.setObjectField(msgData, "c", element);
                // XposedBridge.log(TAG + ": [策略3-9.2.25版本] 成功设置 msgData.a=1, msgData.c=AIOElementType$i");
                return msgData;
            } catch (Throwable t) {
                //XposedBridge.log(TAG + ": [策略3] 设置字段失败: " + t.getMessage());
            }
        }
        
        XposedBridge.log(TAG + ": [自动降级] 所有策略都失败");
        return null;
    }
    
    /**
     * 尝试创建 AIOElementType$i (新版本)
     */
    private static Object tryCreateAIOElementTypeI(ClassLoader classLoader, String content, 
            int atType, long atUid, long atTinyId, String atNtUid) {
        try {
            Class<?> iClass = XposedHelpers.findClass(
                "com.tencent.qqnt.aio.msg.element.AIOElementType$i", classLoader);
            
            Constructor<?>[] constructors = iClass.getDeclaredConstructors();
            //XposedBridge.log(TAG + ": AIOElementType$i 有 " + constructors.length + " 个构造函数");
            
            for (int i = 0; i < constructors.length; i++) {
                Class<?>[] paramTypes = constructors[i].getParameterTypes();
                //XposedBridge.log(TAG + ":   构造函数[" + i + "]: " + Arrays.toString(paramTypes));
            }
            
            // 按优先级尝试不同构造函数
            for (Constructor<?> constructor : constructors) {
                constructor.setAccessible(true);
                Class<?>[] paramTypes = constructor.getParameterTypes();
                Object result = tryInvokeConstructor(constructor, paramTypes, content, atType, atUid, atTinyId, atNtUid);
                if (result != null) {
                    // XposedBridge.log(TAG + ": 创建AIOElementType$i成功: " + result);
                    return result;
                }
            }
        } catch (XposedHelpers.ClassNotFoundError e) {
            XposedBridge.log(TAG + ": AIOElementType$i 类不存在，跳过");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 创建AIOElementType$i异常: " + t.getMessage());
        }
        return null;
    }
    
    /**
     * 尝试创建 AIOElementType$h (旧版本)
     */
    private static Object tryCreateAIOElementTypeH(ClassLoader classLoader, String content, 
            int atType, long atUid, long atTinyId, String atNtUid) {
        try {
            Class<?> hClass = XposedHelpers.findClass(
                "com.tencent.qqnt.aio.msg.element.AIOElementType$h", classLoader);
            
            Constructor<?>[] constructors = hClass.getDeclaredConstructors();
            //XposedBridge.log(TAG + ": AIOElementType$h 有 " + constructors.length + " 个构造函数");
            
            for (int i = 0; i < constructors.length; i++) {
                Class<?>[] paramTypes = constructors[i].getParameterTypes();
                //XposedBridge.log(TAG + ":   构造函数[" + i + "]: " + Arrays.toString(paramTypes));
            }
            
            // 按优先级尝试不同构造函数
            for (Constructor<?> constructor : constructors) {
                constructor.setAccessible(true);
                Class<?>[] paramTypes = constructor.getParameterTypes();
                Object result = tryInvokeConstructor(constructor, paramTypes, content, atType, atUid, atTinyId, atNtUid);
                if (result != null) {
                    // XposedBridge.log(TAG + ": 创建AIOElementType$h成功: " + result);
                    return result;
                }
            }
        } catch (XposedHelpers.ClassNotFoundError e) {
            XposedBridge.log(TAG + ": AIOElementType$h 类不存在，跳过");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 创建AIOElementType$h异常: " + t.getMessage());
        }
        return null;
    }

    /**
     * 智能调用构造函数 - 根据参数类型自动匹配
     */
    private static Object tryInvokeConstructor(Constructor<?> constructor, Class<?>[] paramTypes,
            String content, int atType, long atUid, long atTinyId, String atNtUid) {
        try {
            int len = paramTypes.length;
            //XposedBridge.log(TAG + ": 尝试调用 " + len + " 参数构造函数");
            
            if (len == 0) {
                // 无参构造函数
                Object obj = constructor.newInstance();
                // 尝试设置字段
                trySetAllFields(obj, content, atType, atUid, atTinyId, atNtUid);
                // XposedBridge.log(TAG + ": 无参构造函数成功，已设置字段");
                return obj;
            }
            
            if (len == 1) {
                if (paramTypes[0] == String.class) {
                    return constructor.newInstance(content);
                }
            }
            
            if (len == 4) {
                // (long, long, String, String) - 根据日志
                if (paramTypes[0] == long.class && paramTypes[1] == long.class 
                    && paramTypes[2] == String.class && paramTypes[3] == String.class) {
                    //XposedBridge.log(TAG + ": 匹配4参数构造函数 (long, long, String, String)");
                    return constructor.newInstance(atUid, atTinyId, content, atNtUid);
                }
                // (String, int, long, long) - 另一种可能
                if (paramTypes[0] == String.class && paramTypes[1] == int.class) {
                    //XposedBridge.log(TAG + ": 匹配4参数构造函数 (String, int, long, long)");
                    return constructor.newInstance(content, atType, atUid, atTinyId);
                }
            }
            
            if (len == 5) {
                // (String, int, long, long, String)
                if (paramTypes[0] == String.class && paramTypes[1] == int.class) {
                    //XposedBridge.log(TAG + ": 匹配5参数构造函数 (String, int, long, long, String)");
                    return constructor.newInstance(content, atType, atUid, atTinyId, atNtUid);
                }
            }
            
            // 通用尝试：根据参数类型智能填充
            Object[] args = new Object[len];
            int stringIdx = 0;
            int longIdx = 0;
            
            for (int i = 0; i < len; i++) {
                Class<?> type = paramTypes[i];
                if (type == String.class) {
                    args[i] = (stringIdx == 0) ? content : atNtUid;
                    stringIdx++;
                } else if (type == int.class) {
                    args[i] = atType;
                } else if (type == long.class) {
                    args[i] = (longIdx == 0) ? atUid : atTinyId;
                    longIdx++;
                } else {
                    args[i] = null;
                }
            }
            
            //XposedBridge.log(TAG + ": 通用填充参数: " + Arrays.toString(args));
            return constructor.newInstance(args);
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 构造函数调用失败: " + t.getMessage());
            return null;
        }
    }
    
    /**
     * 尝试设置所有可能的字段名
     */
    private static void trySetAllFields(Object obj, String content, int atType, 
            long atUid, long atTinyId, String atNtUid) {
        // content 字段
        trySetField(obj, "content", content);
        trySetField(obj, "a", content);
        
        // atType 字段
        trySetIntField(obj, "atType", atType);
        trySetIntField(obj, "b", atType);
        
        // atUid 字段
        trySetLongField(obj, "atUid", atUid);
        trySetLongField(obj, "c", atUid);
        
        // atTinyId 字段
        trySetLongField(obj, "atTinyId", atTinyId);
        trySetLongField(obj, "d", atTinyId);
        
        // atNtUid 字段
        trySetField(obj, "atNtUid", atNtUid);
        trySetField(obj, "e", atNtUid);
    }
    
    private static void trySetField(Object obj, String fieldName, Object value) {
        try {
            XposedHelpers.setObjectField(obj, fieldName, value);
        } catch (Throwable ignored) {}
    }
    
    private static void trySetIntField(Object obj, String fieldName, int value) {
        try {
            XposedHelpers.setIntField(obj, fieldName, value);
        } catch (Throwable ignored) {}
    }
    
    private static void trySetLongField(Object obj, String fieldName, long value) {
        try {
            XposedHelpers.setLongField(obj, fieldName, value);
        } catch (Throwable ignored) {}
    }
    
    /**
     * 创建 msg.data.a 实例
     */
    private static Object createMsgDataInstance(ClassLoader classLoader) {
        try {
            Class<?> msgDataClass = XposedHelpers.findClass(
                "com.tencent.mobileqq.aio.msg.data.a", classLoader);
            
            Constructor<?>[] constructors = msgDataClass.getDeclaredConstructors();
            XposedBridge.log(TAG + ": msg.data.a 有 " + constructors.length + " 个构造函数");
            
            for (Constructor<?> constructor : constructors) {
                constructor.setAccessible(true);
                Class<?>[] paramTypes = constructor.getParameterTypes();
                XposedBridge.log(TAG + ":   构造函数: " + Arrays.toString(paramTypes));
                
                try {
                    Object[] params = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        Class<?> type = paramTypes[i];
                        if (type == int.class) params[i] = 0;
                        else if (type == boolean.class) params[i] = false;
                        else if (type == long.class) params[i] = 0L;
                        else params[i] = null;
                    }
                    
                    Object msgData = constructor.newInstance(params);
                    // XposedBridge.log(TAG + ": 创建msg.data.a成功 (" + paramTypes.length + "参数)");
                    return msgData;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": 构造函数失败: " + t.getMessage());
                }
            }
            
            XposedBridge.log(TAG + ": 无法创建msg.data.a对象");
            return null;
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 创建msg.data.a异常: " + t.getMessage());
            XposedBridge.log(t);
            return null;
        }
    }
    
    private static Object getAIOSendMsgVMDelegate(Context context) {
        if (sAIOSendMsgVMDelegate == null) {
            XposedBridge.log(TAG + ": AIOSendMsgVMDelegate实例为null，请确保已Hook");
        }
        return sAIOSendMsgVMDelegate;
    }
}
