package org.ungoogled.samsungphone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.widget.Toast;

/**
 * Samsung Phone decides whether to show the SIM 1 / SIM 2 call buttons from One UI's own signals, which other phones
 * do not have:
 * <ul>
 * <li>persist.radio.multisim.config ("dsds" on a dual SIM phone): a radio property apps cannot read;</li>
 * <li>ril.ICC_TYPE0 / ril.ICC_TYPE1 ("0" when the slot is empty): Samsung's RIL;</li>
 * <li>Settings.System phone1_on / phone2_on (1 when the SIM is switched on): One UI's SIM manager.</li>
 * </ul>
 * Each is answered here from Android's TelephonyManager, so a phone with two SIMs in use gets the dual SIM buttons.
 */
public final class SimCompat {
    /** TelephonyManager.SIM_STATE_LOADED, which the SDK hides from apps. */
    private static final int SIM_STATE_LOADED = 10;
    /** Temporary: shows once what the dual SIM check sees, as a toast. */
    private static final boolean DEBUG = true;
    private static boolean shown;

    private SimCompat() {
    }

    private static TelephonyManager telephony() {
        Context app = Calls.app();
        if (app == null) {
            try {
                app = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return app == null ? null : app.getSystemService(TelephonyManager.class);
    }

    /** "dsds" when the phone has two SIM slots in use, else "". */
    public static String multiSimConfig() {
        String config = "";
        int modems = -1;
        try {
            TelephonyManager tm = telephony();
            if (tm != null) modems = tm.getActiveModemCount();
            if (modems >= 2) config = "dsds";
        } catch (RuntimeException ignored) {
        }
        debug("SIM: modems=" + modems + " sim1=" + simPresent(0) + " sim2=" + simPresent(1));
        return config;
    }

    private static synchronized void debug(String text) {
        if (!DEBUG || shown) return;
        Context app = Calls.app();
        if (app == null) return;
        shown = true;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }

    /** A SIM is in the slot (0 or 1) and usable. */
    public static boolean simPresent(int slot) {
        try {
            TelephonyManager tm = telephony();
            if (tm == null) return false;
            int state = tm.getSimState(slot);
            return state == TelephonyManager.SIM_STATE_READY || state == SIM_STATE_LOADED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Samsung's ril.ICC_TYPE value: "1" with a SIM in the slot, "0" without. */
    public static String iccType(int slot) {
        return simPresent(slot) ? "1" : "0";
    }

    /**
     * Answers for Samsung-only system properties, or null for any other key.
     */
    public static String property(String key) {
        if (key == null) return null;
        switch (key) {
            case "persist.radio.multisim.config":
                return multiSimConfig();
            case "ril.ICC_TYPE0":
                return iccType(0);
            case "ril.ICC_TYPE1":
                return iccType(1);
            default:
                return null;
        }
    }

    /** Settings.System phone1_on / phone2_on: 1 with a SIM in that slot, else 0; -1 for any other name. */
    public static int simSwitch(String name) {
        if ("phone1_on".equals(name)) return simPresent(0) ? 1 : 0;
        if ("phone2_on".equals(name)) return simPresent(1) ? 1 : 0;
        return -1;
    }
}
