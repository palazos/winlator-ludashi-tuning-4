package com.winlator.cmod.games;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Generates the {@code .desktop} file for a {@link GameCandidate} inside
 * a target {@link Container}. The output mirrors the format produced by
 * {@code FileManagerFragment.createShortcutDirectly} so the shortcut is
 * picked up by the existing list and works with the regular launch
 * machinery.
 */
public final class ShortcutBuilder {
    private static final String TAG = "ShortcutBuilder";

    private ShortcutBuilder() {}

    public static class Result {
        public final boolean created;
        public final boolean skipped;
        public final String message;
        public final File desktopFile;

        public Result(boolean created, boolean skipped, String message, File desktopFile) {
            this.created = created;
            this.skipped = skipped;
            this.message = message;
            this.desktopFile = desktopFile;
        }
    }

    /**
     * @param overwrite if true an existing .desktop with the same name is replaced.
     */
    public static Result createForGame(Context context, Container container, GameCandidate candidate, boolean overwrite) {
        try {
            String displayName = sanitizeFileName(candidate.displayName);
            File shortcutsDir = container.getDesktopDir();
            if (!shortcutsDir.exists()) shortcutsDir.mkdirs();
            File desktopFile = new File(shortcutsDir, displayName + ".desktop");

            if (desktopFile.exists() && !overwrite) {
                return new Result(false, true, "Already exists", desktopFile);
            }

            String unixPath = candidate.exe.getAbsolutePath();
            String winePrefix = getContainerWineHome(context, container) + "/.wine";
            try (PrintWriter writer = new PrintWriter(new FileWriter(desktopFile))) {
                writer.println("[Desktop Entry]");
                writer.println("Name=" + displayName);
                writer.println("Exec=env WINEPREFIX=\"" + winePrefix + "\" wine \"" + unixPath + "\"");
                writer.println("Type=Application");
                writer.println("Icon=" + displayName);
                writer.println("container_id:" + container.id);
            }

            // Extract icon, mirroring createShortcutDirectly.
            File iconDir64 = container.getIconsDir(64);
            if (!iconDir64.exists()) iconDir64.mkdirs();
            File iconDest = new File(iconDir64, displayName + ".png");
            boolean iconExtracted = false;
            try {
                iconExtracted = ExeIconExtractor.extractIcon(candidate.exe, iconDest);
            } catch (Exception ex) {
                Log.w(TAG, "Icon extraction failed for " + candidate.exe + ": " + ex.getMessage());
            }

            if (iconExtracted) {
                File iconsDir = new File(Environment.getExternalStorageDirectory(), "Winlator/icons");
                if (!iconsDir.exists()) iconsDir.mkdirs();
                File userIcon = new File(iconsDir, displayName + ".png");
                if (!userIcon.exists()) {
                    try { FileUtils.copy(iconDest, userIcon); } catch (Exception ignored) {}
                }
            }

            // Force a fresh SteamGridDB cover fetch on next render.
            File coversDir = new File(Environment.getExternalStorageDirectory(), "Winlator/covers");
            if (!coversDir.exists()) coversDir.mkdirs();
            File autoCover = new File(coversDir, displayName + ".png");
            if (autoCover.exists()) autoCover.delete();

            return new Result(true, false, null, desktopFile);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create shortcut for " + candidate.displayName, e);
            return new Result(false, false, e.getMessage(), null);
        }
    }

    /**
     * Mirrors {@code FileManagerFragment.getContainerWineHome}: containers live under
     * {@code getFilesDir()/imagefs}, and the WINEPREFIX must be expressed relative to
     * that chroot root, not as an absolute host path.
     */
    private static String getContainerWineHome(Context context, Container container) {
        File imagefs = new File(context.getFilesDir(), "imagefs");
        String imagefsPath = normalizeFilePath(imagefs.getAbsolutePath());
        String rootPath = normalizeFilePath(container.getRootDir().getAbsolutePath());
        return rootPath.startsWith(imagefsPath)
                ? rootPath.substring(imagefsPath.length())
                : "/home/" + ImageFs.USER;
    }

    private static String normalizeFilePath(String path) {
        if (path == null) return "";
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return new File(path).getAbsolutePath();
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "Untitled";
        String n = name.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        return n.isEmpty() ? "Untitled" : n;
    }
}
