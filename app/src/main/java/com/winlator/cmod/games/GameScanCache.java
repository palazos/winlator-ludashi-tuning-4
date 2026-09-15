package com.winlator.cmod.games;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Simple persistent cache that stores AI resolutions on disk so repeated
 * scans of the same library don't issue new network calls.
 *
 * <p>The key is a stable signature combining the folder name and the list
 * of exe filenames + sizes. If the user adds/removes an exe inside the
 * folder, the signature changes and the entry is invalidated.</p>
 */
public class GameScanCache {
    private static final String TAG = "GameScanCache";
    private static final String FILE_NAME = "games_scan_cache.json";

    private final File file;
    private final Map<String, Entry> entries = new HashMap<>();

    public GameScanCache(Context context) {
        this.file = new File(context.getFilesDir(), FILE_NAME);
        load();
    }

    public static class Entry {
        public String name;
        public String exeName;
        public float confidence;
        public long updatedAt;
    }

    public static String signatureFor(RawCandidate raw) {
        StringBuilder sb = new StringBuilder();
        sb.append(raw.folderName.toLowerCase(Locale.ROOT));
        for (RawCandidate.ExeEntry e : raw.exes) {
            sb.append('|').append(e.relativePath.toLowerCase(Locale.ROOT))
              .append(':').append(e.sizeBytes);
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    public Entry get(String signature) {
        return entries.get(signature);
    }

    public void put(String signature, String name, String exeName, float confidence) {
        Entry e = new Entry();
        e.name = name;
        e.exeName = exeName;
        e.confidence = confidence;
        e.updatedAt = System.currentTimeMillis();
        entries.put(signature, e);
    }

    public synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Entry> en : entries.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("name", en.getValue().name);
                o.put("exe", en.getValue().exeName);
                o.put("confidence", en.getValue().confidence);
                o.put("updatedAt", en.getValue().updatedAt);
                root.put(en.getKey(), o);
            }
            try (FileWriter w = new FileWriter(file)) {
                w.write(root.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to save cache: " + e.getMessage());
        }
    }

    private synchronized void load() {
        if (!file.exists()) return;
        try {
            String raw = FileUtils.readString(file);
            if (raw == null || raw.isEmpty()) return;
            JSONObject root = new JSONObject(raw);
            JSONArray names = root.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                JSONObject o = root.getJSONObject(key);
                Entry e = new Entry();
                e.name = o.optString("name", null);
                e.exeName = o.optString("exe", null);
                e.confidence = (float) o.optDouble("confidence", 0.5);
                e.updatedAt = o.optLong("updatedAt", 0L);
                entries.put(key, e);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load cache: " + e.getMessage());
        }
    }
}
