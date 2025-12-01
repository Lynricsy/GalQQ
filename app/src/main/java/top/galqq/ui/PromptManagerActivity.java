package top.galqq.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

import top.galqq.R;
import top.galqq.config.ConfigManager;
import top.galqq.utils.HostInfo;

public class PromptManagerActivity extends AppCompatTransferActivity {

    private RecyclerView recyclerView;
    private PromptAdapter adapter;
    private List<ConfigManager.PromptItem> promptList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 在宿主进程中动态设置主题
        if (HostInfo.isInHostProcess()) {
            setTheme(R.style.Theme_GalQQ_DayNight);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prompt_manager);
        
        ConfigManager.init(this);
        
        setTitle("提示词管理");
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        recyclerView = findViewById(R.id.prompt_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        loadPrompts();
        
        // 设置拖拽排序
        setupDragAndDrop();
        
        // 添加按钮
        findViewById(R.id.btn_add_prompt).setOnClickListener(v -> showAddPromptDialog());
    }
    
    private void setupDragAndDrop() {
        ItemTouchHelper.Callback callback = new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                // 允许上下拖拽
                int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
                return makeMovementFlags(dragFlags, 0);
            }
            
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPosition = viewHolder.getAdapterPosition();
                int toPosition = target.getAdapterPosition();
                
                // 交换列表中的位置（顺序决定优先级）
                Collections.swap(promptList, fromPosition, toPosition);
                adapter.notifyItemMoved(fromPosition, toPosition);
                
