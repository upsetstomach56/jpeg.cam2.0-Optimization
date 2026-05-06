package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";
    private String currentGrainTexturePath = "";

    // Result returned by loadFromCam() so ImageProcessor knows what was loaded.
    public static class CamLoadResult {
        public boolean lutLoaded   = false;
        public boolean grainLoaded = false;
    }

    // Cache key for .cam loading: "<path>|<fileSize>" — avoids re-opening the ZIP
    // on every shot when the same .cam is in use.
    private String currentCamKey        = "";
    private boolean lastCamLutLoaded    = false;
    private boolean lastCamGrainLoaded  = false;

    private native boolean loadLutNative(String filePath);
    private native boolean loadGrainTextureNative(String filePath);

    // Signature matches C++ exactly: 16 total parameters after env/obj
    private native boolean processImageNative(
        String inPath, String outPath, int scaleDenom, int opacity,
        int grain, int grainSize, int vignette, int rollOff,
        int colorChrome, int chromeBlue, int shadowToe,
        int subtractiveSat, int halation, int bloom,
        int advancedGrainExperimental, int jpegQuality,
        boolean applyCrop, int numCores
    );

    /**
     * Loads either a .cube/.cub or .png HaldCLUT from the SD card.
     */
    public boolean loadLut(File lutFile, String lutName) {
        String safeName = lutName != null ? lutName : "OFF";
        String path = lutFile != null ? lutFile.getAbsolutePath() : "";
        boolean noLut = "OFF".equalsIgnoreCase(safeName) || "NONE".equalsIgnoreCase(path) || path.length() == 0;

        if (noLut) {
            if ("OFF".equals(currentLutName)) return true;
            loadLutNative("");
            currentLutName = "OFF";
            return true;
        }

        String lutKey = safeName + "|" + path;
        if (lutKey.equals(currentLutName)) return true;
        if (loadLutNative(path)) {
            currentLutName = lutKey;
            return true;
        }
        currentLutName = "";
        return false;
    }

    public boolean loadLut(String lutPath, String lutName) {
        String safePath = lutPath != null ? lutPath : "";
        File lutFile = safePath.length() > 0 && !"NONE".equalsIgnoreCase(safePath) ? new File(safePath) : null;
        return loadLut(lutFile, lutName);
    }

    public boolean applyLutToJpeg(String in, String out, int scale, int opacity,
                                  int grain, int grainSize, int vignette, int rollOff,
                                  int colorChrome, int chromeBlue, int shadowToe,
                                  int subtractiveSat, int halation, int bloom,
                                  int advancedGrainExperimental,
                                  int quality,
                                  boolean applyCrop, int numCores) {
        return processImageNative(in, out, scale, opacity, grain, grainSize, vignette,
                                 rollOff, colorChrome, chromeBlue, shadowToe,
                                 subtractiveSat, halation, bloom,
                                 advancedGrainExperimental, quality,
                                 applyCrop, numCores);
    }

    // Public wrapper to load the grain texture safely (loose-file path).
    public boolean loadGrainTexture(File texFile) {
        if (texFile == null || !texFile.exists()) return false;
        String texPath = texFile.getAbsolutePath();
        if (texPath.equals(currentGrainTexturePath)) return true;
        if (loadGrainTextureNative(texPath)) {
            currentGrainTexturePath = texPath;
            return true;
        }
        return false;
    }

    /**
     * Opens a .cam bundle (renamed ZIP) and loads its LUT and grain into native memory.
     *
     * Streaming approach: ZipFile stays open while we pipe each asset through an 8 KB
     * rolling buffer directly to a temp file. Peak heap cost is just the 8 KB buffer —
     * no byte array accumulation, no OOM risk on the 24 MB Dalvik heap.
     *
     * Temp files use strict 8.3 filenames (LUT_TMP.CUB / LUT_TMP.PNG / GRN_TMP.PNG).
     * The Sony camera FAT32 driver can read LFN files created on a PC but cannot CREATE
     * new LFN entries from the device — an open ZipFile handle is NOT the cause of ENOENT,
     * the long filename was. 8.3 names are safe to create with ZipFile still open.
     *
     * Cached by camPath + file size so the ZIP is only opened once per recipe switch.
     */
    public CamLoadResult loadFromCam(String camPath, File cacheDir) {
        CamLoadResult result = new CamLoadResult();
        java.io.File camFile = new java.io.File(camPath);
        if (!camFile.exists()) {
            DebugLog.write("CAM: file not found: " + camPath);
            return result;
        }

        // Cache check — if same .cam and same size, native already has it loaded.
        String cacheKey = camPath + "|" + camFile.length();
        if (cacheKey.equals(currentCamKey)) {
            DebugLog.write("CAM: cache hit lut=" + lastCamLutLoaded + " grain=" + lastCamGrainLoaded);
            result.lutLoaded   = lastCamLutLoaded;
            result.grainLoaded = lastCamGrainLoaded;
            return result;
        }

        File tempLutFile   = null;
        File tempGrainFile = null;

        try {
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(camFile);
            try {
                // 1. Read recipe.json to discover asset entry names (small — ByteArrayOutputStream fine).
                String lutEntry   = null;
                String grainEntry = null;
                java.util.zip.ZipEntry je = zf.getEntry("recipe.json");
                if (je != null) {
                    byte[] jsonBytes = readZipEntry(zf, je);
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(new String(jsonBytes, "UTF-8"));
                        lutEntry   = json.optString("lutEntry",   null);
                        grainEntry = json.optString("grainEntry", null);
                    } catch (Exception e) {
                        DebugLog.write("CAM: recipe.json parse error: " + e.getMessage());
                    }
                } else {
                    DebugLog.write("CAM: no recipe.json in bundle");
                }

                // 2. Stream LUT directly to 8.3 temp file (no byte array in heap).
                if (lutEntry == null) {
                    DebugLog.write("CAM: no lutEntry in recipe.json");
                } else {
                    java.util.zip.ZipEntry le = zf.getEntry(lutEntry);
                    if (le != null) {
                        boolean isPng = lutEntry.toLowerCase().endsWith(".png");
                        tempLutFile = new File(cacheDir, isPng ? "LUT_TMP.PNG" : "LUT_TMP.CUB");
                        DebugLog.write("CAM: streaming lut \"" + lutEntry + "\" (" + le.getSize() + "b) -> " + tempLutFile.getName());
                        streamZipEntryToFile(zf, le, tempLutFile);
                        result.lutLoaded = loadLutNative(tempLutFile.getAbsolutePath());
                        DebugLog.write("CAM: loadLutNative=" + result.lutLoaded);
                        currentLutName = "";
                    } else {
                        DebugLog.write("CAM: lutEntry \"" + lutEntry + "\" not found in ZIP");
                    }
                }

                // 3. Stream grain directly to 8.3 temp file (optional).
                if (grainEntry != null) {
                    java.util.zip.ZipEntry ge = zf.getEntry(grainEntry);
                    if (ge != null) {
                        tempGrainFile = new File(cacheDir, "GRN_TMP.PNG");
                        DebugLog.write("CAM: streaming grain \"" + grainEntry + "\" (" + ge.getSize() + "b) -> " + tempGrainFile.getName());
                        streamZipEntryToFile(zf, ge, tempGrainFile);
                        result.grainLoaded = loadGrainTextureNative(tempGrainFile.getAbsolutePath());
                        DebugLog.write("CAM: loadGrainTextureNative=" + result.grainLoaded);
                        currentGrainTexturePath = "";
                    } else {
                        DebugLog.write("CAM: grainEntry \"" + grainEntry + "\" not found in ZIP");
                    }
                }

            } finally {
                zf.close();
            }

        } catch (Exception e) {
            DebugLog.write("CAM: load exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        } finally {
            if (tempLutFile   != null) tempLutFile.delete();
            if (tempGrainFile != null) tempGrainFile.delete();
        }

        currentCamKey      = cacheKey;
        lastCamLutLoaded   = result.lutLoaded;
        lastCamGrainLoaded = result.grainLoaded;
        DebugLog.write("CAM: loaded lut=" + result.lutLoaded + " grain=" + result.grainLoaded);
        return result;
    }

    /** Reads a small ZipEntry (e.g. recipe.json) into memory via ByteArrayOutputStream. */
    private static byte[] readZipEntry(java.util.zip.ZipFile zf, java.util.zip.ZipEntry entry)
            throws java.io.IOException {
        java.io.InputStream is = zf.getInputStream(entry);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        is.close();
        return baos.toByteArray();
    }

    /**
     * Streams a ZipEntry to a file using an 8 KB rolling buffer.
     * Peak heap cost: 8 KB — no full-file byte array ever allocated.
     */
    private static void streamZipEntryToFile(java.util.zip.ZipFile zf,
                                              java.util.zip.ZipEntry entry,
                                              File dest) throws java.io.IOException {
        java.io.InputStream is = zf.getInputStream(entry);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        } finally {
            fos.close();
            is.close();
        }
    }
}
