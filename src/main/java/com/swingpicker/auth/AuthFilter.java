package com.swingpicker.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Guards every /api/ call except login and the session check. The page itself loads for anyone (it holds no data)
 * and shows the login screen when the API answers 401. /api/jobs/ uses its own token instead of the cookie.
 */
public class AuthFilter implements Filter {

    private final Auth auth;

    public AuthFilter(Auth auth) {
        this.auth = auth;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        HttpServletResponse w = (HttpServletResponse) res;
        String path = r.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/login") || path.equals("/api/session")
                || path.startsWith("/api/jobs/")) {
            chain.doFilter(req, res);
            return;
        }
        if (auth.validSession(sessionCookie(r))) {
            chain.doFilter(req, res);
            return;
        }
        w.setStatus(401);
        w.setContentType("application/json");
        w.getWriter().write("{\"error\":\"Locked. Enter your password.\",\"locked\":true}");
    }

    public static String sessionCookie(HttpServletRequest r) {
        Cookie[] cookies = r.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (Auth.COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
