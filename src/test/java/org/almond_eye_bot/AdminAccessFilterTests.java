package org.almond_eye_bot;

import org.almond_eye_bot.config.AdminAccessFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AdminAccessFilterTests {
    private MockHttpServletRequest request(String address, String host, String method) {
        var request = new MockHttpServletRequest(method, "/admin/api/state");
        request.setServletPath("/admin/api/state");
        request.setRemoteAddr(address);
        request.setServerName(host);
        return request;
    }

    @Test
    void defaultAccessRequiresLoopbackClientAndLocalHost() throws Exception {
        var filter = new AdminAccessFilter("");
        var allowed = new AtomicBoolean();
        filter.doFilter(request("127.0.0.1", "localhost", "GET"), new MockHttpServletResponse(),
                (req, res) -> allowed.set(true));
        assertTrue(allowed.get());
        for (var request : new MockHttpServletRequest[]{request("192.168.1.2", "localhost", "GET"),
                request("127.0.0.1", "attacker.example", "GET")}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("Request should not pass"));
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void remoteTokenAndWriteHeaderAreBothRequired() throws Exception {
        var filter = new AdminAccessFilter("test-token");
        var request = request("192.168.1.2", "bot.example", "PUT");
        var unauthorized = new MockHttpServletResponse();
        filter.doFilter(request, unauthorized, (req, res) -> fail("Missing token"));
        assertEquals(401, unauthorized.getStatus());
        request.addHeader("Authorization", "Bearer test-token");
        var forbidden = new MockHttpServletResponse();
        filter.doFilter(request, forbidden, (req, res) -> fail("Missing write header"));
        assertEquals(403, forbidden.getStatus());
        request.addHeader("X-Requested-With", "AlmondAdmin");
        var allowed = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> allowed.set(true));
        assertTrue(allowed.get());
    }
}
