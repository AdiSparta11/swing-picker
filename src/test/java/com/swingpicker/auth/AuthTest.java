package com.swingpicker.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTest {

    static Clock at(String t) {
        return Clock.fixed(Instant.parse(t), ZoneOffset.UTC);
    }

    @Test
    void passwordAndSessionsWork() {
        Auth a = new Auth("correct horse", "job-123", at("2026-11-05T10:00:00Z"));

        assertTrue(a.enabled());
        assertTrue(a.checkPassword("correct horse"));
        assertFalse(a.checkPassword("correct hors"));
        assertFalse(a.checkPassword(null));
        String s = a.newSession();
        assertTrue(a.validSession(s));
        assertFalse(a.validSession(null));
        assertFalse(a.validSession("garbage"));
        assertFalse(a.validSession(s + "x"));
        assertFalse(a.validSession("9999999999." + s.substring(s.indexOf('.') + 1)));   // longer expiry, same signature
    }

    @Test
    void sessionsExpireAfter30Days() {
        String s = new Auth("pw", null, at("2026-11-05T10:00:00Z")).newSession();

        assertTrue(new Auth("pw", null, at("2026-12-04T10:00:00Z")).validSession(s));
        assertFalse(new Auth("pw", null, at("2026-11-05T10:00:00Z").instant().plus(Auth.SESSION).plus(Duration.ofSeconds(1))
                .atZone(ZoneOffset.UTC).toInstant().toString().transform(AuthTest::at)).validSession(s));
    }

    @Test
    void changingThePasswordLogsEveryoneOut() {
        String s = new Auth("old", null, at("2026-11-05T10:00:00Z")).newSession();
        assertFalse(new Auth("new", null, at("2026-11-05T10:00:00Z")).validSession(s));
    }

    @Test
    void noPasswordMeansNoLockAndJobTokenIsSeparate() {
        Auth open = new Auth("  ", null, at("2026-11-05T10:00:00Z"));
        assertFalse(open.enabled());
        assertTrue(open.validSession(null));
        assertFalse(open.jobsEnabled());
        assertFalse(open.checkJobToken("anything"));

        Auth a = new Auth("pw", "job-123", at("2026-11-05T10:00:00Z"));
        assertTrue(a.checkJobToken("job-123"));
        assertFalse(a.checkJobToken("pw"));
    }
}
