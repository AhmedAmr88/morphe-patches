package android.os;

import java.util.Locale;

/**
 * Stand-in for One UI's SemSystemProperties (names and signatures Samsung Phone uses, no Samsung code): Android's
 * own system properties, where Samsung's (ro.build.version.oneui, ro.csc.*) are empty. Apps parse the One UI
 * version as a number, so an absent one reads "0".
 */
public class SemSystemProperties {
    private static String real(String key) {
        try {
            Object value = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return value == null ? "" : (String) value;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** Android's value, or for the dual SIM properties One UI has and other phones lack, TelephonyManager's. */
    private static String value(String key) {
        String value = real(key);
        if (!value.isEmpty()) return value;
        String sim = org.ungoogled.samsungphone.SimCompat.property(key);
        return sim != null ? sim : "";
    }

    public static java.lang.String get(java.lang.String p0) {
        String value = value(p0);
        if (value.isEmpty() && "ro.build.version.oneui".equals(p0)) return "0";
        return value;
    }

    public static java.lang.String get(java.lang.String p0, java.lang.String p1) {
        String value = value(p0);
        return value.isEmpty() ? p1 : value;
    }

    public static boolean getBoolean(java.lang.String p0, boolean p1) {
        String value = real(p0);
        if (value.isEmpty()) return p1;
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "y".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    public static java.lang.String getCountryCode() {
        return "";
    }

    public static java.lang.String getSalesCode() {
        return "";
    }

    public static java.lang.String getCountryIso() {
        return Locale.getDefault().getCountry();
    }

    public static int getInt(java.lang.String p0, int p1) {
        try {
            return Integer.parseInt(real(p0).trim());
        } catch (NumberFormatException e) {
            return p1;
        }
    }
}
