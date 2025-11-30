package top.galqq.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;


import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import top.galqq.R;
import top.galqq.config.ConfigManager;
import top.galqq.utils.HostInfo;
import java.util.List;

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
        
        // 添加按钮
        findViewById(R.id.btn_add_prompt).setOnClickListener(v -> showAddPromptDialog());
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
        
        new AlertDialog.Builder(this)
            .setTitle("添加提示词")
            .setView(dialogView)
            .setPositiveButton("添加", (dialog, which) -> {
                String name = nameEdit.getText().toString().trim();
                String content = contentEdit.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入提示词名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (content.isEmpty()) {
                    Toast.makeText(this, "请输入提示词内容", Toast.LENGTH_SHORT).show();
                    return;
                }
                promptList.add(new ConfigManager.PromptItem(name, content));
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
        nameEdit.setText(item.name);
        contentEdit.setText(item.content);
        
        new AlertDialog.Builder(this)
            .setTitle("编辑提示词")
            .setView(dialogView)
            .setPositiveButton("保存", (dialog, which) -> {
                String name = nameEdit.getText().toString().trim();
                String content = contentEdit.getText().toString().trim();
                if (name.isEmpty() || content.isEmpty()) {
                    Toast.makeText(this, "名称和内容不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                item.name = name;
                item.content = content;
                ConfigManager.savePromptList(promptList);
                // 如果编辑的是当前使用的提示词，同步更新
                if (position == ConfigManager.getCurrentPromptIndex()) {
                    ConfigManager.setSysPrompt(content);
                }
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
                // 调整当前索引
                int currentIndex = ConfigManager.getCurrentPromptIndex();
                if (currentIndex >= promptList.size()) {
                    ConfigManager.setCurrentPromptIndex(promptList.size() - 1);
                } else if (currentIndex == position) {
                    ConfigManager.setCurrentPromptIndex(0);
                }
                adapter.notifyDataSetChanged();
                Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void selectPrompt(int position) {
        ConfigManager.setCurrentPromptIndex(position);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已切换到: " + promptList.get(position).name, Toast.LENGTH_SHORT).show();
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
            holder.nameText.setText(item.name);
            holder.contentText.setText(item.content.length() > 60 
                ? item.content.substring(0, 60) + "..." 
                : item.content);
            
            // 显示选中指示器
            int currentIndex = ConfigManager.getCurrentPromptIndex();
            holder.selectedIndicator.setVisibility(position == currentIndex 
                ? View.VISIBLE : View.GONE);
            
            holder.itemView.setOnClickListener(v -> selectPrompt(position));
            holder.btnEdit.setOnClickListener(v -> showEditPromptDialog(position));
            holder.btnDelete.setOnClickListener(v -> deletePrompt(position));
        }

        @Override
        public int getItemCount() {
            return promptList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView nameText, contentText;
            View selectedIndicator;
            TextView btnEdit, btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                nameText = itemView.findViewById(R.id.prompt_name);
                contentText = itemView.findViewById(R.id.prompt_content);
                selectedIndicator = itemView.findViewById(R.id.selected_indicator);
                btnEdit = itemView.findViewById(R.id.btn_edit);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }
        }
    }
}
