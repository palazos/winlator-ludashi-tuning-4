package com.winlator.cmod.games;

import com.winlator.cmod.container.Container;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers to read/edit the {@link Container} {@code drives} string.
 *
 * <p>Format reminder (see {@link Container#drivesIterator(String)}):
 * letters and unix paths are concatenated with no separator, e.g.
 * <pre>F:/storage/emulated/0D:/storage/emulated/0/Download</pre>
 * Each pair is parsed by locating each {@code ':'}: the previous char is
 * the drive letter and the path runs up to the next {@code ':'} minus 1
 * (so the next letter starts there).</p>
 */
public final class DrivesEditor {
    private DrivesEditor() {}

    public static List<String[]> parse(String drives) {
        List<String[]> out = new ArrayList<>();
        if (drives == null || drives.isEmpty()) return out;
        for (String[] drive : Container.drivesIterator(drives)) {
            out.add(new String[]{drive[0], drive[1]});
        }
        return out;
    }

    /**
     * Returns the host path mapped to {@code letter} or {@code null} if absent.
     */
    public static String pathFor(String drives, char letter) {
        String upper = String.valueOf(letter).toUpperCase();
        for (String[] drive : parse(drives)) {
            if (drive[0].equalsIgnoreCase(upper)) return drive[1];
        }
        return null;
    }

    /**
     * Returns true if {@code letter} already has a mapping (case-insensitive).
     */
    public static boolean hasLetter(String drives, char letter) {
        return pathFor(drives, letter) != null;
    }

    /**
     * Returns the set of letters used in {@code drives}, upper-cased.
     */
    public static List<Character> usedLetters(String drives) {
        List<Character> out = new ArrayList<>();
        for (String[] drive : parse(drives)) {
            if (!drive[0].isEmpty()) out.add(Character.toUpperCase(drive[0].charAt(0)));
        }
        return out;
    }

    /**
     * Inserts or replaces the entry for {@code letter} so it points to
     * {@code unixPath}. Other letters and ordering are preserved.
     */
    public static String upsert(String drives, char letter, String unixPath) {
        if (unixPath == null) throw new IllegalArgumentException("unixPath is null");
        char upper = Character.toUpperCase(letter);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        for (String[] drive : parse(drives)) {
            char existing = Character.toUpperCase(drive[0].charAt(0));
            if (existing == upper) {
                sb.append(upper).append(':').append(unixPath);
                replaced = true;
            } else {
                sb.append(drive[0].charAt(0)).append(':').append(drive[1]);
            }
        }
        if (!replaced) sb.append(upper).append(':').append(unixPath);
        return sb.toString();
    }

    /**
     * Removes the entry for {@code letter} if present.
     */
    public static String remove(String drives, char letter) {
        char upper = Character.toUpperCase(letter);
        StringBuilder sb = new StringBuilder();
        for (String[] drive : parse(drives)) {
            char existing = Character.toUpperCase(drive[0].charAt(0));
            if (existing != upper) sb.append(drive[0].charAt(0)).append(':').append(drive[1]);
        }
        return sb.toString();
    }
}
