package com.cuktech.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalUrlPolicyTest {
    @Test public void originalPagesAndLocalRoutesStayInWebView() {
        LocalUrlPolicy policy = new LocalUrlPolicy("http://127.0.0.1:8123");
        assertTrue(policy.isLocal("http://127.0.0.1:8123/phone.html"));
        assertTrue(policy.isLocal("http://127.0.0.1:8123/config.html?x=1#xiaomi"));
        assertTrue(policy.isLocal("http://127.0.0.1:8123/static/power_chart.html"));
    }

    @Test public void similarHostsPortsAndDangerousSchemesAreNotTrusted() {
        LocalUrlPolicy policy = new LocalUrlPolicy("http://127.0.0.1:8123");
        for (String url : new String[]{"http://127.0.0.1:8124/config.html", "http://localhost:8123/",
                "http://127.0.0.1.evil.test:8123/", "http://evil@127.0.0.1:8123/",
                "https://127.0.0.1:8123/", "file:///etc/passwd", "javascript:alert(1)",
                "http://127.0.0.1:8123\\@evil.test/", "http://127.0.0.1:8123/\n", null}) {
            assertFalse(String.valueOf(url), policy.isLocal(url));
        }
    }

    @Test public void nativeDownloadsOnlyFetchExistingCsvExportRoutes() {
        LocalUrlPolicy policy = new LocalUrlPolicy("http://127.0.0.1:8123/");
        assertTrue(policy.isCsvExport("http://127.0.0.1:8123/api/export/1?hours=24"));
        assertTrue(policy.isCsvExport("http://127.0.0.1:8123/api/export/4"));
        for (String url : new String[]{"http://127.0.0.1:8123/api/config", "http://127.0.0.1:8123/api/export/5",
                "http://127.0.0.1:8123/api/export/1/../config", "http://127.0.0.1:8123/api/export/%31",
                "https://example.com/api/export/1", "blob:http://127.0.0.1:8123/1234"}) {
            assertFalse(url, policy.isCsvExport(url));
        }
    }

    @Test public void runtimeCannotSupplyRemoteOrPathScopedBase() {
        for (String url : new String[]{"https://example.com", "http://localhost:8000", "http://127.0.0.1:0",
                "http://127.0.0.1:99999", "http://127.0.0.1:8000/phone.html", "http://127.0.0.1:8000?x=1"}) {
            try { new LocalUrlPolicy(url); fail(url); } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void onlyWebLinksCanLeaveTheApp() {
        assertTrue(LocalUrlPolicy.isExternalWeb("https://github.com/example"));
        assertTrue(LocalUrlPolicy.isExternalWeb("http://example.com/path"));
        assertFalse(LocalUrlPolicy.isExternalWeb("intent://test"));
        assertFalse(LocalUrlPolicy.isExternalWeb("javascript:alert(1)"));
        assertFalse(LocalUrlPolicy.isExternalWeb("https://user:password@example.com/"));
    }
}
