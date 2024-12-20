package com.bencodez.gravestonesplus.graves;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.NumberConversions;

import lombok.Getter;
import lombok.Setter;

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

	public GravesConfig(UUID uuid, String playerName, Location loc, HashMap<Integer, ItemStack> items, int exp,
			String deathMessage, long time, boolean destroyed, long destroyedTime, UUID displayUUID,
			UUID interactUUID) {
		this.uuid = uuid;
		this.location = loc;
		this.items = items;
		this.exp = exp;
		this.playerName = playerName;
		this.deathMessage = deathMessage;
		this.time = time;
		this.destroyed = destroyed;
		this.destroyedTime = destroyedTime;
		this.displayUUID = displayUUID;
		this.interactUUID = interactUUID;
	}

	@Override
	public Map<String, Object> serialize() {
		Map<String, Object> serialized = new HashMap<>();
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
		serialized.put("Items", items);
		serialized.put("EXP", exp);
		serialized.put("Destroyed", destroyed);
		serialized.put("DestroyedTime", destroyedTime);
		return serialized;
	}

	@SuppressWarnings("unchecked")
	public static GravesConfig deserialize(Map<String, Object> deserialize) {
		Object strObj = deserialize.get("DisplayUUID");
		String str = "";
		if (strObj != null) {
			str = strObj.toString();
		} else {
			str = null;
		}

		Object strObj1 = deserialize.get("InteractUUID");
		String str1 = "";
		if (strObj1 != null) {
			str1 = strObj.toString();
		} else {
			str1 = null;
		}

		if (str != null && str1 != null) {
			return new GravesConfig(UUID.fromString(deserialize.get("UUID").toString()),
					deserialize.get("PlayerName").toString(),
					new Location(Bukkit.getWorld(UUID.fromString(deserialize.get("World").toString())),
							NumberConversions.toInt(deserialize.get("X")),
							NumberConversions.toInt(deserialize.get("Y")),
							NumberConversions.toInt(deserialize.get("Z"))),
					(HashMap<Integer, ItemStack>) deserialize.get("Items"),
					NumberConversions.toInt(deserialize.get("EXP")),
					(deserialize.get("DeathMessage") != null ? deserialize.get("DeathMessage").toString() : ""),
					NumberConversions.toLong(deserialize.get("Time")),
					Boolean.valueOf(
							(deserialize.get("Destroyed") != null ? deserialize.get("Destroyed").toString() : "false")),
					NumberConversions.toLong(deserialize.get("DestroyedTime")), UUID.fromString(str),
					UUID.fromString(str1));
		} else {
			return new GravesConfig(UUID.fromString(deserialize.get("UUID").toString()),
					deserialize.get("PlayerName").toString(),
					new Location(Bukkit.getWorld(UUID.fromString(deserialize.get("World").toString())),
							NumberConversions.toInt(deserialize.get("X")),
							NumberConversions.toInt(deserialize.get("Y")),
							NumberConversions.toInt(deserialize.get("Z"))),
					(HashMap<Integer, ItemStack>) deserialize.get("Items"),
					NumberConversions.toInt(deserialize.get("EXP")),
					(deserialize.get("DeathMessage") != null ? deserialize.get("DeathMessage").toString() : ""),
					NumberConversions.toLong(deserialize.get("Time")),
					Boolean.valueOf(
							(deserialize.get("Destroyed") != null ? deserialize.get("Destroyed").toString() : "false")),
					NumberConversions.toLong(deserialize.get("DestroyedTime")), null, null);
		}
	}

}
