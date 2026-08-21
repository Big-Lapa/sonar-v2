package com.sonar.trading.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Token gate for /api/v1/** (AccountOpsController + ManualTradeController — close-all,
 * auto-pause clear, manual orders on the live exchange). Actuator stays open. Comparison
 * is constant-time and the token is never logged (same discipline as monitoring's
 * AdminTokenFilter). Registered via {@link OpsApiConfig} — deliberately not a @Component
 * so WebMvcTest slices don't drag it into controller tests.
 */
public class OpsTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Admin-Token";

    private final OpsApiProperties props;

    public OpsTokenFilter(OpsApiProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !normalizePath(request.getRequestURI()).startsWith("/api/v1/");
    }

    /**
     * Normalize the raw request URI before the prefix check so a protected path cannot masquerade
     * as an unguarded one. Tomcat and Spring's PathPatternParser strip matrix parameters and decode
     * {@code %2f}/{@code %3b} when mapping to the controller, but {@code getRequestURI()} returns the
     * raw target — so {@code /api;x=y/v1/...}, {@code /api%3Bx/v1/...}, {@code /api%2fv1/...} and
     * {@code //api/v1/...} would all skip this filter yet still reach the guarded controller. We
     * decode percent-escapes (path-safe: {@code +} is left intact), strip matrix params, and collapse
     * duplicate slashes so the check sees the same path the dispatcher will route.
     */
    static String normalizePath(String uri) {
        if (uri == null) {
            return "";
        }
        String p = decodePath(uri);
        p = p.replaceAll(";[^/]*", "");   // strip matrix parameters (path params) per segment
        p = p.replaceAll("/{2,}", "/");   // collapse duplicate slashes
        return collapseDotSegments(p);
    }

    /**
     * Resolve {@code .} and {@code ..} path segments (RFC 3986 remove_dot_segments), so a request
     * like {@code /api/x/../v1/close-all} — which the dispatcher resolves back to {@code /api/v1/...}
     * before routing — cannot slip past the prefix check by looking like a different path.
     */
    private static String collapseDotSegments(String path) {
        if (!path.contains("/.")) {
            return path;
        }
        boolean absolute = path.startsWith("/");
        boolean trailingSlash = path.length() > 1 && path.endsWith("/");
        String[] segments = (absolute ? path.substring(1) : path).split("/", -1);
        java.util.Deque<String> out = new java.util.ArrayDeque<>();
        for (String seg : segments) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (!out.isEmpty() && !out.peekLast().equals("..")) {
                    out.removeLast();
                } else if (!absolute) {
                    out.addLast(seg);
                }
                continue;
            }
            out.addLast(seg);
        }
        String result = (absolute ? "/" : "") + String.join("/", out);
        return trailingSlash && !result.endsWith("/") ? result + "/" : result;
    }

    /** Percent-decode a URI path (unlike URLDecoder, {@code +} stays a literal plus in a path). */
    private static String decodePath(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            out.write(c);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!props.enabled()) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "ops API disabled: ADMIN_TOKEN not configured");
            return;
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || !constantTimeEquals(provided, props.token())) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid admin token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
