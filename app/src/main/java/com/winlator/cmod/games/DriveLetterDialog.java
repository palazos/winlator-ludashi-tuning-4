package com.winlator.cmod.games;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.Callback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Asks the user which drive letter should be used to map the games folder
 * across the chosen containers. Letters already mapped to the same path
 * in any container are still offered (we'd just upsert with the same
 * value); letters mapped to a *different* path are flagged as "in use"
 * to keep the choice visible.
 */
public class DriveLetterDialog {
    private final Context context;
    private final List<Container> containers;
    private final String gamesPath;
    private final Callback<Selection> onPicked;

    public static class Selection {
        public final char letter;
        /** True if the letter is already used somewhere with a different path. */
        public final boolean conflict;
        public Selection(char letter, boolean conflict) {
            this.letter = letter;
            this.conflict = conflict;
        }
    }

    public DriveLetterDialog(@NonNull Context context, @NonNull List<Container> containers,
                             @NonNull String gamesPath, Callback<Selection> onPicked) {
        this.context = context;
        this.containers = containers;
        this.gamesPath = gamesPath;
        this.onPicked = onPicked;
    }

    public void show() {
        // Collect letters with conflicts (mapped to a *different* path in any container).
        Set<Character> conflictLetters = new HashSet<>();
        // Reserved letters that we don't expose at all.
        Set<Character> reserved = new HashSet<>();
        reserved.add('C');
        reserved.add('Z');
        for (Container c : containers) {
            for (String[] drive : Container.drivesIterator(c.getDrives())) {
                if (drive[0].isEmpty()) continue;
                char letter = Character.toUpperCase(drive[0].charAt(0));
                if (!drive[1].equals(gamesPath)) conflictLetters.add(letter);
            }
        }

        List<Character> options = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            if (reserved.contains(c)) continue;
            options.add(c);
            labels.add(c + ":" + (conflictLetters.contains(c) ? " (in use)" : ""));
        }

        // Pre-select G if free, else first non-conflict, else first option.
        int defaultIdx = -1;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i) == 'G' && !conflictLetters.contains('G')) { defaultIdx = i; break; }
        }
        if (defaultIdx == -1) {
            for (int i = 0; i < options.size(); i++) {
                if (!conflictLetters.contains(options.get(i))) { defaultIdx = i; break; }
            }
        }
        if (defaultIdx == -1 && !options.isEmpty()) defaultIdx = 0;

        if (options.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.scan_games_drive_letter)
                    .setMessage(R.string.scan_games_no_free_letter)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final int[] selectedIdx = { defaultIdx };
        new AlertDialog.Builder(context)
                .setTitle(R.string.scan_games_drive_letter)
                .setSingleChoiceItems(labels.toArray(new String[0]), defaultIdx,
                        (d, which) -> selectedIdx[0] = which)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    char letter = options.get(selectedIdx[0]);
                    boolean conflict = conflictLetters.contains(letter);
                    if (onPicked != null) onPicked.call(new Selection(letter, conflict));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
