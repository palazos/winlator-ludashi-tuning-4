package com.winlator.cmod.games;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One game folder detected during a scan with its resolved entry point.
 * Built from {@link RawCandidate} after heuristic resolution.
 */
public class GameCandidate {
    public enum Source { HEURISTIC }

    public String displayName;
    public File folder;
    public File exe;
    public final List<File> alternateExes = new ArrayList<>();
    public float confidence;
    public Source source = Source.HEURISTIC;
    public String reason;
    public boolean selected = true;

    public GameCandidate() {}

    public GameCandidate(String displayName, File folder, File exe) {
        this.displayName = displayName;
        this.folder = folder;
        this.exe = exe;
    }
}
