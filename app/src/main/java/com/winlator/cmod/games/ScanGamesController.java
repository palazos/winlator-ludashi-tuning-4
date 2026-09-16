package com.winlator.cmod.games;

import android.app.ProgressDialog;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * End-to-end UI flow for the "Scan games" feature:
 * folder picker -> containers picker -> drive letter -> background scan
 * (folder walk + heuristic resolution) -> results checklist -> batch
 * shortcut creation with per-container drive merge.
 */
public class ScanGamesController {
    private static final String PREF_LAST_FOLDER = "games_root_path";
    private static final String PREF_LAST_LETTER = "games_drive_letter";

    private final Context context;
    private final ContainerManager manager;
    private final Runnable onShortcutsCreated;

    public ScanGamesController(@NonNull Context context, @NonNull ContainerManager manager,
                               Runnable onShortcutsCreated) {
        this.context = context;
        this.manager = manager;
        this.onShortcutsCreated = onShortcutsCreated;
    }

    public void start() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        File initial = null;
        String last = prefs.getString(PREF_LAST_FOLDER, null);
        if (last != null) initial = new File(last);

        new FolderPickerDialog(context, initial, this::onFolderPicked).show();
    }

    /**
     * Skips the folder picker and starts straight at {@code folder}. Used
     * by callers that already obtained a real path via SAF.
     */
    public void startWithFolder(File folder) {
        if (folder == null || !folder.isDirectory() || !folder.canRead()) {
            Toast.makeText(context,
                    context.getString(R.string.scan_games_no_results),
                    Toast.LENGTH_LONG).show();
            return;
        }
        onFolderPicked(folder);
    }

    private void onFolderPicked(File folder) {
        if (folder == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(PREF_LAST_FOLDER, folder.getAbsolutePath()).apply();

        List<Container> all = new ArrayList<>(manager.getContainers());
        if (all.isEmpty()) {
            Toast.makeText(context, R.string.no_items_to_display, Toast.LENGTH_SHORT).show();
            return;
        }
        new ContainerMultiSelectDialog(context, all, selected -> onContainersPicked(folder, selected)).show();
    }

    private void onContainersPicked(File folder, List<Container> selected) {
        if (selected == null || selected.isEmpty()) return;
        new DriveLetterDialog(context, selected, folder.getAbsolutePath(),
                sel -> {
                    if (sel.conflict) {
                        new AlertDialog.Builder(context)
                                .setTitle(R.string.scan_games_overwrite_letter_title)
                                .setMessage(context.getString(R.string.scan_games_overwrite_letter_msg, sel.letter + ":"))
                                .setPositiveButton(android.R.string.ok,
                                        (d, w) -> runScan(folder, selected, sel.letter))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    } else {
                        runScan(folder, selected, sel.letter);
                    }
                }).show();
    }

    private void runScan(File folder, List<Container> targets, char letter) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(PREF_LAST_LETTER, String.valueOf(letter)).apply();

        ProgressDialog progress = new ProgressDialog(context);
        progress.setTitle(R.string.scan_games_progress_title);
        progress.setMessage(context.getString(R.string.scan_games_resolving));
        progress.setCancelable(false);
        progress.show();

        Handler ui = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                GameScanner scanner = new GameScanner();
                List<RawCandidate> raws = scanner.scan(folder, new GameScanner.ProgressListener() {
                    @Override public void onFolderVisited(File f) {}
                    @Override public void onCandidateFound(RawCandidate c) {}
                    @Override public boolean isCancelled() { return false; }
                });

                if (raws.isEmpty()) {
                    ui.post(() -> {
                        progress.dismiss();
                        Toast.makeText(context, R.string.scan_games_no_results, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                List<GameCandidate> candidates = new ArrayList<>(raws.size());
                for (RawCandidate raw : raws) {
                    GameCandidate c = HeuristicResolver.resolve(raw);
                    if (c != null) candidates.add(c);
                }

                ui.post(() -> {
                    progress.dismiss();
                    showResults(folder, targets, letter, candidates);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    progress.dismiss();
                    Toast.makeText(context, "Scan failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showResults(File folder, List<Container> targets, char letter,
                             List<GameCandidate> candidates) {
        if (candidates.isEmpty()) {
            Toast.makeText(context, R.string.scan_games_no_results, Toast.LENGTH_LONG).show();
            return;
        }

        // Pre-check candidates the heuristic is fairly confident about.
        for (GameCandidate c : candidates) {
            if (c == null) continue;
            c.selected = c.confidence >= 0.6f;
        }

        View root = LayoutInflater.from(context).inflate(R.layout.dialog_scan_results, null);
        RecyclerView rv = root.findViewById(R.id.RecyclerView);
        rv.setLayoutManager(new LinearLayoutManager(context));
        ResultsAdapter adapter = new ResultsAdapter(candidates);
        rv.setAdapter(adapter);

        TextView footer = root.findViewById(R.id.TVFooter);
        footer.setText(R.string.scan_games_disclosure);

        new AlertDialog.Builder(context)
                .setTitle(R.string.scan_games_results_title)
                .setView(root)
                .setPositiveButton(R.string.scan_games_create,
                        (d, w) -> applySelection(folder, targets, letter, candidates))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applySelection(File folder, List<Container> targets, char letter,
                                List<GameCandidate> candidates) {
        ProgressDialog progress = new ProgressDialog(context);
        progress.setTitle(R.string.scan_games_progress_title);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        Handler ui = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            int created = 0;
            int skipped = 0;
            Set<Container> touchedContainers = new HashSet<>();

            // Make sure each target container has the chosen letter pointing
            // at the games folder. Use a non-destructive merge.
            String gamesPath = folder.getAbsolutePath();
            for (Container c : targets) {
                String drives = c.getDrives();
                String existing = DrivesEditor.pathFor(drives, letter);
                if (existing == null || !existing.equals(gamesPath)) {
                    c.setDrives(DrivesEditor.upsert(drives, letter, gamesPath));
                    c.saveData();
                    touchedContainers.add(c);
                }
            }

            for (GameCandidate cand : candidates) {
                if (cand == null || !cand.selected || cand.exe == null) continue;
                for (Container c : targets) {
                    ShortcutBuilder.Result r = ShortcutBuilder.createForGame(context, c, cand, false);
                    if (r.created) created++;
                    else if (r.skipped) skipped++;
                }
            }

            final int createdF = created;
            final int containerCount = targets.size();
            ui.post(() -> {
                progress.dismiss();
                String msg = context.getString(R.string.scan_games_summary, createdF, containerCount);
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                if (onShortcutsCreated != null) onShortcutsCreated.run();
            });
        });
    }

    private static class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.VH> {
        private final List<GameCandidate> data;

        ResultsAdapter(List<GameCandidate> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.scan_result_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            GameCandidate c = data.get(position);
            h.title.setText(c.displayName);
            String exeName = c.exe != null ? c.exe.getName() : "?";
            h.subtitle.setText(exeName + "  ·  " + (int) (c.confidence * 100) + "%");
            h.checkBox.setOnCheckedChangeListener(null);
            h.checkBox.setChecked(c.selected);
            h.checkBox.setOnCheckedChangeListener((b, isChecked) -> c.selected = isChecked);
            h.itemView.setOnClickListener(v -> {
                c.selected = !c.selected;
                h.checkBox.setChecked(c.selected);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final CheckBox checkBox;
            final TextView title;
            final TextView subtitle;
            VH(View v) {
                super(v);
                checkBox = v.findViewById(R.id.CheckBox);
                title = v.findViewById(R.id.TVTitle);
                subtitle = v.findViewById(R.id.TVSubtitle);
            }
        }
    }
}
