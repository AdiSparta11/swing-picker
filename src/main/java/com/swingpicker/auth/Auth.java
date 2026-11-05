package com.swingpicker.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * One password for the whole app. A successful login gets a session token "expiry.signature" where the signature is
 * HMAC-SHA256 over the expiry, keyed from the password, so changing APP_PASSWORD logs every device out.
 * No password configured = lock off (for running on your own laptop).
 */
public class Auth {

    public static final String COOKIE = "sp_session";
    public static final Duration SESSION = Duration.ofDays(30);

    private final byte[] password;
    private final byte[] key;
    private final String jobToken;
    private final Clock clock;

    public Auth(String password, String jobToken, Clock clock) {
        this.password = password == null || password.isBlank() ? null : password.getBytes(StandardCharsets.UTF_8);
        this.key = this.password == null ? null : sha256(("swing-picker:" + password).getBytes(StandardCharsets.UTF_8));
        this.jobToken = jobToken == null || jobToken.isBlank() ? null : jobToken;
        this.clock = clock;
    }

    public boolean enabled() {
        return password != null;
    }

    public boolean jobsEnabled() {
        return jobToken != null;
    }

    /** Constant-time comparison, so response timing doesn't leak how much of a guess was right. */
    public boolean checkPassword(String attempt) {
        if (password == null || attempt == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(password), sha256(attempt.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean checkJobToken(String attempt) {
        return jobToken != null && attempt != null
                && MessageDigest.isEqual(sha256(jobToken.getBytes(StandardCharsets.UTF_8)), sha256(attempt.getBytes(StandardCharsets.UTF_8)));
    }

    public String newSession() {
        long expiry = clock.instant().plus(SESSION).getEpochSecond();
        return expiry + "." + sign(Long.toString(expiry));
    }

    public boolean validSession(String token) {
        if (!enabled()) {
            return true;
        }
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String expiry = token.substring(0, dot);
        long exp;
        try {
            exp = Long.parseLong(expiry);
        } catch (NumberFormatException e) {
            return false;
        }
        if (exp < clock.instant().getEpochSecond()) {
            return false;
        }
        byte[] expected = sign(expiry).getBytes(StandardCharsets.US_ASCII);
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
