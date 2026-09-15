package com.winlator.cmod.games;

import android.util.Log;

import com.winlator.cmod.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Resolves the entry-point .exe for a list of {@link RawCandidate} folders
 * by calling OpenAI's chat completions endpoint with a strict JSON schema
 * prompt. Falls back to {@link HeuristicResolver} silently when:
 * <ul>
 *   <li>the API key embedded at build time is empty</li>
 *   <li>the network call fails or times out</li>
 *   <li>the model returns malformed JSON or an exe that doesn't exist</li>
 * </ul>
 */
public class GameAIClient {
    private static final String TAG = "GameAIClient";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");

    private static final int CONNECT_TIMEOUT_S = 15;
    private static final int READ_TIMEOUT_S    = 60;
    private static final int BATCH_SIZE        = 8;

    private final OkHttpClient http;

    public GameAIClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .build();
    }

    public boolean isAvailable() {
        return BuildConfig.GAMES_AI_KEY != null && !BuildConfig.GAMES_AI_KEY.isEmpty();
    }

    private static String parentDirOf(String relativePath) {
        if (relativePath == null) return "";
        int slash = relativePath.lastIndexOf('/');
        if (slash <= 0) return "";
        String parent = relativePath.substring(0, slash);
        int prev = parent.lastIndexOf('/');
        return prev >= 0 ? parent.substring(prev + 1) : parent;
    }

    /**
     * Resolves all candidates. Always returns a list of the same size as
     * {@code raws} (uses heuristics for entries the AI couldn't help with).
     */
    public List<GameCandidate> resolveAll(List<RawCandidate> raws, GameScanCache cache) {
        List<GameCandidate> out = new ArrayList<>(raws.size());
        for (int i = 0; i < raws.size(); i++) out.add(null);

        // Cache hits first.
        List<RawCandidate> needAi = new ArrayList<>();
        List<Integer> needAiIdx = new ArrayList<>();
        for (int i = 0; i < raws.size(); i++) {
            RawCandidate raw = raws.get(i);
            if (cache != null) {
                String sig = GameScanCache.signatureFor(raw);
                GameScanCache.Entry hit = cache.get(sig);
                if (hit != null && hit.exeName != null) {
                    File exe = HeuristicResolver.matchExeByName(raw.exes, hit.exeName);
                    if (exe != null) {
                        GameCandidate c = new GameCandidate();
                        c.displayName = (hit.name != null && !hit.name.isEmpty())
                                ? hit.name : HeuristicResolver.cleanName(raw.folderName);
                        c.folder = raw.folder;
                        c.exe = exe;
                        c.confidence = hit.confidence;
                        c.source = GameCandidate.Source.CACHE;
                        c.reason = "Cached";
                        for (RawCandidate.ExeEntry e : raw.exes)
                            if (!e.file.equals(exe)) c.alternateExes.add(e.file);
                        out.set(i, c);
                        continue;
                    }
                }
            }
            needAi.add(raw);
            needAiIdx.add(i);
        }

        // Batch the AI calls.
        if (!needAi.isEmpty() && isAvailable()) {
            for (int from = 0; from < needAi.size(); from += BATCH_SIZE) {
                int to = Math.min(from + BATCH_SIZE, needAi.size());
                List<RawCandidate> chunk = needAi.subList(from, to);
                List<JSONObject> aiResults = callOpenAI(chunk);
                for (int j = 0; j < chunk.size(); j++) {
                    RawCandidate raw = chunk.get(j);
                    int globalIdx = needAiIdx.get(from + j);
                    JSONObject parsed = j < aiResults.size() ? aiResults.get(j) : null;
                    GameCandidate c = applyAiResult(raw, parsed);
                    if (c == null) c = HeuristicResolver.resolve(raw);
                    if (c != null) {
                        out.set(globalIdx, c);
                        if (cache != null && c.source == GameCandidate.Source.AI) {
                            cache.put(GameScanCache.signatureFor(raw), c.displayName,
                                    c.exe.getName(), c.confidence);
                        }
                    }
                }
            }
        }

        // Anything still null -> heuristic.
        for (int i = 0; i < raws.size(); i++) {
            if (out.get(i) == null) out.set(i, HeuristicResolver.resolve(raws.get(i)));
        }

        if (cache != null) cache.save();
        return out;
    }

    private GameCandidate applyAiResult(RawCandidate raw, JSONObject parsed) {
        if (parsed == null) return null;
        String exeName = parsed.optString("exe", null);
        if (exeName == null || exeName.isEmpty()) return null;

        File exe = HeuristicResolver.matchExeByName(raw.exes, exeName);
        if (exe == null) {
            Log.w(TAG, "AI returned unknown exe '" + exeName + "' for folder " + raw.folderName);
            return null;
        }

        GameCandidate c = new GameCandidate();
        String name = parsed.optString("name", "");
        c.displayName = !name.isEmpty() ? name : HeuristicResolver.cleanName(raw.folderName);
        c.folder = raw.folder;
        c.exe = exe;
        c.confidence = (float) parsed.optDouble("confidence", 0.7);
        c.source = GameCandidate.Source.AI;
        c.reason = parsed.optString("reason", "AI match");

        JSONArray alt = parsed.optJSONArray("alternates");
        if (alt != null) {
            for (int k = 0; k < alt.length(); k++) {
                File a = HeuristicResolver.matchExeByName(raw.exes, alt.optString(k));
                if (a != null && !a.equals(exe)) c.alternateExes.add(a);
            }
        }
        if (c.alternateExes.isEmpty()) {
            for (RawCandidate.ExeEntry e : raw.exes)
                if (!e.file.equals(exe)) c.alternateExes.add(e.file);
        }
        return c;
    }

    private List<JSONObject> callOpenAI(List<RawCandidate> chunk) {
        try {
            JSONArray itemsArr = new JSONArray();
            for (RawCandidate raw : chunk) {
                JSONObject item = new JSONObject();
                item.put("folder", raw.folderName);
                JSONArray exesArr = new JSONArray();
                for (RawCandidate.ExeEntry e : raw.exes) {
                    JSONObject ex = new JSONObject();
                    ex.put("path", e.relativePath);
                    ex.put("parent_dir", parentDirOf(e.relativePath));
                    ex.put("size", e.sizeBytes);
                    exesArr.put(ex);
                }
                item.put("exes", exesArr);
                itemsArr.put(item);
            }

            String system = "You identify PC games installed on disk. " +
                    "Each item describes one game folder with its exe files. " +
                    "Use ALL signals to pick the right entrypoint AND infer the canonical game name: " +
                    "the folder name, every exe's filename, the exe's parent directory name and size. " +
                    "If the folder name is generic (e.g. \"Game\", a version number, a build hash) " +
                    "derive the real name from the exe filenames or parent directories. " +
                    "Prefer official launchers (Rockstar, EA, Ubisoft, Bethesda, Epic, etc.) when they are the canonical entrypoint. " +
                    "Reject installers, redistributables, anti-cheat helpers, crash reporters and updaters. " +
                    "The 'exe' field MUST be one of the provided paths verbatim. " +
                    "The 'name' field is the human-readable game title (e.g. \"Red Dead Redemption 2\"), not the folder name. " +
                    "Respond ONLY with the JSON object described, no markdown, no prose.";

            String user = "Items: " + itemsArr.toString() + "\n" +
                    "Return JSON of the form {\"results\":[{\"folder\":\"<folder field copied verbatim>\"," +
                    "\"name\":\"<canonical game title>\"," +
                    "\"exe\":\"<one of the provided exe paths>\"," +
                    "\"confidence\":0..1," +
                    "\"alternates\":[\"<other plausible exe paths>\"]," +
                    "\"reason\":\"<one short sentence>\"}]}";

            JSONObject body = new JSONObject();
            body.put("model", BuildConfig.GAMES_AI_MODEL);
            body.put("temperature", 0);
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", "json_object");
            body.put("response_format", responseFormat);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", user));
            body.put("messages", messages);

            Request req = new Request.Builder()
                    .url(BuildConfig.GAMES_AI_ENDPOINT)
                    .header("Authorization", "Bearer " + BuildConfig.GAMES_AI_KEY)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    Log.w(TAG, "OpenAI HTTP " + resp.code());
                    return new ArrayList<>();
                }
                ResponseBody rb = resp.body();
                if (rb == null) return new ArrayList<>();
                String raw = rb.string();
                JSONObject root = new JSONObject(raw);
                JSONArray choices = root.optJSONArray("choices");
                if (choices == null || choices.length() == 0) return new ArrayList<>();
                String content = choices.getJSONObject(0)
                        .getJSONObject("message").getString("content");
                JSONObject parsed = new JSONObject(content);
                JSONArray results = parsed.optJSONArray("results");
                if (results == null) return new ArrayList<>();

                // Match results back to chunk by folder name (order is best-effort).
                List<JSONObject> out = new ArrayList<>();
                for (RawCandidate raw2 : chunk) {
                    JSONObject match = null;
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject r = results.optJSONObject(i);
                        if (r != null && raw2.folderName.equals(r.optString("folder"))) {
                            match = r;
                            break;
                        }
                    }
                    out.add(match);
                }
                return out;
            }
        } catch (Exception e) {
            Log.w(TAG, "AI call failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}
