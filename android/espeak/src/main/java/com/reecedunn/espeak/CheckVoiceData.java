package com.reecedunn.espeak;

import android.content.Context;
import android.util.Log;

import java.io.File;

public final class CheckVoiceData {
    private static final String TAG = "eSpeakTTS";

    private static final String[] BASE_RESOURCES = {
            "version",
            "intonations",
            "phondata",
            "phonindex",
            "phontab",
            "en_dict",
    };

    private CheckVoiceData() {}

    public static File getDataPath(Context context) {
        return new File(context.getDir("voices", Context.MODE_PRIVATE), "espeak-ng-data");
    }

    public static boolean hasBaseResources(Context context) {
        final File dataPath = getDataPath(context);
        for (String resource : BASE_RESOURCES) {
            final File resourceFile = new File(dataPath, resource);
            if (!resourceFile.exists()) {
                Log.e(TAG, "Missing base resource: " + resourceFile.getPath());
                return false;
            }
        }
        return true;
    }

    public static int rawResourceId(Context context, String name) {
        final Context app = context.getApplicationContext();
        int id = app.getResources().getIdentifier(name, "raw", app.getPackageName());
        if (id != 0) return id;
        id = app.getResources().getIdentifier(name, "raw", "com.reecedunn.espeak");
        if (id != 0) return id;
        Log.e(TAG, "Missing raw resource: " + name + ", appPackage=" + app.getPackageName());
        return 0;
    }

    public static boolean canUpgradeResources(Context context) {
        try {
            final int vid = rawResourceId(context, "espeakdata_version");
            if (vid == 0) return false;
            final String version = FileUtils.read(context.getResources().openRawResource(vid));
            final String installedVersion = FileUtils.read(new File(getDataPath(context), "version"));
            return !version.equals(installedVersion);
        } catch (Exception e) {
            return false;
        }
    }
}
