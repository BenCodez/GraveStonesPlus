package com.bencodez.gravestonesplus.graves;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.NumberConversions;

import lombok.Getter;

public class GravesConfig implements ConfigurationSerializable {

	@Getter
	private List<ItemStack> items;

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

	public GravesConfig(UUID uuid, String playerName, Location loc, List<ItemStack> items, int exp, String deathMessage,
			long time) {
		this.uuid = uuid;
		this.location = loc;
		this.items = items;
		this.exp = exp;
		this.playerName = playerName;
		this.deathMessage = deathMessage;
		this.time = time;
	}

	@Override
	public Map<String, Object> serialize() {
		Map<String, Object> serialized = new HashMap<>();
		serialized.put("DeathMessage", deathMessage);
		serialized.put("Time", time);
		serialized.put("UUID", uuid.toString());
		serialized.put("PlayerName", playerName);
		serialized.put("World", location.getWorld().getUID().toString());
		serialized.put("X", location.getX());
		serialized.put("Y", location.getY());
		serialized.put("Z", location.getZ());
		serialized.put("Items", items);
		serialized.put("EXP", exp);
		return serialized;
	}

	@SuppressWarnings("unchecked")
	public static GravesConfig deserialize(Map<String, Object> deserialize) {
		return new GravesConfig(UUID.fromString(deserialize.get("UUID").toString()),
				deserialize.get("PlayerName").toString(),
				new Location(Bukkit.getWorld(UUID.fromString(deserialize.get("World").toString())),
						NumberConversions.toInt(deserialize.get("X")), NumberConversions.toInt(deserialize.get("Y")),
						NumberConversions.toInt(deserialize.get("Z"))),
				(List<ItemStack>) deserialize.get("Items"), NumberConversions.toInt(deserialize.get("EXP")),
				deserialize.get("DeathMessage").toString(), NumberConversions.toLong(deserialize.get("Time")));
	}

}
