package io.rwv.webview;

import java.net.URI;

final class Navigation {
    private Navigation() {}
    static boolean isWebUrl(String value) {
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty()
                && !uri.getRawAuthority().contains("@");
        } catch (Exception ex) { return false; }
    }
}
