package io.rwv.webview;
import org.junit.Test;
import static org.junit.Assert.*;
public class NavigationTest {
    @Test public void acceptsWebUrls() {
        assertTrue(Navigation.isWebUrl("https://example.org/a"));
        assertTrue(Navigation.isWebUrl("http://192.168.1.2:3355/"));
    }
    @Test public void blocksOtherSchemesAndCredentials() {
        assertFalse(Navigation.isWebUrl("file:///etc/passwd"));
        assertFalse(Navigation.isWebUrl("javascript:alert(1)"));
        assertFalse(Navigation.isWebUrl("https://user:pass@example.org"));
        assertFalse(Navigation.isWebUrl("http:///missing"));
    }
}
