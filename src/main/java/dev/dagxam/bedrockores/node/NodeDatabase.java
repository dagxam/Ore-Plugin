package dev.dagxam.bedrockores.node;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** SQLite-хранилище. Bukkit API здесь не используется. */
final class NodeDatabase implements AutoCloseable {
    private final Connection connection;

    NodeDatabase(Plugin plugin) throws SQLException {
        File file = new File(plugin.getDataFolder(), "nodes.db");
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("CREATE TABLE IF NOT EXISTS nodes (world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,type TEXT NOT NULL,hits INTEGER NOT NULL,max_hits INTEGER NOT NULL,PRIMARY KEY(world,x,y,z))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_nodes_chunk ON nodes(world,(x >> 4),(z >> 4))");
            st.execute("CREATE TABLE IF NOT EXISTS respawns (world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,type TEXT NOT NULL,due_at INTEGER NOT NULL,PRIMARY KEY(world,x,y,z))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_respawns_chunk ON respawns(world,(x >> 4),(z >> 4))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_respawns_due ON respawns(due_at)");
            st.execute("CREATE TABLE IF NOT EXISTS processed_chunks (world TEXT NOT NULL,x INTEGER NOT NULL,z INTEGER NOT NULL,PRIMARY KEY(world,x,z))");
        }
    }

    List<NodeManager.NodeEntry> loadNodes(UUID world, int cx, int cz) throws SQLException {
        List<NodeManager.NodeEntry> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT x,y,z,type,hits,max_hits FROM nodes WHERE world=? AND (x >> 4)=? AND (z >> 4)=?")) {
            ps.setString(1, world.toString()); ps.setInt(2, cx); ps.setInt(3, cz);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new NodeManager.NodeEntry(world, rs.getInt(1), rs.getInt(2), rs.getInt(3), Material.valueOf(rs.getString(4)), rs.getInt(5), rs.getInt(6)));
            }
        }
        return out;
    }

    List<NodeManager.RespawnEntry> loadDueRespawns(UUID world, int cx, int cz, long now) throws SQLException {
        List<NodeManager.RespawnEntry> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT x,y,z,type,due_at FROM respawns WHERE world=? AND (x >> 4)=? AND (z >> 4)=? AND due_at<=?")) {
            ps.setString(1, world.toString()); ps.setInt(2, cx); ps.setInt(3, cz); ps.setLong(4, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new NodeManager.RespawnEntry(world, rs.getInt(1), rs.getInt(2), rs.getInt(3), Material.valueOf(rs.getString(4)), rs.getLong(5)));
            }
        }
        return out;
    }

    boolean isProcessed(UUID world, int x, int z) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM processed_chunks WHERE world=? AND x=? AND z=?")) {
            ps.setString(1, world.toString()); ps.setInt(2, x); ps.setInt(3, z);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    void markProcessed(UUID world, int x, int z) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO processed_chunks(world,x,z) VALUES(?,?,?)")) {
            ps.setString(1, world.toString()); ps.setInt(2, x); ps.setInt(3, z); ps.executeUpdate();
        }
    }

    void saveDirty(Collection<NodeManager.NodeEntry> upsertNodes, Collection<NodeManager.NodeEntry> deleteNodes,
                   Collection<NodeManager.RespawnEntry> upsertRespawns, Collection<NodeManager.RespawnEntry> deleteRespawns,
                   Collection<NodeManager.ChunkEntry> processed) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement up = connection.prepareStatement("INSERT INTO nodes(world,x,y,z,type,hits,max_hits) VALUES(?,?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET type=excluded.type,hits=excluded.hits,max_hits=excluded.max_hits");
                 PreparedStatement del = connection.prepareStatement("DELETE FROM nodes WHERE world=? AND x=? AND y=? AND z=?")) {
                for (NodeManager.NodeEntry e : upsertNodes) { up.setString(1,e.world().toString()); up.setInt(2,e.x()); up.setInt(3,e.y()); up.setInt(4,e.z()); up.setString(5,e.type().name()); up.setInt(6,e.hits()); up.setInt(7,e.maxHits()); up.addBatch(); }
                for (NodeManager.NodeEntry e : deleteNodes) { del.setString(1,e.world().toString()); del.setInt(2,e.x()); del.setInt(3,e.y()); del.setInt(4,e.z()); del.addBatch(); }
                up.executeBatch(); del.executeBatch();
            }
            try (PreparedStatement up = connection.prepareStatement("INSERT INTO respawns(world,x,y,z,type,due_at) VALUES(?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET type=excluded.type,due_at=excluded.due_at");
                 PreparedStatement del = connection.prepareStatement("DELETE FROM respawns WHERE world=? AND x=? AND y=? AND z=?")) {
                for (NodeManager.RespawnEntry e : upsertRespawns) { up.setString(1,e.world().toString()); up.setInt(2,e.x()); up.setInt(3,e.y()); up.setInt(4,e.z()); up.setString(5,e.type().name()); up.setLong(6,e.dueAtMillis()); up.addBatch(); }
                for (NodeManager.RespawnEntry e : deleteRespawns) { del.setString(1,e.world().toString()); del.setInt(2,e.x()); del.setInt(3,e.y()); del.setInt(4,e.z()); del.addBatch(); }
                up.executeBatch(); del.executeBatch();
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO processed_chunks(world,x,z) VALUES(?,?,?)")) {
                for (NodeManager.ChunkEntry e : processed) { ps.setString(1,e.world().toString()); ps.setInt(2,e.x()); ps.setInt(3,e.z()); ps.addBatch(); }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException ex) { connection.rollback(); throw ex; } finally { connection.setAutoCommit(true); }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
