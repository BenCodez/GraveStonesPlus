package com.bencodez.gravestonesplus.listeners;

import java.util.List;

import org.bukkit.inventory.ItemStack;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.nbt.NBTRule;

import de.tr7zw.nbtapi.NBTCompound;
import de.tr7zw.nbtapi.NBTItem;

public class PlayerDeathNBTHandle {
	/**
	 * Generic NBT rule matching method. Checks if the given item matches any rule
	 * in the provided list of NBT rules.
	 *
	 * @param item  The item to check.
	 * @param rules The list of rules to match against.
	 * @return True if the item matches any rule; otherwise, false.
	 */
	public boolean doesItemMatchAnyRule(GraveStonesPlus plugin, ItemStack item, List<NBTRule> rules) {
		if (item == null || item.getType().isAir() || rules == null || rules.isEmpty()) {
			return false;
		}

		NBTItem nbtItem = new NBTItem(item);
		// If the NBTItem has no NBT data, no need to check further
		if (!nbtItem.hasNBTData()) {
			return false;
		}

		for (NBTRule rule : rules) {
			String path = rule.getPath();
			String type = rule.getType();
			Object expectedValue = rule.getValue();

			// Debug log (optional, enable only for troubleshooting)
			// plugin.getLogger().info(" Evaluating NBT rule: " + rule.getName() + " for
			// item: " + item.getType().name() + " (Path: " + path + ", Type: " + type + ",
			// Expected Value: " + expectedValue + ")");

			String[] pathParts = path.split("\\.");
			NBTCompound currentCompound = nbtItem;

			// Traverse the path up to the compound tag preceding the final tag
			for (int i = 0; i < pathParts.length - 1; i++) {
				String part = pathParts[i];
				// Warning for paths containing list indices (not fully supported by this direct
				// implementation)
				if (part.matches("\\d+")) {
					plugin.getLogger().warning("NBT rule '" + rule.getName() + "' path '" + path
							+ "' contains list index which is not fully supported by this direct implementation.");
					currentCompound = null;
					break;
				}

				if (currentCompound.hasTag(part)) {
					currentCompound = currentCompound.getCompound(part);
				} else {
					currentCompound = null; // A compound tag in the path does not exist
					break;
				}
			}

			if (currentCompound == null) {
				continue; // Path does not match, try next rule
			}

			String finalTag = pathParts[pathParts.length - 1];

			if (!currentCompound.hasTag(finalTag)) {
				continue; // Final tag does not exist, try next rule
			}

			// If the rule type is "EXISTS", it matches as long as the tag exists
			if ("EXISTS".equalsIgnoreCase(type)) {
				return true;
			}

			Object actualValue = null;
			try {
				// Read the actual NBT value based on the rule type
				switch (type) {
				case "STRING":
					actualValue = currentCompound.getString(finalTag);
					break;
				case "INTEGER":
					actualValue = currentCompound.getInteger(finalTag);
					break;
				case "BOOLEAN":
					actualValue = currentCompound.getBoolean(finalTag);
					break;
				case "DOUBLE":
					actualValue = currentCompound.getDouble(finalTag);
					break;
				case "BYTE":
					actualValue = currentCompound.getByte(finalTag);
					break;
				case "SHORT":
					actualValue = currentCompound.getShort(finalTag);
					break;
				case "LONG":
					actualValue = currentCompound.getLong(finalTag);
					break;
				case "FLOAT":
					actualValue = currentCompound.getFloat(finalTag);
					break;
				default:
					plugin.getLogger().warning("NBT rule '" + rule.getName() + "' has unsupported type: " + type);
					continue; // Unsupported type, try next rule
				}
			} catch (Exception e) {
				plugin.getLogger().warning("Error reading NBT for rule '" + rule.getName() + "' (Path: " + path
						+ ", Type: " + type + "): " + e.getMessage());
				continue; // Failed to read NBT, try next rule
			}

			// Compare actual and expected values
			if (actualValue instanceof Number && expectedValue instanceof Number) {
				// For number types, use doubleValue for floating-point comparison to avoid
				// precision issues
				if (((Number) actualValue).doubleValue() == ((Number) expectedValue).doubleValue()) {
					return true;
				}
			} else if (actualValue != null && actualValue.equals(expectedValue)) {
				// For non-number types, use the equals method for comparison
				return true;
			}
		}
		return false; // No rule matched
	}
}
