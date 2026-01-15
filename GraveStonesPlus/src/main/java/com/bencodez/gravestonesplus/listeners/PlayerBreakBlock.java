package com.bencodez.gravestonesplus.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.block.BlockExplodeEvent;

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.simpleapi.messages.MessageAPI;

public class PlayerBreakBlock implements Listener {

	private final GraveStonesPlus plugin;

	public PlayerBreakBlock(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	// --------------------
	// Helpers
	// --------------------

	private boolean isProtected(Block b) {
		if (b == null) {
			return false;
		}
		// Supports both your MiscUtils block meta and plain Bukkit metadata
		return MiscUtils.getInstance().getBlockMeta(b, "Grave") != null || b.hasMetadata("Grave");
	}

	private boolean isPistonBase(Block b) {
		if (b == null)
			return false;
		Material t = b.getType();
		return t == Material.PISTON || t == Material.STICKY_PISTON;
	}

	/**
	 * Bedrock-breaker style contraptions can break blocks that are NOT in
	 * event.getBlocks(). So we check the piston line + small bubble near its head.
	 */
	private boolean pistonWouldAffectProtected(Block pistonBase, int extendLengthGuess) {
		if (!isPistonBase(pistonBase))
			return false;
		if (!(pistonBase.getBlockData() instanceof Directional))
			return false;

		Directional data = (Directional) pistonBase.getBlockData();
		int max = Math.min(12, Math.max(1, extendLengthGuess));

		// Check line in front of piston
		for (int i = 1; i <= max + 1; i++) {
			Block front = pistonBase.getRelative(data.getFacing(), i);
			if (isProtected(front)
					&& (front.getType() == Material.PLAYER_HEAD || front.getType() == Material.BARRIER)) {
				return true;
			}
		}

		// Check a small bubble around the piston head position (common exploit layouts)
		Block headPos = pistonBase.getRelative(data.getFacing(), 1);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					Block near = headPos.getRelative(dx, dy, dz);
					if (isProtected(near)
							&& (near.getType() == Material.PLAYER_HEAD || near.getType() == Material.BARRIER)) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private void restoreIfRemovedNextTick(Block b, Material expected) {
		if (b == null)
			return;
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (b.getType() == Material.AIR) {
				b.setType(expected, false);
			}
		});
	}

	// --------------------
	// Explosions
	// --------------------

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		for (Block b : event.blockList().toArray(new Block[0])) {
			if ((b.getType() == Material.PLAYER_HEAD || b.getType() == Material.BARRIER) && isProtected(b)) {
				event.blockList().remove(b);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event) {
		for (Block b : event.blockList().toArray(new Block[0])) {
			if ((b.getType() == Material.PLAYER_HEAD || b.getType() == Material.BARRIER) && isProtected(b)) {
				event.blockList().remove(b);
			}
		}
	}

	// --------------------
	// Drops
	// --------------------

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onItemDrop(BlockDropItemEvent event) {
		Block b = event.getBlock();
		if ((b.getType() == Material.PLAYER_HEAD || b.getType() == Material.BARRIER) && isProtected(b)) {
			event.setCancelled(true);
		}
	}

	// --------------------
	// Liquids
	// --------------------

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onWaterMove(BlockFromToEvent event) {
		if (event.getBlock().getType() == Material.WATER || event.getBlock().getType() == Material.LAVA) {
			Block to = event.getToBlock();
			if ((to.getType() == Material.PLAYER_HEAD || to.getType() == Material.BARRIER) && isProtected(to)) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onWaterCreate(PlayerBucketEmptyEvent event) {
		Block b = event.getBlock();
		if ((b.getType() == Material.PLAYER_HEAD || b.getType() == Material.BARRIER) && isProtected(b)) {
			event.setCancelled(true);
		}
	}

	// --------------------
	// Player breaks
	// --------------------

	@EventHandler(priority = EventPriority.LOW)
	public void onBlockBreak(BlockBreakEvent event) {
		if (event.getPlayer() == null) {
			return;
		}

		Block b = event.getBlock();

		// Barrier protection: if it's a protected grave barrier, never allow breaking
		if (b.getType() == Material.BARRIER && isProtected(b)) {
			event.setCancelled(true);
			return;
		}

		// Existing skull logic, unchanged behavior, but now only runs for protected
		// skulls
		if (b.getType() == Material.PLAYER_HEAD) {
			Object obj = MiscUtils.getInstance().getBlockMeta(b, "Grave");
			if (obj == null) {
				return;
			}
			Grave grave = (Grave) obj;

			if (grave.getGravesConfig().isDestroyed()) {
				plugin.debug("Grave already broken: " + grave.getGravesConfig().getLocation().toString());
				return;
			}

			if (grave.isOwner(event.getPlayer())) {
				event.setDropItems(false);
				grave.claim(event.getPlayer());
				return;
			}

			if (event.isCancelled()) {
				return;
			}

			if (event.getPlayer().hasPermission("GraveStonesPlus.BreakOtherGraves")) {
				if (plugin.getConfigFile().isBreakOtherGravesWithPermission()) {
					event.setDropItems(false);
					grave.claim(event.getPlayer());
					return;
				} else {
					plugin.debug("Config option disabled to break other graves");
				}
			} else {
				plugin.debug("No permission to break other graves");
			}

			event.getPlayer().sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatNotYourGrave()));
			event.setCancelled(true);
		}
	}

	// --------------------
	// Pistons (extend + retract)
	// --------------------

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPistonExtend(BlockPistonExtendEvent event) {
		// If any moved block is protected, cancel
		for (Block block : event.getBlocks()) {
			if ((block.getType() == Material.PLAYER_HEAD || block.getType() == Material.BARRIER)
					&& isProtected(block)) {
				event.setCancelled(true);
				return;
			}
		}

		// Bedrock breaker defense: cancel if piston would affect protected blocks even
		// if not in moved list
		if (pistonWouldAffectProtected(event.getBlock(), event.getLength())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPistonRetract(BlockPistonRetractEvent event) {
		for (Block block : event.getBlocks()) {
			if ((block.getType() == Material.PLAYER_HEAD || block.getType() == Material.BARRIER)
					&& isProtected(block)) {
				event.setCancelled(true);
				return;
			}
		}

		// Use moved block count as a rough length guess for retract
		if (pistonWouldAffectProtected(event.getBlock(), Math.max(1, event.getBlocks().size()))) {
			event.setCancelled(true);
		}
	}

	// --------------------
	// Physics fallback (the "at all costs" layer)
	// --------------------

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPhysics(BlockPhysicsEvent event) {
		Block b = event.getBlock();

		if (b.getType() == Material.BARRIER && isProtected(b)) {
			event.setCancelled(true);
			restoreIfRemovedNextTick(b, Material.BARRIER);
			return;
		}

		if (b.getType() == Material.PLAYER_HEAD && isProtected(b)) {
			event.setCancelled(true);
			restoreIfRemovedNextTick(b, Material.PLAYER_HEAD);
		}
	}
}
