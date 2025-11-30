package top.galqq.ui;

import android.content.Intent;
import android.os.Bundle;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;
import top.galqq.R;
import top.galqq.config.ConfigManager;

public class GalSettingsFragment extends PreferenceFragmentCompat {

    /**
     * 更新Provider ListPreference的summary显示
     * @param pref ListPreference
     * @param provider 服务商标识
     */
    private void updateProviderSummary(androidx.preference.ListPreference pref, String provider) {
        String displayName = ConfigManager.getProviderDisplayName(provider);
        String apiUrl = ConfigManager.getDefaultApiUrl(provider);
        if (apiUrl.isEmpty()) {
            apiUrl = ConfigManager.getApiUrl();
        }
        pref.setSummary(displayName + "\n" + apiUrl);
    }

    /**
     * 更新提示词管理的summary显示
     */
    private void updatePromptManagerSummary(Preference pref) {
        java.util.List<ConfigManager.PromptItem> list = ConfigManager.getPromptList();
        int index = ConfigManager.getCurrentPromptIndex();
        if (index >= 0 && index < list.size()) {
            pref.setSummary("当前: " + list.get(index).name + " (共" + list.size() + "个)");
        } else {
            pref.setSummary("管理多个提示词，点击切换不同风格");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从提示词管理返回时刷新显示
        Preference promptManagerPref = findPreference("gal_prompt_manager");
        if (promptManagerPref != null) {
            updatePromptManagerSummary(promptManagerPref);
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Don't use SharedPreferences at all, we'll manually handle everything
        setPreferencesFromResource(R.xml.preferences_gal, rootKey);
        
        // Initialize MMKV
        ConfigManager.init(requireContext());
        
        // Bind preferences to MMKV
        bindPreferences();
    }

    private void bindPreferences() {
        // Module Enable Switch - use base Preference class to avoid ClassCastException
        Preference enableSwitch = findPreference(ConfigManager.KEY_ENABLED);
        if (enableSwitch != null) {
            // For SwitchPreference, we need to handle it differently
            if (enableSwitch instanceof androidx.preference.TwoStatePreference) {
                ((androidx.preference.TwoStatePreference) enableSwitch).setChecked(ConfigManager.isModuleEnabled());
            }
            enableSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setModuleEnabled((Boolean) newValue);
                return true;
            });
        }

        // AI Enable Switch
        Preference aiEnableSwitch = findPreference(ConfigManager.KEY_AI_ENABLED);
        if (aiEnableSwitch != null) {
            if (aiEnableSwitch instanceof androidx.preference.TwoStatePreference) {
                ((androidx.preference.TwoStatePreference) aiEnableSwitch).setChecked(ConfigManager.isAiEnabled());
            }
            aiEnableSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setAiEnabled((Boolean) newValue);
                return true;
            });
        }

        // Prompt Manager (提示词管理)
        Preference promptManagerPref = findPreference("gal_prompt_manager");
        if (promptManagerPref != null) {
            // 更新summary显示当前使用的提示词名称
            updatePromptManagerSummary(promptManagerPref);
            promptManagerPref.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), PromptManagerActivity.class);
                startActivity(intent);
                return true;
            });
        }

        // API URL
        EditTextPreference apiUrlPref = findPreference(ConfigManager.KEY_API_URL);
        if (apiUrlPref != null) {
            apiUrlPref.setText(ConfigManager.getApiUrl());
            apiUrlPref.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setApiUrl((String) newValue);
                apiUrlPref.setText((String) newValue);
                return true;
            });
        }

        // API Key
        EditTextPreference apiKeyPref = findPreference(ConfigManager.KEY_API_KEY);
        if (apiKeyPref != null) {
            apiKeyPref.setText(ConfigManager.getApiKey());
            apiKeyPref.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setApiKey((String) newValue);
                apiKeyPref.setText((String) newValue);
                return true;
            });
        }

        // Dictionary Path
        EditTextPreference dictPathPref = findPreference(ConfigManager.KEY_DICT_PATH);
        if (dictPathPref != null) {
            dictPathPref.setText(ConfigManager.getDictPath());
            dictPathPref.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setDictPath((String) newValue);
                dictPathPref.setText((String) newValue);
                return true;
            });
        }
        
        // AI Model
        EditTextPreference aiModelPref = findPreference(ConfigManager.KEY_AI_MODEL);
        if (aiModelPref != null) {
            aiModelPref.setText(ConfigManager.getAiModel());
            aiModelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setAiModel((String) newValue);
                aiModelPref.setText((String) newValue);
                return true;
            });
        }
        
        // AI Provider
        androidx.preference.ListPreference aiProviderPref = findPreference(ConfigManager.KEY_AI_PROVIDER);
        EditTextPreference apiUrlPrefForProvider = findPreference(ConfigManager.KEY_API_URL);
        if (aiProviderPref != null) {
            String currentProvider = ConfigManager.getAiProvider();
            aiProviderPref.setValue(currentProvider);
            // 初始化summary显示
            updateProviderSummary(aiProviderPref, currentProvider);
            
            aiProviderPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String provider = (String) newValue;
                ConfigManager.setAiProvider(provider);
                
                // 自动填充API URL
                String defaultUrl = ConfigManager.getDefaultApiUrl(provider);
                if (!defaultUrl.isEmpty()) {
                    ConfigManager.setApiUrl(defaultUrl);
                    // 更新API URL EditTextPreference的显示
                    if (apiUrlPrefForProvider != null) {
                        apiUrlPrefForProvider.setText(defaultUrl);
                    }
                }
                
                // 更新Provider的summary显示
                updateProviderSummary(aiProviderPref, provider);
                return true;
            });
        }
        
        // AI Temperature
        EditTextPreference aiTempPref = findPreference(ConfigManager.KEY_AI_TEMPERATURE);
        if (aiTempPref != null) {
            aiTempPref.setText(String.valueOf(ConfigManager.getAiTemperature()));
            aiTempPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    float temp = Float.parseFloat((String) newValue);
                    if (temp >= 0 && temp <= 2.0) {
                        ConfigManager.setAiTemperature(temp);
                        aiTempPref.setText((String) newValue);
                        return true;
                    }
                } catch (Exception e) {}
                return false;
            });
        }
        
        // AI Max Tokens
        EditTextPreference aiMaxTokensPref = findPreference(ConfigManager.KEY_AI_MAX_TOKENS);
        if (aiMaxTokensPref != null) {
            aiMaxTokensPref.setText(String.valueOf(ConfigManager.getAiMaxTokens()));
            aiMaxTokensPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    int tokens = Integer.parseInt((String) newValue);
                    if (tokens > 0) {
                        ConfigManager.setAiMaxTokens(tokens);
                        aiMaxTokensPref.setText((String) newValue);
                        return true;
                    }
                } catch (Exception e) {}
                return false;
            });
        }
        
        // AI QPS
        EditTextPreference aiQpsPref = findPreference(ConfigManager.KEY_AI_QPS);
        if (aiQpsPref != null) {
            aiQpsPref.setText(String.valueOf(ConfigManager.getAiQps()));
            aiQpsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    float qps = Float.parseFloat((String) newValue);
                    if (qps > 0.1) {
                        ConfigManager.setAiQps(qps);
                        aiQpsPref.setText((String) newValue);
                        return true;
                    }
                } catch (Exception e) {}
                return false;
            });
        }
        
        // Context Enabled (启用对话上下文)
        Preference contextEnabledSwitch = findPreference(ConfigManager.KEY_CONTEXT_ENABLED);
        if (contextEnabledSwitch != null) {
            if (contextEnabledSwitch instanceof androidx.preference.TwoStatePreference) {
                ((androidx.preference.TwoStatePreference) contextEnabledSwitch).setChecked(ConfigManager.isContextEnabled());
            }
            contextEnabledSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setContextEnabled((Boolean) newValue);
                return true;
            });
        }
        
        // Context Message Count (上下文消息数)
        EditTextPreference contextCountPref = findPreference(ConfigManager.KEY_CONTEXT_MESSAGE_COUNT);
        if (contextCountPref != null) {
            contextCountPref.setText(String.valueOf(ConfigManager.getContextMessageCount()));
            contextCountPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    int count = Integer.parseInt((String) newValue);
                    if (count >= 1 && count <= 30) {
                        ConfigManager.setContextMessageCount(count);
                        contextCountPref.setText((String) newValue);
                        return true;
                    }
                } catch (Exception e) {}
                return false;
            });
        }
        
        // History Threshold (历史消息阈值)
        EditTextPreference historyThresholdPref = findPreference(ConfigManager.KEY_HISTORY_THRESHOLD);
        if (historyThresholdPref != null) {
            historyThresholdPref.setText(String.valueOf(ConfigManager.getHistoryThreshold()));
            historyThresholdPref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    int seconds = Integer.parseInt((String) newValue);
                    if (seconds > 0) {
                        ConfigManager.setHistoryThreshold(seconds);
                        historyThresholdPref.setText((String) newValue);
                        return true;
                    }
                } catch (Exception e) {}
                return false;
            });
        }
        
        // Test API Button
        Preference testApiPref = findPreference("gal_test_api");
        if (testApiPref != null) {
            testApiPref.setOnPreferenceClickListener(preference -> {
                android.widget.Toast.makeText(requireContext(), "正在测试API连接...", android.widget.Toast.LENGTH_SHORT).show();
                top.galqq.utils.HttpAiClient.testApiConnection(requireContext(), new top.galqq.utils.HttpAiClient.AiCallback() {
                    @Override
                    public void onSuccess(java.util.List<String> options) {
                        android.app.Activity activity = getActivity();
                        if (activity != null && isAdded()) {
                            activity.runOnUiThread(() -> {
                                android.widget.Toast.makeText(activity, "✅ API连接成功！", android.widget.Toast.LENGTH_LONG).show();
                            });
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        android.app.Activity activity = getActivity();
                        if (activity != null && isAdded()) {
                            activity.runOnUiThread(() -> {
                                android.widget.Toast.makeText(activity, "❌ API连接失败: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                            });
                        }
                    }
                });
                return true;
            });
        }
        
        // Auto Show Options
        Preference autoShowOptionsSwitch = findPreference(ConfigManager.KEY_AUTO_SHOW_OPTIONS);
        if (autoShowOptionsSwitch != null) {
            if (autoShowOptionsSwitch instanceof androidx.preference.TwoStatePreference) {
                ((androidx.preference.TwoStatePreference) autoShowOptionsSwitch).setChecked(ConfigManager.isAutoShowOptionsEnabled());
            }
            autoShowOptionsSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setAutoShowOptionsEnabled((Boolean) newValue);
                return true;
            });
        }
        
        // Affinity Display (好感度显示)
        Preference affinitySwitch = findPreference(ConfigManager.KEY_AFFINITY_ENABLED);
        if (affinitySwitch != null) {
            if (affinitySwitch instanceof androidx.preference.TwoStatePreference) {
                ((androidx.preference.TwoStatePreference) affinitySwitch).setChecked(ConfigManager.isAffinityEnabled());
            }
            affinitySwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setAffinityEnabled((Boolean) newValue);
                return true;
            });
        }
        
        // Affinity Model (好感度计算模型)
        androidx.preference.ListPreference affinityModelPref = findPreference(ConfigManager.KEY_AFFINITY_MODEL);
        if (affinityModelPref != null) {
            affinityModelPref.setValue(String.valueOf(ConfigManager.getAffinityModel()));
            affinityModelPref.setSummary(ConfigManager.getAffinityModelName(ConfigManager.getAffinityModel()));
            affinityModelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int model = Integer.parseInt((String) newValue);
                ConfigManager.setAffinityModel(model);
                affinityModelPref.setSummary(ConfigManager.getAffinityModelName(model));
                
                // 【关键】清空好感度显示缓存，以便使用新模型重新计算
                try {
                    top.galqq.hook.MessageInterceptor.clearAffinityDisplayCache();
                    android.widget.Toast.makeText(requireContext(), "计算模型已切换，返回聊天界面后生效", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    // 忽略错误（可能在非 Xposed 环境下运行）
                }
                return true;
            });
        }
        
        // Test Affinity Data (测试好感度数据获取)
        Preference testAffinityPref = findPreference("gal_test_affinity");
        if (testAffinityPref != null) {
            testAffinityPref.setOnPreferenceClickListener(preference -> {
                android.widget.Toast.makeText(requireContext(), "正在测试好感度数据获取...\n调试数据将保存到 Download/GalQQ_Debug/", android.widget.Toast.LENGTH_LONG).show();
                
                // 创建 CloseRankClient 并测试（启用调试模式）
                top.galqq.utils.CloseRankClient client = new top.galqq.utils.CloseRankClient();
                client.setDebugMode(true); // 启用调试模式，保存请求和响应到下载目录
                
                // 测试获取"谁在意我"数据
                client.fetchWhoCaresMe(requireContext(), new top.galqq.utils.CloseRankClient.RankCallback() {
                    @Override
                    public void onSuccess(java.util.Map<String, Integer> uinToScore) {
                        android.app.Activity activity = getActivity();
                        if (activity != null && isAdded()) {
                            activity.runOnUiThread(() -> {
                                StringBuilder sb = new StringBuilder();
                                sb.append("✅ 获取成功！\n");
                                sb.append("谁在意我: ").append(uinToScore.size()).append(" 条数据\n\n");
                                
                                // 显示前5条数据
                                int count = 0;
                                for (java.util.Map.Entry<String, Integer> entry : uinToScore.entrySet()) {
                                    if (count >= 5) {
                                        sb.append("...(共").append(uinToScore.size()).append("条)");
                                        break;
                                    }
                                    sb.append("QQ: ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append("\n");
                                    count++;
                                }
                                
                                sb.append("\n\n📁 调试数据已保存到:\nDownload/GalQQ_Debug/");
                                
                                new android.app.AlertDialog.Builder(activity)
                                    .setTitle("好感度数据测试结果")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("确定", null)
                                    .show();
                            });
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        android.app.Activity activity = getActivity();
                        if (activity != null && isAdded()) {
                            activity.runOnUiThread(() -> {
                                new android.app.AlertDialog.Builder(activity)
                                    .setTitle("好感度数据测试失败")
                                    .setMessage("❌ 获取失败: " + e.getMessage() + "\n\n请检查：\n1. 是否已登录QQ\n2. 是否有网络连接\n3. Cookie是否有效\n\n📁 调试数据已保存到:\nDownload/GalQQ_Debug/")
                                    .setPositiveButton("确定", null)
                                    .show();
                            });
                        }
                    }
                });
                return true;
            });
        }

        // Verbose Log
        SwitchPreference verboseLogPref = findPreference(ConfigManager.KEY_VERBOSE_LOG);
        if (verboseLogPref != null) {
            verboseLogPref.setChecked(ConfigManager.isVerboseLogEnabled());
            verboseLogPref.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setVerboseLogEnabled((Boolean) newValue);
                return true;
            });
        }
        
        // Filter Mode
        androidx.preference.ListPreference filterModePref = findPreference(ConfigManager.KEY_FILTER_MODE);
        if (filterModePref != null) {
            filterModePref.setValue(ConfigManager.getFilterMode());
            filterModePref.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setFilterMode((String) newValue);
                return true;
            });
        }
        
        // Blacklist
        EditTextPreference blacklistPref = findPreference(ConfigManager.KEY_BLACKLIST);
        if (blacklistPref != null) {
            blacklistPref.setText(ConfigManager.getBlacklist());
            blacklistPref.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setBlacklist((String) newValue);
                return true;
            });
        }
        
        // Whitelist
        EditTextPreference whitelistPref = findPreference(ConfigManager.KEY_WHITELIST);
        if (whitelistPref != null) {
            whitelistPref.setText(ConfigManager.getWhitelist());
            whitelistPref.setOnPreferenceChangeListener((preference, newValue) -> {
                ConfigManager.setWhitelist((String) newValue);
                return true;
            });
        }
        
        // AI Log Viewer
        Preference aiLogPref = findPreference("gal_ai_log");
        if (aiLogPref != null) {
            aiLogPref.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), AiLogViewerActivity.class);
                startActivity(intent);
                return true;
            });
        }
        
        // AI Monitor
        Preference aiMonitorPref = findPreference("gal_ai_monitor");
        if (aiMonitorPref != null) {
            aiMonitorPref.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), AiMonitorActivity.class);
                startActivity(intent);
                return true;
            });
        }
        
        // Open Source Address
        Preference sourcePref = findPreference("gal_source");
        if (sourcePref != null) {
            sourcePref.setOnPreferenceClickListener(preference -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/yiyihuohuo/GalQQ"));
                    startActivity(intent);
                } catch (Exception e) {
                    android.widget.Toast.makeText(requireContext(), "无法打开浏览器", android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // Join Group - 实现加入QQ群1026163188
        Preference joinGroupPref = findPreference("gal_join_group");
        if (joinGroupPref != null) {
            joinGroupPref.setOnPreferenceClickListener(preference -> {
                try {
                    // QQ群号
                    String groupNumber = "1026163188";
                    
                    // 构建QQ群跳转链接（使用mqqapi协议）
                    // 格式：mqqapi://card/show_pslcard?src_type=internal&version=1&uin=群号&card_type=group&source=qrcode
                    String url = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" + 
                                groupNumber + "&card_type=group&source=qrcode";
                    
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    intent.setData(android.net.Uri.parse(url));
                    intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    
                    requireContext().startActivity(intent);
                    android.widget.Toast.makeText(requireContext(), "正在打开QQ群...", android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                } catch (Exception e) {
                    android.widget.Toast.makeText(requireContext(), "打开QQ群失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                    return false;
                }
            });
        }
    }
}
