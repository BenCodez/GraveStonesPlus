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
		init();
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
		if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getLocation().getWorld() == null) {
			return;
		}

		String sql = "INSERT INTO " + qi(tableName)
				+ " (broken, player_uuid, player_name, death_message, time, exp, destroyed, destroyed_time, world_uuid, x, y, z, display_uuid, interact_uuid, items_encoded)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
				+ " ON DUPLICATE KEY UPDATE "
				+ qi("broken") + "=VALUES(" + qi("broken") + "), "
				+ qi("player_name") + "=VALUES(" + qi("player_name") + "), "
				+ qi("death_message") + "=VALUES(" + qi("death_message") + "), "
				+ qi("exp") + "=VALUES(" + qi("exp") + "), "
				+ qi("destroyed") + "=VALUES(" + qi("destroyed") + "), "
				+ qi("destroyed_time") + "=VALUES(" + qi("destroyed_time") + "), "
				+ qi("display_uuid") + "=VALUES(" + qi("display_uuid") + "), "
				+ qi("interact_uuid") + "=VALUES(" + qi("interact_uuid") + "), "
				+ qi("items_encoded") + "=VALUES(" + qi("items_encoded") + ");";

		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {

			bindInsert(ps, grave, broken);
			ps.executeUpdate();

		} catch (SQLException e) {
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
	if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getLocation().getWorld() == null) {
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
			+ qi("items_encoded") + "=? WHERE "
			+ qi("player_uuid") + "=? AND "
			+ qi("world_uuid") + "=? AND "
			+ qi("x") + "=? AND "
			+ qi("y") + "=? AND "
			+ qi("z") + "=? AND "
			+ qi("time") + "=?;";

	try (Connection conn = mysql.getConnectionManager().getConnection();
			PreparedStatement ps = conn.prepareStatement(sql)) {

		ps.setInt(1, broken ? 1 : 0);
		ps.setString(2, safeString(grave.getPlayerName()));
		ps.setString(3, safeString(grave.getDeathMessage()));
		ps.setInt(4, grave.getExp());
		ps.setInt(5, grave.isDestroyed() ? 1 : 0);
		ps.setLong(6, grave.getDestroyedTime());
		ps.setString(7, grave.getDisplayUUID() != null ? grave.getDisplayUUID().toString() : null);
		ps.setString(8, grave.getInteractUUID() != null ? grave.getInteractUUID().toString() : null);
		ps.setString(9, encodeItemsSafe(grave.getItems()));

		ps.setString(10, grave.getUuid().toString());
		ps.setString(11, grave.getLocation().getWorld().getUID().toString());
		ps.setInt(12, grave.getLocation().getBlockX());
		ps.setInt(13, grave.getLocation().getBlockY());
		ps.setInt(14, grave.getLocation().getBlockZ());
		ps.setLong(15, grave.getTime());

		ps.executeUpdate();

	} catch (SQLException e) {
		debug(e);
	}
}



	/**
	 * Update only the broken flag for a grave (incremental write).
	 *
	 * @param grave grave config
	 * @param broken new broken flag
	 */
	public void setBroken(GravesConfig grave, boolean broken) {
		if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getLocation().getWorld() == null) {
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

			ps.setInt(1, broken ? 1 : 0);
			ps.setString(2, grave.getUuid().toString());
			ps.setString(3, grave.getLocation().getWorld().getUID().toString());
			ps.setInt(4, grave.getLocation().getBlockX());
			ps.setInt(5, grave.getLocation().getBlockY());
			ps.setInt(6, grave.getLocation().getBlockZ());
			ps.setLong(7, grave.getTime());

			ps.executeUpdate();

		} catch (SQLException e) {
			debug(e);
		}
	}

	/**
	 * Delete a grave row by identity.
	 *
	 * @param grave grave config
	 */
	public void deleteGrave(GravesConfig grave) {
		if (grave == null || grave.getUuid() == null || grave.getLocation() == null || grave.getLocation().getWorld() == null) {
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

			ps.setString(1, grave.getUuid().toString());
			ps.setString(2, grave.getLocation().getWorld().getUID().toString());
			ps.setInt(3, grave.getLocation().getBlockX());
			ps.setInt(4, grave.getLocation().getBlockY());
			ps.setInt(5, grave.getLocation().getBlockZ());
			ps.setLong(6, grave.getTime());

			ps.executeUpdate();

		} catch (SQLException e) {
			debug(e);
		}
	}

	/**
	 * Load all graves for one type (broken or not).
	 *
	 * @param broken broken flag
	 * @return list of graves
	 */
	public List<GravesConfig> loadAll(boolean broken) {
		List<GravesConfig> out = new ArrayList<GravesConfig>();

		String sql = "SELECT * FROM " + qi(tableName) + " WHERE " + qi("broken") + "=?;";
		try (Connection conn = mysql.getConnectionManager().getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {

			ps.setInt(1, broken ? 1 : 0);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					UUID playerUuid = safeUuid(rs.getString("player_uuid"));
					if (playerUuid == null) {
						continue;
					}

					UUID worldUuid = safeUuid(rs.getString("world_uuid"));
					World world = worldUuid != null ? Bukkit.getWorld(worldUuid) : null;
					if (world == null) {
						continue;
					}

					Location loc = new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));

					HashMap<Integer, ItemStack> items = decodeItemsSafe(rs.getString("items_encoded"));

					String playerName = rs.getString("player_name");
					String deathMessage = rs.getString("death_message");
					long time = rs.getLong("time");
					int exp = rs.getInt("exp");
					boolean destroyed = rs.getInt("destroyed") == 1;
					long destroyedTime = rs.getLong("destroyed_time");

					UUID display = safeUuid(rs.getString("display_uuid"));
					UUID interact = safeUuid(rs.getString("interact_uuid"));

					out.add(new GravesConfig(playerUuid,
							playerName != null ? playerName : "",
							loc,
							items,
							exp,
							deathMessage != null ? deathMessage : "",
							time,
							destroyed,
							destroyedTime,
							display,
							interact));
				}
			}

		} catch (SQLException e) {
			debug(e);
		}

		return out;
	}

	private void bindInsert(PreparedStatement ps, GravesConfig g, boolean broken) throws SQLException {
		ps.setInt(1, broken ? 1 : 0);
		ps.setString(2, g.getUuid().toString());
		ps.setString(3, safeString(g.getPlayerName()));
		ps.setString(4, safeString(g.getDeathMessage()));
		ps.setLong(5, g.getTime());
		ps.setInt(6, g.getExp());
		ps.setInt(7, g.isDestroyed() ? 1 : 0);
		ps.setLong(8, g.getDestroyedTime());
		ps.setString(9, g.getLocation().getWorld().getUID().toString());
		ps.setInt(10, g.getLocation().getBlockX());
		ps.setInt(11, g.getLocation().getBlockY());
		ps.setInt(12, g.getLocation().getBlockZ());
		ps.setString(13, g.getDisplayUUID() != null ? g.getDisplayUUID().toString() : null);
		ps.setString(14, g.getInteractUUID() != null ? g.getInteractUUID().toString() : null);
		ps.setString(15, encodeItemsSafe(g.getItems()));
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

	private static String encodeItemsSafe(HashMap<Integer, ItemStack> items) {
		try {
			return InventorySerializer.encode(items);
		} catch (IOException e) {
			return "";
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