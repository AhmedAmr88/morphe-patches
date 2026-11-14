package org.ungoogled.samsungphone;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.Log;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The dial pad's contact suggestions. Samsung Phone reads them through a ContentProviderClient, which the providers
 * patch does not see:
 * <ul>
 * <li>names from content://com.android.contacts/contacts/dialer_keypad/filter/DIGITS, a T9 search only Samsung's
 * contacts provider has (Android's throws "Unknown URI", so nothing was suggested). Answered here: a contact whose
 * name has a word starting with the typed digits on the keypad letters (2 = ABC, ...), with action_code holding the
 * name in digits so the app highlights the matched letters.</li>
 * <li>numbers from Android's phone table with a selection naming Samsung's sec_read_only_contacts column in a
 * subquery, which Android's provider refuses: the condition is left out.</li>
 * </ul>
 * Everything else goes to ProviderCalls like the app's ContentResolver calls.
 */
public final class DialpadSearch {
    private static final String TAG = "UA";
    private static final String READ_ONLY_CONTACTS =
            "contact_id IN (SELECT _id FROM contacts WHERE sec_read_only_contacts = 0)";
    private static final int MAX_RESULTS = 200;

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
            try {
                ContentResolver resolver = app != null ? app.getContentResolver() : null;
                return t9(resolver, client, uri.getLastPathSegment(), projection, signal);
            } catch (RuntimeException e) {
                Log.w(TAG, "dial pad name search failed: " + e);
                return null;
            }
        }
        if (selection != null && selection.contains(READ_ONLY_CONTACTS)) {
            selection = selection.replace(READ_ONLY_CONTACTS, "1");
        }
        if (app != null) {
            return ProviderCalls.query(app.getContentResolver(), uri, projection, selection, args, sort, signal);
        }
        return client.query(uri, projection, selection, args, sort, signal);
    }

    private static boolean keypadSearch(Uri uri) {
        if (uri == null || !"com.android.contacts".equals(uri.getAuthority())) return false;
        List<String> path = uri.getPathSegments();
        return path.size() >= 3 && path.contains("dialer_keypad") && path.contains("filter");
    }

    private static Cursor t9(ContentResolver resolver, ContentProviderClient client, String digits, String[] projection,
                             CancellationSignal signal) throws RemoteException {
        if (projection == null) return null;
        MatrixCursor out = new MatrixCursor(projection);
        String query = digits == null ? "" : digits.replaceAll("[^0-9]", "");
        if (query.isEmpty()) return out;

        Uri phones = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String order = ContactsContract.CommonDataKinds.Phone.STARRED + " DESC, "
                + ContactsContract.CommonDataKinds.Phone.SORT_KEY_PRIMARY + ", "
                + ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC";
        Cursor c = resolver != null
                ? resolver.query(phones, PHONE_COLUMNS, null, null, order, signal)
                : client.query(phones, PHONE_COLUMNS, null, null, order, signal);
        if (c == null) return out;

        Set<Long> seen = new HashSet<>();
        try {
            while (c.moveToNext() && out.getCount() < MAX_RESULTS) {
                if (signal != null) signal.throwIfCanceled();
                long contactId = c.getLong(0);
                if (seen.contains(contactId)) continue;

                String name = c.getString(1);
                String alt = c.getString(2);
                String code = encode(name);
                String altCode = encode(alt);
                if (!matches(name, code, query) && !matches(alt, altCode, query)) continue;
                seen.add(contactId);

                Object[] row = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) {
                    row[i] = value(projection[i], c, name, alt, code, altCode);
                }
                out.addRow(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    private static Object value(String column, Cursor c, String name, String alt, String code, String altCode) {
        switch (column) {
            case "_id":
            case "contact_id":
                return c.getLong(0);
            case "display_name":
                return name;
            case "display_name_alt":
                return alt != null ? alt : name;
            case "sort_key":
            case "sort_key_alt":
                return c.getString(3);
            case "starred":
                return c.getInt(4);
            case "photo_id":
                return c.isNull(5) ? 0L : c.getLong(5);
            case "lookup":
                return c.getString(6);
            case "phonetic_name":
                return c.getString(7);
            case "data1":
            case "number":
                return c.getString(8);
            case "action_code":
                return code;
            case "action_code_alt":
                return alt != null ? altCode : code;
            case "action_code_type":
                return 0;
            case "sec_primary_name_initial":
                return initial(name);
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