                return true;
            }
            
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // 不支持滑动删除
            }
            
            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                // 拖拽结束后保存新顺序
                ConfigManager.savePromptList(promptList);
            }
            
            @Override
            public boolean isLongPressDragEnabled() {
                return true; // 长按启用拖拽
            }
        };
        
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(recyclerView);
    }

    private void loadPrompts() {
        promptList = ConfigManager.getPromptList();
        adapter = new PromptAdapter();
        recyclerView.setAdapter(adapter);
    }

    private void showAddPromptDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_prompt, null);
        EditText nameEdit = dialogView.findViewById(R.id.edit_prompt_name);
        EditText contentEdit = dialogView.findViewById(R.id.edit_prompt_content);
        EditText whitelistEdit = dialogView.findViewById(R.id.edit_prompt_whitelist);
        EditText blacklistEdit = dialogView.findViewById(R.id.edit_prompt_blacklist);
        android.widget.Switch whitelistSwitch = dialogView.findViewById(R.id.switch_whitelist);
        android.widget.Switch blacklistSwitch = dialogView.findViewById(R.id.switch_blacklist);
        
        // 设置开关监听器，控制输入框显示
        whitelistSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            whitelistEdit.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        blacklistSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            blacklistEdit.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        
        new AlertDialog.Builder(this)
            .setTitle("添加提示词")
            .setView(dialogView)
            .setPositiveButton("添加", (dialog, which) -> {
                String name = nameEdit.getText().toString().trim();
                String content = contentEdit.getText().toString().trim();
                String whitelist = whitelistEdit.getText().toString().trim();
                String blacklist = blacklistEdit.getText().toString().trim();
                boolean whitelistEnabled = whitelistSwitch.isChecked();
                boolean blacklistEnabled = blacklistSwitch.isChecked();
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入提示词名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (content.isEmpty()) {
                    Toast.makeText(this, "请输入提示词内容", Toast.LENGTH_SHORT).show();
                    return;
                }
                promptList.add(new ConfigManager.PromptItem(name, content, whitelist, blacklist, true, whitelistEnabled, blacklistEnabled));
                ConfigManager.savePromptList(promptList);
                adapter.notifyItemInserted(promptList.size() - 1);
                Toast.makeText(this, "添加成功", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }


    private void showEditPromptDialog(int position) {
        ConfigManager.PromptItem item = promptList.get(position);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_prompt, null);
        EditText nameEdit = dialogView.findViewById(R.id.edit_prompt_name);
        EditText contentEdit = dialogView.findViewById(R.id.edit_prompt_content);
        EditText whitelistEdit = dialogView.findViewById(R.id.edit_prompt_whitelist);
        EditText blacklistEdit = dialogView.findViewById(R.id.edit_prompt_blacklist);
        android.widget.Switch whitelistSwitch = dialogView.findViewById(R.id.switch_whitelist);
        android.widget.Switch blacklistSwitch = dialogView.findViewById(R.id.switch_blacklist);
        
        nameEdit.setText(item.name);
        contentEdit.setText(item.content);
        whitelistEdit.setText(item.whitelist);
        blacklistEdit.setText(item.blacklist);
        
        // 设置开关状态和输入框可见性
        whitelistSwitch.setChecked(item.whitelistEnabled);
        blacklistSwitch.setChecked(item.blacklistEnabled);
        whitelistEdit.setVisibility(item.whitelistEnabled ? View.VISIBLE : View.GONE);
        blacklistEdit.setVisibility(item.blacklistEnabled ? View.VISIBLE : View.GONE);
        
        // 设置开关监听器
        whitelistSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            whitelistEdit.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        blacklistSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            blacklistEdit.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        
        new AlertDialog.Builder(this)
            .setTitle("编辑提示词")
            .setView(dialogView)
            .setPositiveButton("保存", (dialog, which) -> {
                String name = nameEdit.getText().toString().trim();
                String content = contentEdit.getText().toString().trim();
                String whitelist = whitelistEdit.getText().toString().trim();
                String blacklist = blacklistEdit.getText().toString().trim();
                boolean whitelistEnabled = whitelistSwitch.isChecked();
                boolean blacklistEnabled = blacklistSwitch.isChecked();
                if (name.isEmpty() || content.isEmpty()) {
                    Toast.makeText(this, "名称和内容不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                item.name = name;
                item.content = content;
                item.whitelist = whitelist;
                item.blacklist = blacklist;
                item.whitelistEnabled = whitelistEnabled;
                item.blacklistEnabled = blacklistEnabled;
                ConfigManager.savePromptList(promptList);
                adapter.notifyItemChanged(position);
                Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void deletePrompt(int position) {
        if (promptList.size() <= 1) {
            Toast.makeText(this, "至少保留一个提示词", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("删除提示词")
            .setMessage("确定要删除 \"" + promptList.get(position).name + "\" 吗？")
            .setPositiveButton("删除", (dialog, which) -> {
                promptList.remove(position);
                ConfigManager.savePromptList(promptList);
                adapter.notifyDataSetChanged();
                Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void togglePromptEnabled(int position) {
        ConfigManager.PromptItem item = promptList.get(position);
        item.enabled = !item.enabled;
        ConfigManager.savePromptList(promptList);
        adapter.notifyItemChanged(position);
        String status = item.enabled ? "已启用" : "已禁用";
        Toast.makeText(this, item.name + " " + status, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    class PromptAdapter extends RecyclerView.Adapter<PromptAdapter.ViewHolder> {
        
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_prompt, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            ConfigManager.PromptItem item = promptList.get(position);
            // 显示优先级序号和名称，禁用时在名称后显示"(已禁用)"
            String nameDisplay = (position + 1) + ". " + item.name;
            if (!item.enabled) {
                nameDisplay += " (已禁用)";
            }
            holder.nameText.setText(nameDisplay);
            holder.contentText.setText(item.content.length() > 60 
                ? item.content.substring(0, 60) + "..." 
                : item.content);
            
            // 不再使用选中指示器和状态图标
            holder.selectedIndicator.setVisibility(View.GONE);
            holder.statusIndicator.setVisibility(View.GONE);
            
            // 禁用时显示半透明效果
            holder.itemView.setAlpha(item.enabled ? 1.0f : 0.5f);
            
            // 显示黑白名单预览（只有启用了对应功能才显示）
            StringBuilder listPreview = new StringBuilder();
            if (item.whitelistEnabled && item.whitelist != null && !item.whitelist.isEmpty()) {
                if (listPreview.length() > 0) listPreview.append(" | ");
                listPreview.append("白名单: ").append(item.whitelist);
            }
            if (item.blacklistEnabled && item.blacklist != null && !item.blacklist.isEmpty()) {
                if (listPreview.length() > 0) listPreview.append(" | ");
                listPreview.append("黑名单: ").append(item.blacklist);
            }
            if (listPreview.length() > 0) {
                holder.listPreview.setText(listPreview.toString());
                holder.listPreview.setVisibility(View.VISIBLE);
            } else {
                holder.listPreview.setVisibility(View.GONE);
            }
            
            // 点击切换启用/禁用状态（使用 getAdapterPosition 获取当前实际位置）
            holder.itemView.setOnClickListener(v -> {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION) {
                    togglePromptEnabled(currentPosition);
                }
            });
            // 长按由 ItemTouchHelper 处理拖拽，不设置 OnLongClickListener
            holder.btnEdit.setOnClickListener(v -> {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION) {
                    showEditPromptDialog(currentPosition);
                }
            });
            holder.btnDelete.setOnClickListener(v -> {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION) {
                    deletePrompt(currentPosition);
                }
            });
        }

        @Override
        public int getItemCount() {
            return promptList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView nameText, contentText, listPreview, statusIndicator;
            View selectedIndicator;
            TextView btnEdit, btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                nameText = itemView.findViewById(R.id.prompt_name);
                contentText = itemView.findViewById(R.id.prompt_content);
                listPreview = itemView.findViewById(R.id.list_preview);
                statusIndicator = itemView.findViewById(R.id.status_indicator);
                selectedIndicator = itemView.findViewById(R.id.selected_indicator);
                btnEdit = itemView.findViewById(R.id.btn_edit);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }
        }
    }
}
