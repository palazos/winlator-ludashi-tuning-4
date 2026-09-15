package com.winlator.cmod.games;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Walks a games root directory and produces one {@link RawCandidate} per
 * detected game folder.
 *
 * <p>Two modes per folder:</p>
 * <ul>
 *   <li><b>single-game</b>: the folder either has a direct {@code .exe}
 *       file or a direct sub-folder named like a typical binary location
 *       ({@code bin}, {@code Win64}, {@code x64}, …). The folder is
 *       emitted as a single candidate and its whole subtree is scanned
 *       for exes that are then attached to it.</li>
 *   <li><b>library</b>: the folder has no exes nor binary-style children.
 *       Each direct sub-directory is recursed into. This lets the user
 *       point at a parent directory that contains many separate games.</li>
 * </ul>
 *
 * <p>This avoids the previous behavior where the picked root was greedily
 * captured as the only candidate.</p>
 */
public class GameScanner {
    private static final String TAG = "GameScanner";

    /**
     * Folder names that are never recursed into.
     */
    private static final Set<String> SKIP_FOLDERS = new HashSet<>(Arrays.asList(
            "_commonredist", "_redist", "_extras", "redist", "redists",
            "directx", "dotnet", "dotnetfx", "vcredist", "vc_redist",
            "physx", "openal", "$recycle.bin", "system volume information",
            "android"
    ));

    /**
     * Folder names whose presence inside a candidate strongly implies
     * "this is one game". Also used to score exes when sorting.
     */
    private static final Set<String> BIN_FOLDER_HINTS = new HashSet<>(Arrays.asList(
            "bin", "bin32", "bin64", "binaries", "win64", "win32",
            "x64", "x86", "game", "system", "release", "retail"
    ));

    public static class ScanOptions {
        public int rootMaxDepth = 4;        // recursion cap when walking down looking for games
        public int exeMaxDepth = 5;         // recursion cap when collecting exes from a single game
        public int exesPerCandidate = 12;
        public int maxCandidates = 500;
    }

    public interface ProgressListener {
        void onFolderVisited(File folder);
        void onCandidateFound(RawCandidate candidate);
        boolean isCancelled();
    }

    private final ScanOptions options;

    public GameScanner() { this(new ScanOptions()); }

    public GameScanner(ScanOptions options) {
        this.options = options != null ? options : new ScanOptions();
    }

    public List<RawCandidate> scan(File root, ProgressListener listener) {
        List<RawCandidate> results = new ArrayList<>();
        if (root == null || !root.isDirectory()) return results;
        scanRec(root, 0, results, listener);
        Log.i(TAG, "Scan finished: " + results.size() + " candidate(s) under " + root);
        return results;
    }

    private void scanRec(File folder, int depth, List<RawCandidate> results, ProgressListener listener) {
        if (results.size() >= options.maxCandidates) return;
        if (listener != null && listener.isCancelled()) return;
        if (folder == null || !folder.isDirectory()) return;
        if (depth > 0 && shouldSkip(folder)) return;
        if (depth > options.rootMaxDepth) return;
        if (listener != null) listener.onFolderVisited(folder);

        if (isSingleGameMode(folder)) {
            RawCandidate cand = new RawCandidate(folder);
            collectExesFromSubtree(folder, folder, 0, cand);
            if (!cand.exes.isEmpty()) {
                sortAndCap(cand);
                results.add(cand);
                if (listener != null) listener.onCandidateFound(cand);
            }
        } else {
            File[] children = folder.listFiles();
            if (children == null) return;
            Arrays.sort(children);
            for (File child : children) {
                if (child.isDirectory()) scanRec(child, depth + 1, results, listener);
            }
        }
    }

    /**
     * A folder is treated as a single game when it directly contains an
     * {@code .exe} file or a direct sub-folder whose name matches one of
     * {@link #BIN_FOLDER_HINTS}.
     */
    private boolean isSingleGameMode(File folder) {
        File[] children = folder.listFiles();
        if (children == null) return true;
        for (File c : children) {
            if (c.isFile() && c.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) return true;
            if (c.isDirectory()
                    && BIN_FOLDER_HINTS.contains(c.getName().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private void collectExesFromSubtree(File rootFolder, File dir, int depth, RawCandidate cand) {
        if (depth > options.exeMaxDepth) return;
        if (dir == null || !dir.isDirectory()) return;
        if (depth > 0 && shouldSkip(dir)) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isFile()) {
                if (c.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    String rel = relativize(rootFolder, c);
                    cand.exes.add(new RawCandidate.ExeEntry(c, rel, c.length()));
                }
            } else if (c.isDirectory()) {
                collectExesFromSubtree(rootFolder, c, depth + 1, cand);
            }
        }
    }

    private void sortAndCap(RawCandidate cand) {
        Collections.sort(cand.exes, (a, b) -> Integer.compare(score(b), score(a)));
        if (cand.exes.size() > options.exesPerCandidate) {
            cand.exes.subList(options.exesPerCandidate, cand.exes.size()).clear();
        }
    }

    private boolean shouldSkip(File dir) {
        if (dir.isHidden()) return true;
        String name = dir.getName().toLowerCase(Locale.ROOT);
        return SKIP_FOLDERS.contains(name);
    }

    private String relativize(File base, File target) {
        String basePath = base.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (targetPath.startsWith(basePath)) {
            String rel = targetPath.substring(basePath.length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            return rel.replace('\\', '/');
        }
        return target.getName();
    }

    private static int score(RawCandidate.ExeEntry e) {
        int s = 0;
        String relLower = e.relativePath.toLowerCase(Locale.ROOT);
        for (String hint : BIN_FOLDER_HINTS) {
            if (relLower.contains("/" + hint + "/") || relLower.startsWith(hint + "/")) {
                s += 5;
                break;
            }
        }
        if (e.sizeBytes > 100L * 1024 * 1024) s += 4;
        else if (e.sizeBytes > 20L * 1024 * 1024) s += 3;
        else if (e.sizeBytes > 5L * 1024 * 1024) s += 2;
        else if (e.sizeBytes > 1L * 1024 * 1024) s += 1;
        return s;
    }
}
