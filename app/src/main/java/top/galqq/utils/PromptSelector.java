package top.galqq.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import top.galqq.config.ConfigManager;
import top.galqq.config.ConfigManager.PromptItem;

/**
 * 提示词选择器
 * 根据发送者QQ号和优先级决策链选择合适的提示词
 */
public class PromptSelector {
    
    private static final String TAG = "GalQQ.PromptSelector";
    
    /**
     * 计算单个提示词对指定QQ的状态
     * @param prompt 提示词
     * @param senderQQ 发送者QQ号
     * @param aiEnabled AI是否启用
     * @return 提示词状态
     */
    public static PromptStatus calculateStatus(PromptItem prompt, String senderQQ, boolean aiEnabled) {
        // 如果提示词被禁用，直接返回 FORCE_OFF（黑白名单都不触发）
        if (!prompt.enabled) {
            return PromptStatus.FORCE_OFF;
        }
        // 白名单优先
        if (prompt.isInWhitelist(senderQQ)) {
            return PromptStatus.FORCE_ON;
        }
        // 黑名单次之
        if (prompt.isInBlacklist(senderQQ)) {
            return PromptStatus.FORCE_OFF;
        }
        // 默认状态取决于AI是否启用
        return aiEnabled ? PromptStatus.DEFAULT : PromptStatus.FORCE_OFF;
    }
    
    /**
     * 选择适用的提示词列表
     * @param allPrompts 所有提示词
     * @param senderQQ 发送者QQ号
     * @param aiEnabled AI是否启用
     * @return 空列表表示全部被屏蔽，单元素表示白名单命中，多元素表示默认选择
     */
    public static List<PromptItem> selectPrompts(List<PromptItem> allPrompts, String senderQQ, boolean aiEnabled) {
        if (allPrompts == null || allPrompts.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<PromptItem> forceOnPrompts = new ArrayList<>();
        List<PromptItem> defaultPrompts = new ArrayList<>();
        boolean hasForceOff = false;
        
        boolean verboseLog = ConfigManager.isVerboseLogEnabled();
        
        for (PromptItem prompt : allPrompts) {
            PromptStatus status = calculateStatus(prompt, senderQQ, aiEnabled);
            
            if (verboseLog) {
                XposedBridge.log(TAG + ": [" + prompt.name + "] status=" + status.name() + " for QQ: " + senderQQ);
            }
            
            if (status == PromptStatus.FORCE_ON) {
                forceOnPrompts.add(prompt);
            } else if (status == PromptStatus.DEFAULT) {
                defaultPrompts.add(prompt);
            } else {
                hasForceOff = true;
            }
        }
        
        // 优先级决策链
        if (!forceOnPrompts.isEmpty()) {
            // 返回最高优先级的白名单提示词（第一个）
            PromptItem selected = forceOnPrompts.get(0);
            XposedBridge.log(TAG + ": 白名单命中: " + selected.name + " for QQ: " + senderQQ);
            return Collections.singletonList(selected);
        }
        
        if (!defaultPrompts.isEmpty()) {
            XposedBridge.log(TAG + ": 使用默认提示词列表 for QQ: " + senderQQ);
            return defaultPrompts;
        }
        
        // 全部被屏蔽
        XposedBridge.log(TAG + ": 所有提示词被黑名单屏蔽 for QQ: " + senderQQ);
        return Collections.emptyList();
    }
    
    /**
     * 获取选中的单个提示词（用于自动选择场景）
     * 优先级规则：
     * 1. 白名单命中的提示词（按列表顺序，第一个优先）
     * 2. 默认状态的提示词（按列表顺序，第一个优先）
     * 
     * @param allPrompts 所有提示词
     * @param senderQQ 发送者QQ号
     * @param aiEnabled AI是否启用
     * @return 选中的提示词，如果全部被屏蔽则返回null
     */
    public static PromptItem getSelectedPrompt(List<PromptItem> allPrompts, String senderQQ, boolean aiEnabled) {
        List<PromptItem> selected = selectPrompts(allPrompts, senderQQ, aiEnabled);
        if (selected.isEmpty()) {
            return null;
        }
        
        // 返回第一个可用的提示词（顺序决定优先级）
        return selected.get(0);
    }
}
