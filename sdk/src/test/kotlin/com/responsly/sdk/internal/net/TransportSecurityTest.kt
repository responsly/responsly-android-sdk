package com.responsly.sdk.internal.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSecurityTest {
    @Test
    fun httpsAlwaysAndHttpOnlyForLocalDevelopmentHosts() {
        listOf(
            "https://api.responsly.com", "https://203.0.113.7/sdk",
            "http://localhost:8000", "http://api.localhost", "http://127.0.0.1:8085", "http://[::1]:8000",
            "http://10.0.2.2:8000", "http://192.168.1.20", "http://172.16.0.1", "http://172.31.255.255",
            "http://form.responsly.test:8086/f/x", "http://mac.local:8000", "HTTP://LOCALHOST",
        ).forEach { assertTrue(it, TransportSecurity.isAllowed(it)) }

        listOf(
            "http://api.responsly.com", "http://203.0.113.7", "http://172.32.0.1", "http://172.15.0.1",
            "http://192.169.1.1", "http://1.2.3.4", "http://10.0.0", "http://256.1.1.1",
            "http://localhost.example.com", "http://test", "ftp://localhost", "file:///etc/hosts",
            "https://", "not a url", "",
        ).forEach { assertFalse(it, TransportSecurity.isAllowed(it)) }
    }
}
