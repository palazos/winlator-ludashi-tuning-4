package com.winlator.cmod.games;

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
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * End-to-end UI flow for the "Scan games" feature:
 * folder picker -> containers picker -> background scan (folder walk +
 * heuristic resolution) -> results checklist -> batch shortcut creation
 * with per-container drive merge. The games folder is always mapped to
 * drive G: in every target container, overwriting any previous mapping
 * for that letter without asking for confirmation.
 */
public class ScanGamesController {
    private static final char GAMES_DRIVE_LETTER = 'G';
    private static final String PREF_LAST_FOLDER = "games_root_path";

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
        runScan(folder, selected, GAMES_DRIVE_LETTER);
    }

    /**
     * Non-cancelable spinner reusing {@link ContentDialog}'s rounded,
     * theme-aware chrome instead of the plain system {@code ProgressDialog}.
     * Pass {@code messageResId == 0} to show just the spinner under the title.
     */
    private ContentDialog showProgress(int messageResId) {
        ContentDialog dialog = new ContentDialog(context, R.layout.dialog_progress_indicator);
        dialog.setTitle(R.string.scan_games_progress_title);
        if (messageResId != 0) dialog.setMessage(messageResId);
        dialog.getContentView().findViewById(R.id.LLBottomBar).setVisibility(View.GONE);
        dialog.setCancelable(false);
        dialog.show();
        return dialog;
    }

    private void runScan(File folder, List<Container> targets, char letter) {
        ContentDialog progress = showProgress(R.string.scan_games_resolving);

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

        // All detected games start checked; the user unchecks the ones they
        // don't want instead of having to review low-confidence matches.
        for (GameCandidate c : candidates) {
            if (c == null) continue;
            c.selected = true;
        }

        ContentDialog dialog = new ContentDialog(context, R.layout.dialog_scan_results);
        dialog.getContentView().findViewById(R.id.FrameLayout).getLayoutParams().width =
                AppUtils.getPreferredDialogWidth(context);
        dialog.setTitle(R.string.scan_games_results_title);
        // The confirm action is a dedicated button pinned top-right inside
        // dialog_scan_results.xml (see that layout for why); the default
        // bottom bar is only used here for Cancel.
        dialog.getContentView().findViewById(R.id.BTConfirm).setVisibility(View.GONE);

        RecyclerView rv = dialog.findViewById(R.id.RecyclerView);
        rv.setLayoutManager(new LinearLayoutManager(context));
        rv.setAdapter(new ResultsAdapter(candidates));

        dialog.findViewById(R.id.BTApply).setOnClickListener(v -> {
            dialog.dismiss();
            applySelection(folder, targets, letter, candidates);
        });
        dialog.show();
    }

    private void applySelection(File folder, List<Container> targets, char letter,
                                List<GameCandidate> candidates) {
        ContentDialog progress = showProgress(0);

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

            String exeName = c.exe != null ? exeLabel(c, c.exe) : "?";
            int totalExes = 1 + c.alternateExes.size();
            String subtitle = exeName + "  ·  " + (int) (c.confidence * 100) + "%";
            if (totalExes > 1) subtitle += "  ·  " + totalExes + " found";
            h.subtitle.setText(subtitle);

            h.checkBox.setOnCheckedChangeListener(null);
            h.checkBox.setChecked(c.selected);
            h.checkBox.setOnCheckedChangeListener((b, isChecked) -> c.selected = isChecked);
            h.itemView.setOnClickListener(v -> {
                c.selected = !c.selected;
                h.checkBox.setChecked(c.selected);
            });

            // The heuristic only guesses the "best" exe; when a folder has
            // more than one candidate, let the user pick a different one.
            if (totalExes > 1) {
                h.chooseExe.setVisibility(View.VISIBLE);
                h.chooseExe.setOnClickListener(v -> chooseExecutable(h.itemView.getContext(), c, position));
            } else {
                h.chooseExe.setVisibility(View.GONE);
                h.chooseExe.setOnClickListener(null);
            }
        }

        @Override public int getItemCount() { return data.size(); }

        private void chooseExecutable(Context context, GameCandidate c, int position) {
            if (position == RecyclerView.NO_POSITION) return;
            List<File> options = new ArrayList<>();
            options.add(c.exe);
            options.addAll(c.alternateExes);

            String[] labels = new String[options.size()];
            for (int i = 0; i < options.size(); i++) labels[i] = exeLabel(c, options.get(i));

            ContentDialog.showSingleChoiceList(context, R.string.scan_games_choose_exe, labels, chosenIndex -> {
                File chosen = options.get(chosenIndex);
                if (!chosen.equals(c.exe)) {
                    c.alternateExes.remove(chosen);
                    c.alternateExes.add(c.exe);
                    c.exe = chosen;
                    notifyItemChanged(position);
                }
            });
        }

        /** Relative path from the game folder, so same-named exes in different subfolders stay distinguishable. */
        private static String exeLabel(GameCandidate c, File exe) {
            try {
                String folderPath = c.folder.getAbsolutePath();
                String exePath = exe.getAbsolutePath();
                if (exePath.startsWith(folderPath)) {
                    String rel = exePath.substring(folderPath.length());
                    if (rel.startsWith(File.separator)) rel = rel.substring(1);
                    return rel.replace('\\', '/');
                }
            } catch (Exception ignored) {}
            return exe.getName();
        }

        static class VH extends RecyclerView.ViewHolder {
            final CheckBox checkBox;
            final TextView title;
            final TextView subtitle;
            final View chooseExe;
            VH(View v) {
                super(v);
                checkBox = v.findViewById(R.id.CheckBox);
                title = v.findViewById(R.id.TVTitle);
                subtitle = v.findViewById(R.id.TVSubtitle);
                chooseExe = v.findViewById(R.id.IVChooseExe);
            }
        }
    }
}
