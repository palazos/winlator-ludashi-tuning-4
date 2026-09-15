package com.winlator.cmod.games;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Resolves a SAF document tree URI returned by
 * {@code ActivityResultContracts.OpenDocumentTree} into a real
 * {@link java.io.File} on the host filesystem.
 *
 * <p>Handles:
 * <ul>
 *   <li>Primary external storage (volume id {@code "primary"}).</li>
 *   <li>Removable SD cards mounted under {@code /storage/&lt;UUID&gt;}.</li>
 *   <li>Devices where the SD card mount has to be discovered through
 *       {@link StorageManager} (e.g. older SDKs).</li>
 * </ul>
 *
 * <p>If the URI points at something we can't access as a regular file
 * (USB OTG, Google Drive, OneDrive, network shares, …) we return
 * {@code null} so the caller can show a friendly "pick another folder"
 * message instead of pretending the scan can proceed.</p>
 */
public final class TreeUriResolver {
    private static final String TAG = "TreeUriResolver";

    private TreeUriResolver() {}

    public static File resolveToFile(Context ctx, Uri treeUri) {
        if (treeUri == null) return null;
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null) return null;
            String[] split = docId.split(":", 2);
            String volume = split[0];
            String relPath = split.length > 1 ? split[1] : "";

            File mount = mountForVolume(ctx, volume);
            if (mount == null) return null;
            File resolved = relPath.isEmpty() ? mount : new File(mount, relPath);
            return (resolved.exists() && resolved.canRead()) ? resolved : null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve tree URI: " + e.getMessage());
            return null;
        }
    }

    private static File mountForVolume(Context ctx, String volume) {
        if ("primary".equalsIgnoreCase(volume)) {
            return Environment.getExternalStorageDirectory();
        }

        // /storage/<UUID> usually works on real devices with a removable card.
        File direct = new File("/storage/" + volume);
        if (direct.exists() && direct.canRead()) return direct;

        // Fall back to StorageManager.
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) return null;
            for (StorageVolume v : sm.getStorageVolumes()) {
                String uuid = v.getUuid();
                if (uuid == null) continue;
                if (!uuid.equalsIgnoreCase(volume)) continue;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    File f = v.getDirectory();
                    if (f != null) return f;
                }
                try {
                    Method m = v.getClass().getMethod("getPath");
                    Object path = m.invoke(v);
                    if (path instanceof String) return new File((String) path);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "StorageManager fallback failed: " + e.getMessage());
        }
        return null;
    }
}
