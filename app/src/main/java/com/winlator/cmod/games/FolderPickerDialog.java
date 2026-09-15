package com.winlator.cmod.games;

import android.content.Context;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.Callback;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Modal directory picker that walks the host filesystem with
 * {@link java.io.File}. Returns the selected folder via a callback. Used
 * by the "Scan games" flow to pick the games root and to pre-fill the
 * letter mapping for the containers.
 */
public class FolderPickerDialog {
    private final Context context;
    private final Callback<File> onPicked;
    private File currentDir;
    private RecyclerView recyclerView;
    private TextView tvPath;
    private AlertDialog dialog;

    public FolderPickerDialog(@NonNull Context context, File initialDir, Callback<File> onPicked) {
        this.context = context;
        this.onPicked = onPicked;
        this.currentDir = (initialDir != null && initialDir.isDirectory())
                ? initialDir
                : Environment.getExternalStorageDirectory();
    }

    public void show() {
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_folder_picker, null);
        tvPath = root.findViewById(R.id.TVPath);
        recyclerView = root.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        root.findViewById(R.id.BTUp).setOnClickListener(v -> goUp());

        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.scan_games_pick_folder)
                .setView(root)
                .setPositiveButton(R.string.scan_games_select_folder, (d, w) -> {
                    if (onPicked != null) onPicked.call(currentDir);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();

        refresh();
    }

    private void goUp() {
        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            currentDir = parent;
            refresh();
        }
    }

    private void refresh() {
        tvPath.setText(currentDir.getAbsolutePath());
        File[] children = currentDir.listFiles(File::isDirectory);
        List<File> dirs;
        if (children == null) {
            dirs = new ArrayList<>();
        } else {
            dirs = new ArrayList<>(Arrays.asList(children));
            dirs.removeIf(f -> f.getName().startsWith("."));
            dirs.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        }
        recyclerView.setAdapter(new Adapter(dirs));
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<File> dirs;
        Adapter(List<File> dirs) { this.dirs = dirs; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.folder_picker_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            File f = dirs.get(position);
            h.title.setText(f.getName());
            h.icon.setImageResource(R.drawable.ui_ic_storage);
            h.itemView.setOnClickListener(v -> {
                if (f.canRead()) {
                    currentDir = f;
                    refresh();
                }
            });
        }

        @Override
        public int getItemCount() { return dirs.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final ImageView icon;
            VH(View v) {
                super(v);
                title = v.findViewById(R.id.TVTitle);
                icon = v.findViewById(R.id.IVIcon);
            }
        }
    }
}
