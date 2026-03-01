package com.bencodez.gravestonesplus.storage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.NumberConversions;

import com.bencodez.gravestonesplus.graves.GraveDisplayType;

import lombok.Getter;
import lombok.Setter;

/**
 * Stores a single grave entry for persistence.
 */
public class GravesConfig implements ConfigurationSerializable {

	@Getter
	private HashMap<Integer, ItemStack> items;

	@Getter
	private int exp;

	@Getter
	private Location location;

	@Getter
	private UUID uuid;

	@Getter
	private String playerName;

	@Getter
	private String deathMessage;

	@Getter
	private long time;

	@Getter
	@Setter
	private boolean destroyed;

	@Getter
	@Setter
	private long destroyedTime;

	@Getter
	@Setter
	private UUID displayUUID;

	@Getter
	@Setter
	private UUID interactUUID;

	/**
	 * Display type used when this grave was created. Stored as a string for config
	 * serialization stability.
	 */
	@Getter
	@Setter
	private String graveDisplayType;

	/**
	 * Full constructor.
	 *
	 * @param uuid             UUID
	 * @param playerName       Player name
	 * @param loc              Location
	 * @param items            Items
	 * @param exp              Experience
	 * @param deathMessage     Death message
	 * @param time             Time
	 * @param destroyed        Destroyed flag
	 * @param destroyedTime    Destroyed time
	 * @param displayUUID      Display entity UUID
	 * @param interactUUID     Interact entity UUID
	 * @param graveDisplayType Display type name
	 */
	public GravesConfig(UUID uuid, String playerName, Location loc, HashMap<Integer, ItemStack> items, int exp,
			String deathMessage, long time, boolean destroyed, long destroyedTime, UUID displayUUID, UUID interactUUID,
			String graveDisplayType) {
		this.uuid = uuid;
		this.location = loc;
		this.items = items != null ? items : new HashMap<Integer, ItemStack>();
		this.exp = exp;
		this.playerName = playerName;
		this.deathMessage = deathMessage;
		this.time = time;
		this.destroyed = destroyed;
		this.destroyedTime = destroyedTime;
		this.displayUUID = displayUUID;
		this.interactUUID = interactUUID;
		this.graveDisplayType = normalizeDisplayType(graveDisplayType, displayUUID);
	}

	@Override
	public Map<String, Object> serialize() {
		Map<String, Object> serialized = new HashMap<String, Object>();

		serialized.put("DeathMessage", deathMessage);
		serialized.put("Time", time);
		serialized.put("UUID", uuid.toString());

		if (displayUUID != null) {
			serialized.put("DisplayUUID", displayUUID.toString());
		}
		if (interactUUID != null) {
			serialized.put("InteractUUID", interactUUID.toString());
		}

		serialized.put("PlayerName", playerName);
		serialized.put("World", location.getWorld().getUID().toString());
		serialized.put("X", location.getBlockX());
		serialized.put("Y", location.getBlockY());
		serialized.put("Z", location.getBlockZ());
		serialized.put("EXP", exp);
		serialized.put("Destroyed", destroyed);
		serialized.put("DestroyedTime", destroyedTime);

		// New: store the display type used for this grave
		serialized.put("GraveDisplayType", graveDisplayType);

		// New primary format
		try {
			serialized.put("ItemsEncoded", InventorySerializer.encode(items));
		} catch (IOException e) {
			// Fallback: if encoding fails, write legacy format so data is not lost
			serialized.put("Items", items);
		}

		return serialized;
	}

	@SuppressWarnings("unchecked")
	public static GravesConfig deserialize(Map<String, Object> deserialize) {
		Object displayObj = deserialize.get("DisplayUUID");
		String displayStr = displayObj != null ? displayObj.toString() : null;

		Object interactObj = deserialize.get("InteractUUID");
		String interactStr = interactObj != null ? interactObj.toString() : null;

		UUID displayUUID = displayStr != null && !displayStr.isEmpty() ? UUID.fromString(displayStr) : null;
		UUID interactUUID = interactStr != null && !interactStr.isEmpty() ? UUID.fromString(interactStr) : null;

		UUID uuid = UUID.fromString(deserialize.get("UUID").toString());
		String playerName = deserialize.get("PlayerName").toString();

		World world = Bukkit.getWorld(UUID.fromString(deserialize.get("World").toString()));
		Location loc = new Location(world, NumberConversions.toInt(deserialize.get("X")),
				NumberConversions.toInt(deserialize.get("Y")), NumberConversions.toInt(deserialize.get("Z")));

		int exp = NumberConversions.toInt(deserialize.get("EXP"));
		String deathMessage = (deserialize.get("DeathMessage") != null ? deserialize.get("DeathMessage").toString()
				: "");
		long time = NumberConversions.toLong(deserialize.get("Time"));
		boolean destroyed = Boolean
				.valueOf((deserialize.get("Destroyed") != null ? deserialize.get("Destroyed").toString() : "false"));
		long destroyedTime = NumberConversions.toLong(deserialize.get("DestroyedTime"));

		// New: display type (fallback inference for older saves)
		String displayTypeStr = null;
		Object dtObj = deserialize.get("GraveDisplayType");
		if (dtObj != null) {
			displayTypeStr = dtObj.toString();
		}

		HashMap<Integer, ItemStack> items = new HashMap<Integer, ItemStack>();

		// Preferred: encoded first
		Object encodedObj = deserialize.get("ItemsEncoded");
		if (encodedObj != null) {
			String encoded = encodedObj.toString();
			if (!encoded.isEmpty()) {
				try {
					items = InventorySerializer.decode(encoded);
				} catch (IOException e) {
					// ignore and fall back below
				}
			}
		}

		// Fallback: legacy map
		if (items == null || items.isEmpty()) {
			Object legacyItems = deserialize.get("Items");
			if (legacyItems instanceof Map) {
				try {
					items = (HashMap<Integer, ItemStack>) legacyItems;
				} catch (Exception e) {
					items = new HashMap<Integer, ItemStack>();
				}
			} else if (legacyItems instanceof String) {
				// Extra fallback: if some older DB wrote encoded string under "Items"
				try {
					items = InventorySerializer.decode((String) legacyItems);
				} catch (IOException e) {
					items = new HashMap<Integer, ItemStack>();
				}
			}
		}

		return new GravesConfig(uuid, playerName, loc, items, exp, deathMessage, time, destroyed, destroyedTime,
				displayUUID, interactUUID, normalizeDisplayType(displayTypeStr, displayUUID));
	}

	/**
	 * Normalizes and validates a display type string. If missing or invalid,
	 * attempts to infer from stored UUIDs for backwards compatibility.
	 *
	 * @param value       Raw stored value
	 * @param displayUUID Display entity UUID
	 * @return Valid enum name
	 */
	private static String normalizeDisplayType(String value, UUID displayUUID) {
		// Backwards compatibility inference
		if (value == null || value.trim().isEmpty()) {
			if (displayUUID != null) {
				return GraveDisplayType.DISPLAY_ENTITY.name();
			}
			return GraveDisplayType.PLAYER_HEAD.name();
		}

		String normalized = value.trim().toUpperCase();

		try {
			return GraveDisplayType.valueOf(normalized).name();
		} catch (Exception e) {
			// Unknown value in storage, fallback
			if (displayUUID != null) {
				return GraveDisplayType.DISPLAY_ENTITY.name();
			}
			return GraveDisplayType.PLAYER_HEAD.name();
		}
	}
}