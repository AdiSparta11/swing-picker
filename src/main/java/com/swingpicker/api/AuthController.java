package com.swingpicker.api;

import com.swingpicker.auth.Auth;
import com.swingpicker.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** POST /api/login {"password": "..."}, POST /api/logout, GET /api/session. */
@RestController
@RequestMapping("/api")
public class AuthController {

    public record LoginRequest(String password) {
    }

    private final Auth auth;
    private final Deque<Long> recentFailures = new ArrayDeque<>();

    public AuthController(Auth auth) {
        this.auth = auth;
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest r) {
        return Map.of("lockEnabled", auth.enabled(), "unlocked", auth.validSession(AuthFilter.sessionCookie(r)));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest r, HttpServletResponse w)
            throws InterruptedException {
        if (!auth.enabled()) {
            return Map.of("unlocked", true);
        }
        synchronized (recentFailures) {
            long now = System.currentTimeMillis();
            while (!recentFailures.isEmpty() && now - recentFailures.peekFirst() > 60_000) {
                recentFailures.pollFirst();
            }
            if (recentFailures.size() >= 10) {
                w.setStatus(429);
                return Map.of("error", "Too many wrong attempts. Wait a minute and try again.");
            }
        }
        if (!auth.checkPassword(body == null ? null : body.password())) {
            synchronized (recentFailures) {
                recentFailures.addLast(System.currentTimeMillis());
            }
            Thread.sleep(400); // slows down guessing
            w.setStatus(401);
            return Map.of("error", "Wrong password.");
        }
        w.addHeader("Set-Cookie", cookie(auth.newSession(), Auth.SESSION.toSeconds(), secure(r)));
        return Map.of("unlocked", true);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest r, HttpServletResponse w) {
        w.addHeader("Set-Cookie", cookie("", 0, secure(r)));
        return Map.of("unlocked", false);
    }

    /** Render terminates TLS in front of the app, so trust its forwarded-protocol header. */
    private static boolean secure(HttpServletRequest r) {
        return r.isSecure() || "https".equalsIgnoreCase(r.getHeader("X-Forwarded-Proto"));
    }

    static String cookie(String value, long maxAge, boolean secure) {
        return Auth.COOKIE + "=" + value + "; Max-Age=" + maxAge + "; Path=/; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
    }
}
