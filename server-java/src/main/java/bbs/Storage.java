package bbs;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class Storage {
    private final String dbUrl;

    public Storage(String dbPath) {
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();

        if (parent != null) {
            parent.mkdirs();
        }

        this.dbUrl = "jdbc:sqlite:" + dbPath;
        ensureDatabase();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void ensureDatabase() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS logins (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    login_timestamp INTEGER NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS channels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id TEXT,
                    username TEXT NOT NULL,
                    channel_name TEXT NOT NULL,
                    message_text TEXT NOT NULL,
                    sent_timestamp INTEGER NOT NULL
                )
            """);

            addColumnIfMissing(conn, "messages", "request_id", "TEXT");

            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_request_id
                ON messages(request_id)
            """);

        } catch (SQLException e) {
            throw new RuntimeException("Erro ao inicializar banco: " + e.getMessage(), e);
        }
    }

    private void addColumnIfMissing(
            Connection conn,
            String tableName,
            String columnName,
            String columnType
    ) throws SQLException {
        boolean exists = false;

        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String existingColumn = rs.getString("name");

                if (existingColumn.equalsIgnoreCase(columnName)) {
                    exists = true;
                    break;
                }
            }
        }

        if (!exists) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType);
            }
        }
    }

    public void saveLogin(String username, long loginTimestamp) {
        String sql = "INSERT INTO logins (username, login_timestamp) VALUES (?, ?)";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ps.setLong(2, loginTimestamp);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Erro ao salvar login: " + e.getMessage(), e);
        }
    }

    public boolean channelExists(String channelName) {
        String sql = "SELECT 1 FROM channels WHERE name = ? LIMIT 1";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, channelName);

            ResultSet rs = ps.executeQuery();

            return rs.next();

        } catch (SQLException e) {
            throw new RuntimeException("Erro ao verificar canal: " + e.getMessage(), e);
        }
    }

    public boolean createChannel(String channelName, long createdAt) {
        String sql = "INSERT OR IGNORE INTO channels (name, created_at) VALUES (?, ?)";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, channelName);
            ps.setLong(2, createdAt);

            int affectedRows = ps.executeUpdate();

            return affectedRows > 0;

        } catch (SQLException e) {
            throw new RuntimeException("Erro ao criar canal: " + e.getMessage(), e);
        }
    }

    public List<String> listChannels() {
        String sql = "SELECT name FROM channels ORDER BY name ASC";
        List<String> channels = new ArrayList<>();

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                channels.add(rs.getString("name"));
            }

            return channels;

        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar canais: " + e.getMessage(), e);
        }
    }

    public void saveMessage(
            String username,
            String channelName,
            String messageText,
            long sentTimestamp
    ) {
        saveMessage(username, channelName, messageText, sentTimestamp, null);
    }

    public void saveMessage(
            String username,
            String channelName,
            String messageText,
            long sentTimestamp,
            String requestId
    ) {
        String sql = """
            INSERT OR IGNORE INTO messages (
                request_id,
                username,
                channel_name,
                message_text,
                sent_timestamp
            )
            VALUES (?, ?, ?, ?, ?)
        """;

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            if (requestId == null || requestId.isBlank()) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, requestId);
            }

            ps.setString(2, username);
            ps.setString(3, channelName);
            ps.setString(4, messageText);
            ps.setLong(5, sentTimestamp);

            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Erro ao salvar mensagem: " + e.getMessage(), e);
        }
    }
}