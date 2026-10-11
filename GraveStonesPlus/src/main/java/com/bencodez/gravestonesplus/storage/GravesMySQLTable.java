package com.bencodez.gravestonesplus.storage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.mysql.AbstractSqlTable;
import com.bencodez.simpleapi.sql.mysql.DbType;
import com.bencodez.simpleapi.sql.mysql.config.MysqlConfigSpigot;

/**
 * MySQL table for storing graves.
 *
 * Supports incremental updates (UPSERT) rather than rewriting all rows.
 *
 * Identity key is:
 * player_uuid + world_uuid + x + y + z + time
 */
public class GravesMySQLTable extends AbstractSqlTable {

	private final AdvancedCorePlugin plugin;

	/**
	 * Construct the table wrapper.
	 *
	 * @param plugin plugin
	 * @param tableName table name
	 * @param databaseSection Database section from config
	 */
	public GravesMySQLTable(AdvancedCorePlugin plugin, String tableName, ConfigurationSection databaseSection) {
		super(tableName, new MysqlConfigSpigot(databaseSection), plugin.getOptions().getDebug().isDebug(), true);
		this.plugin = plugin;
		try { init(); }
        catch (RuntimeException | Error failure) {
            // A failed constructor cannot publish this table to the backend owner.
            try { closeOwnedPool(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
	}

    @Override public void close() { closeOwnedPool(); }

    private void closeOwnedPool() {
        try { super.close(); }
        catch (RuntimeException | Error failure) {
            // Older SimpleAPI versions skip worker retirement if pool close throws.
            // Keep this table safe without requiring a synchronized dependency upgrade.
            try { if (mysql.getThreadPool() != null) mysql.getThreadPool().shutdown(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

	@Override
	public String getPrimaryKeyColumn() {
		return "id";
	}

	@Override
	public String buildCreateTableSql(DbType dbType) {
		StringBuilder sb = new StringBuilder();
		sb.append("CREATE TABLE IF NOT EXISTS ").append(qi(tableName)).append(" (");
		sb.append(qi("id")).append(" BIGINT AUTO_INCREMENT, ");
		sb.append(qi("broken")).append(" TINYINT(1) NOT NULL, ");
		sb.append(qi("player_uuid")).append(" VARCHAR(36) NOT NULL, ");
		sb.append(qi("player_name")).append(" VARCHAR(64) NOT NULL, ");
		sb.append(qi("death_message")).append(" TEXT, ");
		sb.append(qi("time")).append(" BIGINT NOT NULL, ");
		sb.append(qi("exp")).append(" INT NOT NULL, ");
		sb.append(qi("destroyed")).append(" TINYINT(1) NOT NULL, ");
		sb.append(qi("destroyed_time")).append(" BIGINT NOT NULL, ");
		sb.append(qi("world_uuid")).append(" VARCHAR(36) NOT NULL, ");
		sb.append(qi("x")).append(" INT NOT NULL, ");
		sb.append(qi("y")).append(" INT NOT NULL, ");
		sb.append(qi("z")).append(" INT NOT NULL, ");
		sb.append(qi("display_uuid")).append(" VARCHAR(36), ");
		sb.append(qi("interact_uuid")).append(" VARCHAR(36), ");
		// NEW: store which display type was used (PLAYER_HEAD / DISPLAY_ENTITY / CHEST)
		sb.append(qi("grave_display_type")).append(" VARCHAR(32), ");
		sb.append(qi("items_encoded")).append(" MEDIUMTEXT NOT NULL, ");
		sb.append("PRIMARY KEY (").append(qi("id")).append("), ");
		sb.append("INDEX ").append(qi("idx_player_uuid")).append(" (").append(qi("player_uuid")).append("), ");
		sb.append("INDEX ").append(qi("idx_broken")).append(" (").append(qi("broken")).append("), ");
		sb.append("UNIQUE KEY ").append(qi("uk_grave_identity")).append(" (")
				.append(qi("player_uuid")).append(",")
				.append(qi("world_uuid")).append(",")
				.append(qi("x")).append(",")
				.append(qi("y")).append(",")
				.append(qi("z")).append(",")
				.append(qi("time")).append(")");
		sb.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
		return sb.toString();
	}

	@Override
	public void logSevere(String msg) {
		plugin.getLogger().severe(msg);
	}

	@Override
	public void logInfo(String msg) {
		plugin.getLogger().info(msg);
	}

	@Override
	public void debug(Throwable t) {
		plugin.debug(t);
	}

	@Override
	public void debug(String msg) {
		plugin.debug(msg);
	}

	/**
	 * Insert or update a single grave row (incremental write).
	 * Uses the unique identity key:
	 * player_uuid + world_uuid + x + y + z + time.
	 *
	 * @param grave grave config
	 * @param broken whether grave is broken
	 */
	public void upsertGrave(GravesConfig grave, boolean broken) {
        upsertGrave(grave, broken, false);
    }

    /** Internal backend acknowledgement; legacy direct callers retain logging-only failures. */
    void upsertGraveChecked(GravesConfig grave, boolean broken) {
        upsertGrave(grave, broken, true);
    }

    private void upsertGrave(GravesConfig grave, boolean broken, boolean checked) {
		if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getWorldId() == null) {
            if (checked) throw new IllegalArgumentException("Grave write requires a complete identity");
			return;
		}

		String sql = "INSERT INTO " + qi(tableName)
				+ " (broken, player_uuid, player_name, death_message, time, exp, destroyed, destroyed_time, world_uuid, x, y, z, display_uuid, interact_uuid, grave_display_type, items_encoded)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
				+ " ON DUPLICATE KEY UPDATE "
				+ qi("broken") + "=VALUES(" + qi("broken") + "), "
				+ qi("player_name") + "=VALUES(" + qi("player_name") + "), "
				+ qi("death_message") + "=VALUES(" + qi("death_message") + "), "
				+ qi("exp") + "=VALUES(" + qi("exp") + "), "
				+ qi("destroyed") + "=VALUES(" + qi("destroyed") + "), "
				+ qi("destroyed_time") + "=VALUES(" + qi("destroyed_time") + "), "
				+ qi("display_uuid") + "=VALUES(" + qi("display_uuid") + "), "
				+ qi("interact_uuid") + "=VALUES(" + qi("interact_uuid") + "), "
				+ qi("grave_display_type") + "=VALUES(" + qi("grave_display_type") + "), "
				+ qi("items_encoded") + "=VALUES(" + qi("items_encoded") + ");";

		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
            if (checked && !conn.getAutoCommit()) throw new SQLException("Grave write requires an auto-commit connection");

			bindInsert(ps, grave, broken);
			ps.executeUpdate();

		} catch (SQLException e) {
            if (checked) throw new IllegalStateException("Grave SQL write was not acknowledged", e);
			debug(e);
		}
	}

	/**
	 * Update an existing grave row (no insert). Intended for state transitions such as removal.
	 * If the row does not exist, this method does nothing.
	 *
	 * @param grave grave config
	 * @param broken whether grave is broken
	 */
	public void updateExistingGrave(GravesConfig grave, boolean broken) {
        updateExistingGrave(grave, broken, false);
    }

    /** Internal backend acknowledgement; legacy direct callers retain logging-only failures. */
    void updateExistingGraveChecked(GravesConfig grave, boolean broken) {
        updateExistingGrave(grave, broken, true);
    }

    private void updateExistingGrave(GravesConfig grave, boolean broken, boolean checked) {
		if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getWorldId() == null) {
            if (checked) throw new IllegalArgumentException("Grave write requires a complete identity");
			return;
		}

		String sql = "UPDATE " + qi(tableName) + " SET "
				+ qi("broken") + "=?, "
				+ qi("player_name") + "=?, "
				+ qi("death_message") + "=?, "
				+ qi("exp") + "=?, "
				+ qi("destroyed") + "=?, "
				+ qi("destroyed_time") + "=?, "
				+ qi("display_uuid") + "=?, "
				+ qi("interact_uuid") + "=?, "
				+ qi("grave_display_type") + "=?, "
				+ qi("items_encoded") + "=? WHERE "
				+ qi("player_uuid") + "=? AND "
				+ qi("world_uuid") + "=? AND "
				+ qi("x") + "=? AND "
				+ qi("y") + "=? AND "
				+ qi("z") + "=? AND "
				+ qi("time") + "=?;";

		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
            if (checked && !conn.getAutoCommit()) throw new SQLException("Grave write requires an auto-commit connection");

			ps.setInt(1, broken ? 1 : 0);
			ps.setString(2, safeString(grave.getPlayerName()));
			ps.setString(3, safeString(grave.getDeathMessage()));
			ps.setInt(4, grave.getExp());
			ps.setInt(5, grave.isDestroyed() ? 1 : 0);
			ps.setLong(6, grave.getDestroyedTime());
			ps.setString(7, grave.getDisplayUUID() != null ? grave.getDisplayUUID().toString() : null);
			ps.setString(8, grave.getInteractUUID() != null ? grave.getInteractUUID().toString() : null);
			ps.setString(9, safeString(grave.getGraveDisplayType()));
			ps.setString(10, grave.encodedItemsForStorage());

			ps.setString(11, grave.getUuid().toString());
			ps.setString(12, grave.getWorldId().toString());
			ps.setInt(13, grave.getLocation().getBlockX());
			ps.setInt(14, grave.getLocation().getBlockY());
			ps.setInt(15, grave.getLocation().getBlockZ());
			ps.setLong(16, grave.getTime());

			ps.executeUpdate();

		} catch (SQLException e) {
            if (checked) throw new IllegalStateException("Grave SQL write was not acknowledged", e);
			debug(e);
		}
	}

    /** Update only display identities on this exact still-active record. */
    public void saveDisplayIdentity(GravesConfig grave) {
        if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getWorldId() == null)
            throw new IllegalArgumentException("Display repair requires an existing grave identity");
        String sql = "UPDATE " + qi(tableName) + " SET " + qi("display_uuid") + "=?, "
                + qi("interact_uuid") + "=? WHERE " + qi("player_uuid") + "=? AND "
                + qi("world_uuid") + "=? AND " + qi("x") + "=? AND " + qi("y") + "=? AND "
                + qi("z") + "=? AND " + qi("time") + "=? AND " + qi("broken") + "=0 AND " + qi("destroyed") + "=0;";
        try (Connection connection = mysql.getConnectionManager().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!connection.getAutoCommit()) throw new SQLException("Display repair requires an auto-commit connection");
            statement.setString(1, grave.getDisplayUUID() == null ? null : grave.getDisplayUUID().toString());
            statement.setString(2, grave.getInteractUUID() == null ? null : grave.getInteractUUID().toString());
            statement.setString(3, grave.getUuid().toString()); statement.setString(4, grave.getWorldId().toString());
            statement.setInt(5, grave.getLocation().getBlockX()); statement.setInt(6, grave.getLocation().getBlockY());
            statement.setInt(7, grave.getLocation().getBlockZ()); statement.setLong(8, grave.getTime());
            if (statement.executeUpdate() != 1) throw new SQLException("Active grave display identity was not acknowledged");
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to persist grave display identity", failure);
        }
    }

    /** Restore existing metadata with one checked statement; contents/XP are unchanged. */
    public void saveRecreatedGrave(GravesConfig grave) {
        if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getWorldId() == null)
            throw new IllegalArgumentException("Recreation requires an existing grave identity");
        String sql = "UPDATE " + qi(tableName) + " SET " + qi("broken") + "=?, "
                + qi("destroyed") + "=?, " + qi("destroyed_time") + "=?, "
                + qi("display_uuid") + "=?, " + qi("interact_uuid") + "=?, "
                + qi("grave_display_type") + "=? WHERE " + qi("player_uuid") + "=? AND "
                + qi("world_uuid") + "=? AND " + qi("x") + "=? AND " + qi("y") + "=? AND "
                + qi("z") + "=? AND " + qi("time") + "=?;";
        try (Connection connection = mysql.getConnectionManager().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!connection.getAutoCommit()) throw new SQLException("Recreation requires an auto-commit connection");
            statement.setInt(1, 0);
            statement.setInt(2, grave.isDestroyed() ? 1 : 0);
            statement.setLong(3, grave.getDestroyedTime());
            statement.setString(4, grave.getDisplayUUID() == null ? null : grave.getDisplayUUID().toString());
            statement.setString(5, grave.getInteractUUID() == null ? null : grave.getInteractUUID().toString());
            statement.setString(6, safeString(grave.getGraveDisplayType()));
            statement.setString(7, grave.getUuid().toString());
            statement.setString(8, grave.getWorldId().toString());
            statement.setInt(9, grave.getLocation().getBlockX());
            statement.setInt(10, grave.getLocation().getBlockY());
            statement.setInt(11, grave.getLocation().getBlockZ());
            statement.setLong(12, grave.getTime());
            if (statement.executeUpdate() != 1) throw new SQLException("Existing grave row was not acknowledged");
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to persist recreated grave", failure);
        }
    }

	/**
	 * Update only the broken flag for a grave (incremental write).
	 *
	 * @param grave grave config
	 * @param broken new broken flag
	 */
	public void setBroken(GravesConfig grave, boolean broken) {
        setBroken(grave, broken, false);
    }

    /** Internal backend acknowledgement; legacy direct callers retain logging-only failures. */
    void setBrokenChecked(GravesConfig grave, boolean broken) {
        setBroken(grave, broken, true);
    }

    private void setBroken(GravesConfig grave, boolean broken, boolean checked) {
		if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getWorldId() == null) {
            if (checked) throw new IllegalArgumentException("Grave write requires a complete identity");
			return;
		}

		String sql = "UPDATE " + qi(tableName) + " SET " + qi("broken") + "=? WHERE "
				+ qi("player_uuid") + "=? AND "
				+ qi("world_uuid") + "=? AND "
				+ qi("x") + "=? AND "
				+ qi("y") + "=? AND "
				+ qi("z") + "=? AND "
				+ qi("time") + "=?;";

		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
            if (checked && !conn.getAutoCommit()) throw new SQLException("Grave write requires an auto-commit connection");

			ps.setInt(1, broken ? 1 : 0);
			ps.setString(2, grave.getUuid().toString());
			ps.setString(3, grave.getWorldId().toString());
			ps.setInt(4, grave.getLocation().getBlockX());
			ps.setInt(5, grave.getLocation().getBlockY());
			ps.setInt(6, grave.getLocation().getBlockZ());
			ps.setLong(7, grave.getTime());

			ps.executeUpdate();

		} catch (SQLException e) {
            if (checked) throw new IllegalStateException("Grave SQL write was not acknowledged", e);
			debug(e);
		}
	}

	/**
	 * Delete a grave row by identity.
	 *
	 * @param grave grave config
	 */
	public void deleteGrave(GravesConfig grave) {
        deleteGrave(grave, false);
    }

    /** Internal backend acknowledgement; legacy direct callers retain logging-only failures. */
    void deleteGraveChecked(GravesConfig grave) {
        deleteGrave(grave, true);
    }

    private void deleteGrave(GravesConfig grave, boolean checked) {
		if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getWorldId() == null) {
            if (checked) throw new IllegalArgumentException("Grave write requires a complete identity");
			return;
		}

		String sql = "DELETE FROM " + qi(tableName) + " WHERE "
				+ qi("player_uuid") + "=? AND "
				+ qi("world_uuid") + "=? AND "
				+ qi("x") + "=? AND "
				+ qi("y") + "=? AND "
				+ qi("z") + "=? AND "
				+ qi("time") + "=?;";

		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
            if (checked && !conn.getAutoCommit()) throw new SQLException("Grave write requires an auto-commit connection");

			ps.setString(1, grave.getUuid().toString());
			ps.setString(2, grave.getWorldId().toString());
			ps.setInt(3, grave.getLocation().getBlockX());
			ps.setInt(4, grave.getLocation().getBlockY());
			ps.setInt(5, grave.getLocation().getBlockZ());
			ps.setLong(6, grave.getTime());

			ps.executeUpdate();

		} catch (SQLException e) {
            if (checked) throw new IllegalStateException("Grave SQL write was not acknowledged", e);
			debug(e);
		}
	}

	/**
	 * Load all graves for one type (broken or not).
	 *
	 * @param broken broken flag
	 * @return list of graves
	 */
    /** Legacy synchronous API: caller owns Bukkit materialization, never use on the I/O worker. */
    public List<GravesConfig> loadAll(boolean broken) {
        try { return new ArrayList<>(loadDeferred(broken).stream().map(java.util.function.Supplier::get).toList()); }
        catch (java.util.concurrent.CompletionException failure) {
            if (failure.getCause() instanceof SQLException sql) { debug(sql); return new ArrayList<>(); }
            throw failure;
        }
    }
    /** JDBC-only phase. The returned decoder must run on the supported server context. */
    List<java.util.function.Supplier<GravesConfig>> loadDeferred(boolean broken) {
        List<StoredGrave> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + qi(tableName) + " WHERE " + qi("broken") + "=?;";
        try (Connection conn = mysql.getConnectionManager().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, broken ? 1 : 0);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID player = safeUuid(rs.getString("player_uuid")), world = safeUuid(rs.getString("world_uuid"));
                    if (player == null || world == null) continue;
                    rows.add(new StoredGrave(player, world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                            rs.getString("items_encoded"), rs.getString("player_name"), rs.getString("death_message"),
                            rs.getLong("time"), rs.getInt("exp"), rs.getInt("destroyed") == 1,
                            rs.getLong("destroyed_time"), safeUuid(rs.getString("display_uuid")),
                            safeUuid(rs.getString("interact_uuid")), rs.getString("grave_display_type")));
                }
            }
        } catch (SQLException failure) {
            // An incomplete read is unknown, not a successful empty restoration.
            throw new java.util.concurrent.CompletionException(failure);
        }
        return rows.stream().<java.util.function.Supplier<GravesConfig>>map(row -> () -> {
            World world = Bukkit.getWorld(row.world());
            GravesConfig record = new GravesConfig(row.player(), safeString(row.name()),
                    new Location(world, row.x(), row.y(), row.z()), decodeItemsSafe(row.items()),
                    row.exp(), safeString(row.death()), row.time(), row.destroyed(), row.destroyedTime(),
                    row.display(), row.interact(), row.type());
            record.setWorldId(row.world()); return record;
        }).toList();
    }
    private record StoredGrave(UUID player, UUID world, int x, int y, int z, String items, String name,
            String death, long time, int exp, boolean destroyed, long destroyedTime,
            UUID display, UUID interact, String type) {}

	private void bindInsert(PreparedStatement ps, GravesConfig g, boolean broken) throws SQLException {
		ps.setInt(1, broken ? 1 : 0);
		ps.setString(2, g.getUuid().toString());
		ps.setString(3, safeString(g.getPlayerName()));
		ps.setString(4, safeString(g.getDeathMessage()));
		ps.setLong(5, g.getTime());
		ps.setInt(6, g.getExp());
		ps.setInt(7, g.isDestroyed() ? 1 : 0);
		ps.setLong(8, g.getDestroyedTime());
		ps.setString(9, g.getWorldId().toString());
		ps.setInt(10, g.getLocation().getBlockX());
		ps.setInt(11, g.getLocation().getBlockY());
		ps.setInt(12, g.getLocation().getBlockZ());
		ps.setString(13, g.getDisplayUUID() != null ? g.getDisplayUUID().toString() : null);
		ps.setString(14, g.getInteractUUID() != null ? g.getInteractUUID().toString() : null);
		ps.setString(15, safeString(g.getGraveDisplayType()));
		ps.setString(16, g.encodedItemsForStorage());
	}

	private static String safeString(String s) {
		return s != null ? s : "";
	}

	private static UUID safeUuid(String s) {
		if (s == null || s.isEmpty()) {
			return null;
		}
		try {
			return UUID.fromString(s);
		} catch (Exception e) {
			return null;
		}
	}

	private static HashMap<Integer, ItemStack> decodeItemsSafe(String encoded) {
		try {
			return InventorySerializer.decode(encoded);
		} catch (IOException e) {
			return new HashMap<Integer, ItemStack>();
		}
	}
}
