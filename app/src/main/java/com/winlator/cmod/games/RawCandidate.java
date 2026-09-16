package com.winlator.cmod.games;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A single game folder discovered by {@link GameScanner} together with the
 * .exe files found inside it. Used as input for heuristic resolution.
 */
public class RawCandidate {
    public final File folder;
    public final String folderName;
    public final List<ExeEntry> exes = new ArrayList<>();

    public RawCandidate(File folder) {
        this.folder = folder;
        this.folderName = folder.getName();
    }

    public static class ExeEntry {
        public final File file;
        public final String relativePath;
        public final long sizeBytes;

        public ExeEntry(File file, String relativePath, long sizeBytes) {
            this.file = file;
            this.relativePath = relativePath;
            this.sizeBytes = sizeBytes;
        }
    }
}
