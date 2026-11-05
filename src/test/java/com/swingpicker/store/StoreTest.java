package com.swingpicker.store;

import com.swingpicker.store.JdbcStore.Target;
import com.swingpicker.store.Store.CallRow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    @Test
    void parsesANeonConnectionLink() {
        Target t = Target.parse("postgresql://neondb_owner:p%40ss@ep-cool-sky-a1b2c3.ap-southeast-1.aws.neon.tech/neondb"
                + "?sslmode=require&channel_binding=require");

        assertEquals("jdbc:postgresql://ep-cool-sky-a1b2c3.ap-southeast-1.aws.neon.tech:5432/neondb?sslmode=require", t.jdbcUrl());
        assertEquals("neondb_owner", t.user());
        assertEquals("p@ss", t.password());
    }

    @Test
    void addsTlsForRemoteHostsAndKeepsLocalPlain() {
        assertTrue(Target.parse("postgres://u:p@db.example.com:6543/app").jdbcUrl().endsWith(":6543/app?sslmode=require"));
        assertEquals("jdbc:postgresql://localhost:5432/postgres", Target.parse("postgres://u:p@localhost/").jdbcUrl());
    }

    @Test
    void rejectsAnythingThatIsntPostgres() {
        try {
            Target.parse("mysql://u:p@host/db");
            throw new AssertionError("accepted mysql");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("postgresql://"));
        }
    }

    @Test
    void memoryStoreBehavesLikeTheDatabase() {
        MemoryStore s = new MemoryStore(Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC));
        Store.HoldingRow h = s.addHolding("KAYNES", 26, 4230, null);
        assertEquals(30, s.updateHolding(h.id(), 30, 4100, LocalDate.of(2026, 4, 5)).get().quantity());
        assertTrue(s.updateHolding(999, 1, 1, null).isEmpty());
        s.watch("TCS");
        s.watch("TCS");
        assertEquals(1, s.watchlist().size());
        CallRow c = new CallRow(0, null, "swing", "TCS", "x", LocalDate.of(2026, 11, 4), 100, 95.0, 110.0, 101.0, 15,
                true, "open", null, null, null, null, null, null, null);
        assertTrue(s.logCall(c));
        assertFalse(s.logCall(c));
        CallRow saved = s.calls().get(0);
        s.saveGrade(saved.graded("target", LocalDate.of(2026, 11, 5), 100.0, LocalDate.of(2026, 11, 9), 110.0, 0.1, 2.0, 0.01));
        assertEquals("target", s.calls().get(0).status());
        assertFalse(s.persistent());
        assertTrue(s.deleteHolding(h.id()));
    }
}
