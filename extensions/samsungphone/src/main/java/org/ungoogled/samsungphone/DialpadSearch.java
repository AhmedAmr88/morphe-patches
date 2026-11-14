package org.ungoogled.samsungphone;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.util.Log;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dial pad's contact suggestions. Samsung Phone reads them through a ContentProviderClient, which the providers
 * patch does not see:
 * <ul>
 * <li>names from content://com.android.contacts/contacts/dialer_keypad/filter/DIGITS, a T9 search only Samsung's
 * contacts provider has. Answered here: contacts with a word of the name starting with the typed digits on the keypad
 * letters (2 = ABC, ...), the ones you call most first, with action_code holding the name in digits so the app
 * highlights the matched letters.</li>
 * <li>numbers from Android's phone table with a selection naming Samsung's sec_read_only_contacts column in a
 * subquery, which Android's provider refuses: the condition is left out.</li>
 * </ul>
 * Every other query (call history among them) goes to the provider exactly as the app asked.
 */
public final class DialpadSearch {
    private static final String TAG = "UA";
    private static final String READ_ONLY_CONTACTS =
            "contact_id IN (SELECT _id FROM contacts WHERE sec_read_only_contacts = 0)";
    private static final int MAX_RESULTS = 200;
    /** Calls read to rank the contacts you call most. */
    private static final int CALL_HISTORY = 1000;
    private static final long CALL_COUNTS_MAX_AGE_MS = 60_000;
    /** Temporary: shows what the name search found (or why it failed) as a toast. */
    private static final boolean DEBUG = true;

    private static final String[] PHONE_COLUMNS = {
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_ALTERNATIVE,
            ContactsContract.CommonDataKinds.Phone.SORT_KEY_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.PHOTO_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.PHONETIC_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
    };

    private static Map<String, Integer> callCounts = Collections.emptyMap();
    private static long callCountsAt;

    private DialpadSearch() {
    }

    public static Cursor query(ContentProviderClient client, Uri uri, String[] projection, String selection,
                               String[] args, String sort) throws RemoteException {
        return query(client, uri, projection, selection, args, sort, null);
    }

    public static Cursor query(ContentProviderClient client, Uri uri, String[] projection, String selection,
                               String[] args, String sort, CancellationSignal signal) throws RemoteException {
        Context app = Calls.app();
        if (keypadSearch(uri)) {
            String digits = uri.getLastPathSegment();
            try {
                ContentResolver resolver = app != null ? app.getContentResolver() : null;
                Cursor c = t9(resolver, client, digits, projection, signal);
                debug(app, "T9 " + digits + ": " + (c == null ? "null" : c.getCount() + " contacts"));
                return c != null ? c : empty(projection);
            } catch (RuntimeException e) {
                Log.w(TAG, "dial pad name search failed: " + e);
                debug(app, "T9 " + digits + " failed: " + e);
                return empty(projection);
            }
        }
        if (selection != null && selection.contains(READ_ONLY_CONTACTS)) {
            // The dial pad's number search: a missing result would drop every suggestion, so answer nothing instead.
            try {
                Cursor c = client.query(uri, projection, selection.replace(READ_ONLY_CONTACTS, "1"), args, sort,
                        signal);
                return c != null ? c : empty(projection);
            } catch (RuntimeException e) {
                Log.w(TAG, "dial pad number search failed: " + e);
                return empty(projection);
            }
        }
        return client.query(uri, projection, selection, args, sort, signal);
    }

