package io.rwv.webview;
import org.junit.Test;
import static org.junit.Assert.*;
public class BrowserPolicyTest {
    @Test public void sameOriginRequiresHttpsAndExactHostPort() {
        assertTrue(BrowserPolicy.sameSecureOrigin("https://EXAMPLE.org:443/path", "https://example.org"));
        assertFalse(BrowserPolicy.sameSecureOrigin("https://example.org", "https://example.org:8443"));
        assertFalse(BrowserPolicy.sameSecureOrigin("https://example.org", "https://example.org.evil.test"));
        assertFalse(BrowserPolicy.sameSecureOrigin("http://example.org", "http://example.org"));
        assertFalse(BrowserPolicy.sameSecureOrigin("https://example.org", "https://user:pass@example.org"));
        assertFalse(BrowserPolicy.sameSecureOrigin(null, "https://example.org"));
    }
}
