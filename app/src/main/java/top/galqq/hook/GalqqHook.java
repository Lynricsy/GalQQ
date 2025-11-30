package top.galqq.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import top.galqq.lifecycle.Parasitics;
import top.galqq.utils.HostInfo;
import top.galqq.utils.MessageSendTracker;

import de.robv.android.xposed.IXposedHookZygoteInit;

public class GalqqHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    
    private static final String TAG = "GalQQ";
    
    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        Parasitics.setModulePath(startupParam.modulePath);
    }
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.tencent.mobileqq".equals(lpparam.packageName)) {
            return;
        }
        
        XposedBridge.log(TAG + ": Hooking QQ " + lpparam.packageName);
        
        try {
            // 初始化 CookieHookManager（需要在其他Hook之前）
            XposedBridge.log(TAG + ": 正在初始化CookieHookManager...");
            try {
                CookieHookManager.initHooks(lpparam);
                XposedBridge.log(TAG + ": CookieHookManager初始化完成");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": CookieHookManager初始化失败: " + t.getMessage());
                XposedBridge.log(t);
            }
            
            // 初始化 Hooks
            MessageInterceptor.init(lpparam.classLoader);
            SettingsInterceptor.init(lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Application app = (Application) param.args[0];
                    HostInfo.init(app);
                    Parasitics.initForStubActivity(app);
                    
                    // 启动消息发送追踪
                    XposedBridge.log(TAG + ": 正在启动消息追踪...");
                    try {
                        MessageSendTracker.startTracking(app);
                        XposedBridge.log(TAG + ": 消息追踪启动完成");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": 消息追踪启动失败: " + t.getMessage());
                        XposedBridge.log(t);
                    }
                }
            });

            XposedBridge.log(TAG + ": Hooks initialized successfully");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to initialize hooks: " + t.getMessage());
            XposedBridge.log(t);
        }
    }
}
