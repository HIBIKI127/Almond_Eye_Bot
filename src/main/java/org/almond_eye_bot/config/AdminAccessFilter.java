package org.almond_eye_bot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class AdminAccessFilter extends OncePerRequestFilter {
    private final String token;

    public AdminAccessFilter(@Value("${bot.admin-token:}") String token) { this.token = token; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals("/admin/api") && !path.startsWith("/admin/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (token.isBlank()) {
            Set<String> loopback = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
            String host = request.getServerName().replace("[", "").replace("]", "");
            if (!loopback.contains(request.getRemoteAddr()) || (!loopback.contains(host) && !host.equals("localhost"))) {
                reject(response, 403, "管理接口默认仅限本机访问；远程访问需设置 BOT_ADMIN_TOKEN");
                return;
            }
        } else {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                    authorization.getBytes(StandardCharsets.UTF_8))) {
                reject(response, 401, "请输入正确的管理 Token");
                return;
            }
        }
        if (!Set.of("GET", "HEAD").contains(request.getMethod())
                && !"AlmondAdmin".equals(request.getHeader("X-Requested-With"))) {
            reject(response, 403, "请求来源无效，请从配置页提交");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
