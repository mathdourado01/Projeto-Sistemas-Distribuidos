package bbs;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class Storage {
    private final String dbUrl;

    public Storage(String dbPath) {
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
                    username TEXT NOT NULL,
                    channel_name TEXT NOT NULL,
                    message_text TEXT NOT NULL,
                    sent_timestamp INTEGER NOT NULL
                )
            """);

        } catch (SQLException e) {
            throw new RuntimeException("Erro ao inicializar banco: " + e.getMessage(), e);
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
        if (channelExists(channelName)) {
            return false;
        }

        String sql = "INSERT INTO channels (name, created_at) VALUES (?, ?)";

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, channelName);
            ps.setLong(2, createdAt);
            ps.executeUpdate();
            return true;
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

    public void saveMessage(String username, String channelName, String messageText, long sentTimestamp) {
        String sql = """
            INSERT INTO messages (username, channel_name, message_text, sent_timestamp)
            VALUES (?, ?, ?, ?)
        """;

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, channelName);
            ps.setString(3, messageText);
            ps.setLong(4, sentTimestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao salvar mensagem: " + e.getMessage(), e);
        }
    }
}