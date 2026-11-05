package com.swingpicker.store;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Postgres via plain JDBC (one connection, reopened when it drops). Light on memory, which matters on
 * Render's free instance, and plenty for one user. Works with Neon's free tier.
 */
public class JdbcStore implements Store {

    public static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS holdings (
            id            BIGSERIAL PRIMARY KEY,
            symbol        TEXT NOT NULL,
            quantity      INTEGER NOT NULL CHECK (quantity > 0),
            average_price NUMERIC(14,4) NOT NULL CHECK (average_price > 0),
            buy_date      DATE,
            added_at      TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        """
        CREATE TABLE IF NOT EXISTS watchlist (
            symbol   TEXT PRIMARY KEY,
            added_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        """
        CREATE TABLE IF NOT EXISTS calls (
            id          BIGSERIAL PRIMARY KEY,
            logged_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
            kind        TEXT NOT NULL,
            symbol      TEXT NOT NULL,
            setup       TEXT,
            signal_date DATE NOT NULL,
            ref_price   NUMERIC(14,4) NOT NULL,
            stop        NUMERIC(14,4),
            target      NUMERIC(14,4),
            max_entry   NUMERIC(14,4),
            hold_days   INTEGER NOT NULL,
            recommended BOOLEAN NOT NULL,
            status      TEXT NOT NULL DEFAULT 'open',
            entry_date  DATE,
            entry_price NUMERIC(14,4),
            exit_date   DATE,
            exit_price  NUMERIC(14,4),
            return_pct  DOUBLE PRECISION,
            r_multiple  DOUBLE PRECISION,
            benchmark   DOUBLE PRECISION,
            UNIQUE (kind, symbol, signal_date)
        )"""
    };

    public static final String SELECT_HOLDINGS =
            "SELECT id, symbol, quantity, average_price, buy_date, added_at FROM holdings ORDER BY added_at, id";
    public static final String INSERT_HOLDING =
            "INSERT INTO holdings (symbol, quantity, average_price, buy_date) VALUES (?, ?, ?, ?) RETURNING id, added_at";
    public static final String UPDATE_HOLDING =
            "UPDATE holdings SET quantity = ?, average_price = ?, buy_date = ? WHERE id = ? "
            + "RETURNING id, symbol, quantity, average_price, buy_date, added_at";
    public static final String DELETE_HOLDING = "DELETE FROM holdings WHERE id = ?";
    public static final String SELECT_WATCH = "SELECT symbol, added_at FROM watchlist ORDER BY added_at, symbol";
    public static final String INSERT_WATCH = "INSERT INTO watchlist (symbol) VALUES (?) ON CONFLICT (symbol) DO NOTHING";
    public static final String DELETE_WATCH = "DELETE FROM watchlist WHERE symbol = ?";
    public static final String INSERT_CALL =
            "INSERT INTO calls (kind, symbol, setup, signal_date, ref_price, stop, target, max_entry, hold_days, "
            + "recommended, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (kind, symbol, signal_date) DO NOTHING";
    public static final String SELECT_CALLS =
            "SELECT id, logged_at, kind, symbol, setup, signal_date, ref_price, stop, target, max_entry, hold_days, "
            + "recommended, status, entry_date, entry_price, exit_date, exit_price, return_pct, r_multiple, benchmark "
            + "FROM calls ORDER BY signal_date DESC, symbol";
    public static final String UPDATE_CALL =
            "UPDATE calls SET status = ?, entry_date = ?, entry_price = ?, exit_date = ?, exit_price = ?, "
            + "return_pct = ?, r_multiple = ?, benchmark = ? WHERE id = ?";

    /** Connection details parsed from a postgres:// URL (the format Neon and most hosts give you). */
    public record Target(String jdbcUrl, String user, String password) {

        /**
         * postgresql://user:pass@host[:port]/db?sslmode=require  ->  jdbc:postgresql://host:port/db?sslmode=require.
         * Neon's extra channel_binding parameter is dropped; TLS is required either way.
         */
        public static Target parse(String url) {
            String u = url.trim();
            if (u.startsWith("jdbc:")) {
                u = u.substring(5);
            }
            URI uri = URI.create(u);
            if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
                throw new IllegalArgumentException("DATABASE_URL should start with postgresql://");
            }
            String user = null;
            String pass = null;
            if (uri.getRawUserInfo() != null) {
                String[] parts = uri.getRawUserInfo().split(":", 2);
                user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                pass = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String db = uri.getPath() == null || uri.getPath().length() <= 1 ? "postgres" : uri.getPath().substring(1);
            StringBuilder params = new StringBuilder();
            boolean ssl = false;
            if (uri.getRawQuery() != null) {
                for (String kv : uri.getRawQuery().split("&")) {
                    if (kv.startsWith("channel_binding=") || kv.isBlank()) {
                        continue;
                    }
                    ssl |= kv.startsWith("sslmode=");
                    params.append(params.length() == 0 ? "?" : "&").append(kv);
                }
            }
            if (!ssl && !"localhost".equals(uri.getHost()) && !"127.0.0.1".equals(uri.getHost())) {
                params.append(params.length() == 0 ? "?" : "&").append("sslmode=require");
            }
            return new Target("jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + db + params, user, pass);
        }
    }

    private final Target target;
    private Connection conn;

    public JdbcStore(Target target) {
        this.target = target;
        try {
            Connection c = connection();
            try (Statement st = c.createStatement()) {
                for (String ddl : SCHEMA) {
                    st.execute(ddl);
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Couldn't set up the database: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    private synchronized Connection connection() throws SQLException {
        if (conn == null || conn.isClosed() || !conn.isValid(3)) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // replacing it anyway
                }
            }
            conn = DriverManager.getConnection(target.jdbcUrl(), target.user(), target.password());
            conn.setAutoCommit(true);
        }
        return conn;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Runs once; if the connection had silently died, reconnects and retries once. */
    private synchronized <T> T db(Work<T> w) {
        try {
            return w.run(connection());
        } catch (SQLException first) {
            try {
                conn = null;
                return w.run(connection());
            } catch (SQLException e) {
                throw new StoreException("Database error: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public List<HoldingRow> holdings() {
        return db(c -> {
            List<HoldingRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT_HOLDINGS); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(holding(rs));
                }
            }
            return out;
        });
    }

    @Override
    public HoldingRow addHolding(String symbol, int quantity, double averagePrice, LocalDate buyDate) {
        return db(c -> {
            try (PreparedStatement ps = c.prepareStatement(INSERT_HOLDING)) {
                ps.setString(1, symbol);
                ps.setInt(2, quantity);
                ps.setDouble(3, averagePrice);
                setDate(ps, 4, buyDate);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new HoldingRow(rs.getLong(1), symbol, quantity, averagePrice, buyDate, instant(rs.getTimestamp(2)));
                }
            }
        });
    }

    @Override
    public Optional<HoldingRow> updateHolding(long id, int quantity, double averagePrice, LocalDate buyDate) {
        return db(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPDATE_HOLDING)) {
                ps.setInt(1, quantity);
                ps.setDouble(2, averagePrice);
                setDate(ps, 3, buyDate);
                ps.setLong(4, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(holding(rs)) : Optional.<HoldingRow>empty();
                }
            }
        });
    }

    @Override
    public boolean deleteHolding(long id) {
        return db(c -> {
            try (PreparedStatement ps = c.prepareStatement(DELETE_HOLDING)) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public List<WatchRow> watchlist() {
        return db(c -> {
            List<WatchRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT_WATCH); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WatchRow(rs.getString(1), instant(rs.getTimestamp(2))));
                }
            }
            return out;
        });
    }

    @Override
    public void watch(String symbol) {
        db(c -> {
            try (PreparedStatement ps = c.prepareStatement(INSERT_WATCH)) {
                ps.setString(1, symbol);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public boolean unwatch(String symbol) {
        return db(c -> {
            try (PreparedStatement ps = c.prepareStatement(DELETE_WATCH)) {
                ps.setString(1, symbol);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean logCall(CallRow k) {
        return db(c -> {
            try (PreparedStatement ps = c.prepareStatement(INSERT_CALL)) {
                ps.setString(1, k.kind());
                ps.setString(2, k.symbol());
                ps.setString(3, k.setup());
                setDate(ps, 4, k.signalDate());
                ps.setDouble(5, k.refPrice());
                setDouble(ps, 6, k.stop());
                setDouble(ps, 7, k.target());
                setDouble(ps, 8, k.maxEntry());
                ps.setInt(9, k.holdDays());
                ps.setBoolean(10, k.recommended());
                ps.setString(11, k.status());
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public List<CallRow> calls() {
        return db(c -> {
            List<CallRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT_CALLS); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CallRow(rs.getLong(1), instant(rs.getTimestamp(2)), rs.getString(3), rs.getString(4),
                            rs.getString(5), date(rs.getDate(6)), rs.getDouble(7), dbl(rs, 8), dbl(rs, 9), dbl(rs, 10),
                            rs.getInt(11), rs.getBoolean(12), rs.getString(13), date(rs.getDate(14)), dbl(rs, 15),
                            date(rs.getDate(16)), dbl(rs, 17), dbl(rs, 18), dbl(rs, 19), dbl(rs, 20)));
                }
            }
            return out;
        });
    }

    @Override
    public void saveGrade(CallRow k) {
        db(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPDATE_CALL)) {
                ps.setString(1, k.status());
                setDate(ps, 2, k.entryDate());
                setDouble(ps, 3, k.entryPrice());
                setDate(ps, 4, k.exitDate());
                setDouble(ps, 5, k.exitPrice());
                setDouble(ps, 6, k.returnPct());
                setDouble(ps, 7, k.rMultiple());
                setDouble(ps, 8, k.benchmark());
                ps.setLong(9, k.id());
                return ps.executeUpdate();
            }
        });
    }

    // ---------- mapping ----------

    private static HoldingRow holding(ResultSet rs) throws SQLException {
        return new HoldingRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getDouble(4), date(rs.getDate(5)),
                instant(rs.getTimestamp(6)));
    }

    private static void setDate(PreparedStatement ps, int i, LocalDate d) throws SQLException {
        if (d == null) {
            ps.setNull(i, Types.DATE);
        } else {
            ps.setDate(i, Date.valueOf(d));
        }
    }

    private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null || v.isNaN() || v.isInfinite()) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, v);
        }
    }

    private static Double dbl(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public static class StoreException extends RuntimeException {
        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
