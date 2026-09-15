package com.winlator.cmod.games;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks a "best effort" main executable for a game folder when no AI
 * resolution is available. Used as a silent fallback by
 * {@link GameAIClient} when the network call fails or the API key is not
 * configured.
 */
public final class HeuristicResolver {
    private HeuristicResolver() {}

    /**
     * Substring blacklist applied case-insensitively against the exe basename.
     */
    private static final Set<String> EXE_BLACKLIST = new HashSet<>(Arrays.asList(
            "unins", "uninstall", "setup", "install",
            "vcredist", "vc_redist", "dxsetup", "dxwebsetup", "directx",
            "redist", "physx", "oalinst", "dotnetfx",
            "crashreporter", "crashpad", "crashhandler",
            "easyanticheat", "battleye", "be_service", "eac",
            "dump", "report", "reporter",
            "directxsetup", "directxinstaller"
    ));

    /**
     * Folder names where the "real" exe usually lives.
     */
    private static final Set<String> PREFERRED_PATHS = new HashSet<>(Arrays.asList(
            "bin", "bin32", "bin64", "binaries", "win64", "win32",
            "x64", "x86", "game", "system", "release", "retail"
    ));

    public static GameCandidate resolve(RawCandidate raw) {
        if (raw == null || raw.exes.isEmpty()) return null;

        RawCandidate.ExeEntry best = null;
        int bestScore = Integer.MIN_VALUE;
        for (RawCandidate.ExeEntry e : raw.exes) {
            int s = score(e);
            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }
        if (best == null) return null;

        GameCandidate c = new GameCandidate();
        c.displayName = cleanName(raw.folderName);
        c.folder = raw.folder;
        c.exe = best.file;
        c.confidence = 0.4f;
        c.source = GameCandidate.Source.HEURISTIC;
        c.reason = "Heuristic match";
        for (RawCandidate.ExeEntry e : raw.exes) {
            if (e.file.equals(best.file)) continue;
            c.alternateExes.add(e.file);
        }
        return c;
    }

    private static int score(RawCandidate.ExeEntry e) {
        String lowerName = e.file.getName().toLowerCase(Locale.ROOT);
        for (String black : EXE_BLACKLIST) {
            if (lowerName.contains(black)) return -1000;
        }

        int s = 0;
        String relLower = e.relativePath.toLowerCase(Locale.ROOT);
        for (String hint : PREFERRED_PATHS) {
            if (relLower.contains("/" + hint + "/") || relLower.startsWith(hint + "/")) {
                s += 100;
                break;
            }
        }

        // Larger exes are usually the game itself.
        long mb = e.sizeBytes / (1024L * 1024L);
        if (mb >= 200) s += 60;
        else if (mb >= 80) s += 40;
        else if (mb >= 20) s += 20;
        else if (mb >= 5)  s += 10;
        else if (mb >= 1)  s += 5;

        return s;
    }

    /**
     * Used when the folder name is itself the candidate display name. Trims
     * separators and obvious noise tokens but keeps casing.
     */
    public static String cleanName(String folderName) {
        if (folderName == null) return "";
        String n = folderName.replace('_', ' ').replace('.', ' ').replace('-', ' ');
        n = n.replaceAll("(?i)\\b(v\\d+|repack|setup|installer|portable|goty|edition)\\b", "");
        n = n.replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? folderName : n;
    }

    /**
     * @return true if the file is in the candidate list (defensive check
     *   used when validating an AI-supplied exe filename).
     */
    public static File matchExeByName(List<RawCandidate.ExeEntry> exes, String basename) {
        if (basename == null) return null;
        String target = basename.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) return null;
        for (RawCandidate.ExeEntry e : exes) {
            if (e.file.getName().toLowerCase(Locale.ROOT).equals(target)) return e.file;
            if (e.relativePath.toLowerCase(Locale.ROOT).equals(target)) return e.file;
        }
        // last attempt: endsWith
        for (RawCandidate.ExeEntry e : exes) {
            if (e.relativePath.toLowerCase(Locale.ROOT).endsWith(target)) return e.file;
        }
        return null;
    }
}
