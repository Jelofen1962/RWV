package io.rwv.webview;

import java.net.URI;
import java.util.Locale;

/** Exact origin checks for permission requests. No credentials or non-HTTPS origins. */
final class BrowserPolicy {
    private BrowserPolicy() {}
    static String secureOrigin(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            int port = uri.getPort();
            if (port > 65535 || port == 0) return null;
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port);
        } catch (Exception ex) { return null; }
    }
    static boolean sameSecureOrigin(String page, String requester) {
        String origin = secureOrigin(page);
        return origin != null && origin.equals(secureOrigin(requester));
    }
}