    private static Cursor empty(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[]{"_id"}, 0);
    }

    private static void debug(Context app, String text) {
        if (!DEBUG || app == null) return;
        Log.i(TAG, text);
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
    }

    private static boolean keypadSearch(Uri uri) {
        if (uri == null || !"com.android.contacts".equals(uri.getAuthority())) return false;
        List<String> path = uri.getPathSegments();
        return path.size() >= 3 && path.contains("dialer_keypad") && path.contains("filter");
    }

    /** One contact the name search found. */
    private static final class Match {
        final long contactId;
        final String name, alt, sortKey, lookup, phonetic, code, altCode;
        final int starred;
        final long photoId;
        String number;
        int calls;
        int bestNumberCalls = -1;

        Match(Cursor c, String name, String alt, String code, String altCode) {
            contactId = c.getLong(0);
            this.name = name;
            this.alt = alt;
            sortKey = c.getString(3);
            starred = c.getInt(4);
            photoId = c.isNull(5) ? 0L : c.getLong(5);
            lookup = c.getString(6);
            phonetic = c.getString(7);
            this.code = code;
            this.altCode = altCode;
        }

        /** Adds one of the contact's numbers: its calls count, and the most called number is the one shown. */
        void addNumber(String number, int calls) {
            this.calls += calls;
            if (calls > bestNumberCalls) {
                bestNumberCalls = calls;
                this.number = number;
            }
        }
    }

    private static Cursor t9(ContentResolver resolver, ContentProviderClient client, String digits, String[] projection,
                             CancellationSignal signal) throws RemoteException {
        if (projection == null) return null;
        MatrixCursor out = new MatrixCursor(projection);
        String query = digits == null ? "" : digits.replaceAll("[^0-9]", "");
        if (query.isEmpty()) return out;

        Map<String, Integer> counts = callCounts(resolver);
        Uri phones = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String order = ContactsContract.CommonDataKinds.Phone.STARRED + " DESC, "
                + ContactsContract.CommonDataKinds.Phone.SORT_KEY_PRIMARY + ", "
                + ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC";
        Cursor c = resolver != null
                ? resolver.query(phones, PHONE_COLUMNS, null, null, order, signal)
                : client.query(phones, PHONE_COLUMNS, null, null, order, signal);
        if (c == null) return out;

        Map<Long, Match> found = new LinkedHashMap<>();
        try {
            while (c.moveToNext()) {
                if (signal != null) signal.throwIfCanceled();
                long contactId = c.getLong(0);
                Match match = found.get(contactId);
                if (match == null) {
                    String name = c.getString(1);
                    String alt = c.getString(2);
                    String code = encode(name);
                    String altCode = encode(alt);
                    if (!matches(name, code, query) && !matches(alt, altCode, query)) continue;
                    match = new Match(c, name, alt, code, altCode);
                    found.put(contactId, match);
                }
                String number = c.getString(8);
                Integer calls = counts.get(key(number));
                match.addNumber(number, calls != null ? calls : 0);
            }
        } finally {
            c.close();
        }

        // Most called first, then favourites, then the contacts' own order (by name).
        List<Match> ranked = new ArrayList<>(found.values());
        Collections.sort(ranked, (a, b) -> {
            if (a.calls != b.calls) return Integer.compare(b.calls, a.calls);
            return Integer.compare(b.starred, a.starred);
        });
        for (Match match : ranked) {
            if (out.getCount() >= MAX_RESULTS) break;
            Object[] row = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                row[i] = value(projection[i], match);
            }
            out.addRow(row);
        }
        return out;
    }

    /** How many of the recent calls were with each number (by its last 9 digits). Kept for a minute. */
    private static synchronized Map<String, Integer> callCounts(ContentResolver resolver) {
        long now = SystemClock.elapsedRealtime();
        if (callCountsAt != 0 && now - callCountsAt < CALL_COUNTS_MAX_AGE_MS) return callCounts;
        callCountsAt = now;
        Map<String, Integer> counts = new HashMap<>();
        if (resolver != null) {
            try (Cursor c = resolver.query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER}, null, null,
                    CallLog.Calls.DATE + " DESC")) {
                int read = 0;
                while (c != null && c.moveToNext() && read++ < CALL_HISTORY) {
                    String key = key(c.getString(0));
                    if (key == null) continue;
                    Integer n = counts.get(key);
                    counts.put(key, n == null ? 1 : n + 1);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "call history for the dial pad ranking: " + e);
            }
        }
        callCounts = counts;
        return counts;
    }

    /** A phone number's last 9 digits, so 0101..., +20101... and 0020101... match. */
    private static String key(String number) {
        if (number == null) return null;
        String d = number.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return null;
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }

    private static Object value(String column, Match m) {
        switch (column) {
            case "_id":
            case "contact_id":
                return m.contactId;
            case "display_name":
                return m.name;
            case "display_name_alt":
                return m.alt != null ? m.alt : m.name;
            case "sort_key":
            case "sort_key_alt":
                return m.sortKey;
            case "starred":
                return m.starred;
            case "photo_id":
                return m.photoId;
            case "lookup":
                return m.lookup;
            case "phonetic_name":
                return m.phonetic;
            case "data1":
            case "number":
                return m.number;
            case "action_code":
                return m.code;
            case "action_code_alt":
                return m.alt != null ? m.altCode : m.code;
            case "action_code_type":
                return 0;
            case "sec_primary_name_initial":
                return initial(m.name);
            default:
                return null;
        }
    }

    /** A word of the name starts with the digits. */
    private static boolean matches(String name, String code, String query) {
        if (name == null || code == null) return false;
        int from = 0;
        while (true) {
            int at = code.indexOf(query, from);
            if (at < 0) return false;
            if (at == 0 || !Character.isLetterOrDigit(name.charAt(at - 1))) return true;
            from = at + 1;
        }
    }

    /** The name as keypad digits, one character for each of the name's, so positions line up for highlighting. */
    static String encode(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            sb.append(digit(name.charAt(i)));
        }
        return sb.toString();
    }

    private static char digit(char ch) {
        char c = Character.toLowerCase(ch);
        if (c > 'z') {
            String plain = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            if (!plain.isEmpty()) c = plain.charAt(0);
        }
        if (c >= '0' && c <= '9') return c;
        if (c >= 'a' && c <= 'c') return '2';
        if (c >= 'd' && c <= 'f') return '3';
        if (c >= 'g' && c <= 'i') return '4';
        if (c >= 'j' && c <= 'l') return '5';
        if (c >= 'm' && c <= 'o') return '6';
        if (c >= 'p' && c <= 's') return '7';
        if (c >= 't' && c <= 'v') return '8';
        if (c >= 'w' && c <= 'z') return '9';
        return ch == ' ' ? ' ' : '_';
    }

    private static String initial(String name) {
        if (name == null || name.isEmpty()) return null;
        int cp = name.codePointAt(0);
        return new String(Character.toChars(cp)).toUpperCase(Locale.getDefault());
    }
}
