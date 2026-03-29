package com.bencodez.gravestonesplus.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;

import lombok.Getter;
import lombok.Setter;

/**
 * Prevents environmental and block-system interactions from affecting graves.
 *
 * This listener is responsible for: - Explosions - Fluid movement - Bucket
 * placement - Pistons - Physics updates - Item drops from grave blocks
 */
@Getter
@Setter
public class GraveBlockProtectionListener implements Listener {

	/**
	 * Plugin instance.
	 */
	private GraveStonesPlus plugin;

	/**
	 * Constructor.
	 *
	 * @param plugin Plugin instance
	 */
	public GraveBlockProtectionListener(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * Prevents entity explosions from breaking grave markers.
	 *
	 * @param event Entity explode event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		for (Block block : event.blockList().toArray(new Block[0])) {
			if (isProtectedGraveMarker(block)) {
				event.blockList().remove(block);
			}
		}
	}

	/**
	 * Prevents block explosions from breaking grave markers.
	 *
	 * @param event Block explode event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event) {
		for (Block block : event.blockList().toArray(new Block[0])) {
			if (isProtectedGraveMarker(block)) {
				event.blockList().remove(block);
			}
		}
	}

	/**
	 * Prevents grave blocks from dropping items.
	 *
	 * @param event Block drop item event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBlockDropItem(BlockDropItemEvent event) {
		if (isProtectedGraveMarker(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	/**
	 * Prevents water or lava from flowing into grave markers.
	 *
	 * @param event Block from-to event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBlockFromTo(BlockFromToEvent event) {
		Material type = event.getBlock().getType();
		if (type != Material.WATER && type != Material.LAVA) {
			return;
		}

		if (isProtectedGraveMarker(event.getToBlock())) {
			event.setCancelled(true);
		}
	}

	/**
	 * Prevents bucket emptying into protected grave blocks.
	 *
	 * @param event Player bucket empty event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
		if (isProtectedGraveMarker(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	/**
	 * Prevents pistons from pushing grave markers or affecting them nearby.
	 *
	 * @param event Block piston extend event
	 */
	@SuppressWarnings("deprecation")
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPistonExtend(BlockPistonExtendEvent event) {
		for (Block block : event.getBlocks()) {
			if (isProtectedGraveMarker(block)) {
				event.setCancelled(true);
				return;
			}
		}

		if (pistonWouldAffectProtected(event.getBlock(), event.getLength())) {
			event.setCancelled(true);
		}
	}

	/**
	 * Prevents pistons from retracting grave markers or affecting them nearby.
	 *
	 * @param event Block piston retract event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPistonRetract(BlockPistonRetractEvent event) {
		for (Block block : event.getBlocks()) {
			if (isProtectedGraveMarker(block)) {
				event.setCancelled(true);
				return;
			}
		}

		if (pistonWouldAffectProtected(event.getBlock(), Math.max(1, event.getBlocks().size()))) {
			event.setCancelled(true);
		}
	}

	/**
	 * Prevents physics updates from removing or changing grave markers.
	 *
	 * @param event Block physics event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPhysics(BlockPhysicsEvent event) {
		Block block = event.getBlock();
		if (!isProtectedGraveMarker(block)) {
			return;
		}

		event.setCancelled(true);

		Material type = block.getType();
		if (type == Material.BARRIER) {
			restoreIfRemovedNextTick(block, Material.BARRIER);
		} else if (type == Material.PLAYER_HEAD) {
			restoreIfRemovedNextTick(block, Material.PLAYER_HEAD);
		} else if (type == Material.CHEST) {
			restoreIfRemovedNextTick(block, Material.CHEST);
		}
	}

	/**
	 * Checks whether a block has grave protection metadata.
	 *
	 * @param block Block
	 * @return true if protected
	 */
	private boolean isProtected(Block block) {
		if (block == null) {
			return false;
		}

		return MiscUtils.getInstance().getBlockMeta(block, "Grave") != null || block.hasMetadata("Grave");
	}

	/**
	 * Gets a grave from a block's metadata.
	 *
	 * @param block Block
	 * @return Grave or null
	 */
	private Grave getGrave(Block block) {
		if (block == null) {
			return null;
		}

		Object obj = MiscUtils.getInstance().getBlockMeta(block, "Grave");
		if (obj instanceof Grave) {
			return (Grave) obj;
		}

		return null;
	}

	/**
	 * Checks whether a block is a piston base.
	 *
	 * @param block Block
	 * @return true if piston base
	 */
	private boolean isPistonBase(Block block) {
		if (block == null) {
			return false;
		}

		Material type = block.getType();
		return type == Material.PISTON || type == Material.STICKY_PISTON;
	}

	/**
	 * Checks whether a material is any supported grave marker.
	 *
	 * @param type Material
	 * @return true if grave marker
	 */
	private boolean isAnyGraveMarker(Material type) {
		return type == Material.PLAYER_HEAD || type == Material.BARRIER || type == Material.CHEST;
	}

	/**
	 * Checks whether a block is a protected grave marker.
	 *
	 * @param block Block
	 * @return true if protected grave marker
	 */
	private boolean isProtectedGraveMarker(Block block) {
		if (block == null) {
			return false;
		}

		if (!isAnyGraveMarker(block.getType())) {
			return false;
		}

		Grave grave = getGrave(block);
		return grave != null && grave.isValid() && isProtected(block);
	}

	/**
	 * Checks whether piston movement would affect any protected grave marker.
	 *
	 * @param pistonBase        Piston base block
	 * @param extendLengthGuess Guess for extend distance
	 * @return true if a protected grave could be affected
	 */
	private boolean pistonWouldAffectProtected(Block pistonBase, int extendLengthGuess) {
		if (!isPistonBase(pistonBase)) {
			return false;
		}

		if (!(pistonBase.getBlockData() instanceof Directional)) {
			return false;
		}

		Directional data = (Directional) pistonBase.getBlockData();
		int max = Math.min(12, Math.max(1, extendLengthGuess));

		for (int i = 1; i <= max + 1; i++) {
			Block front = pistonBase.getRelative(data.getFacing(), i);
			if (isProtectedGraveMarker(front)) {
				return true;
			}
		}

		Block headPos = pistonBase.getRelative(data.getFacing(), 1);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					Block near = headPos.getRelative(dx, dy, dz);
					if (isProtectedGraveMarker(near)) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/**
	 * Restores a grave marker block on the next tick if it was removed.
	 *
	 * @param block    Block
	 * @param expected Expected material
	 */
	private void restoreIfRemovedNextTick(final Block block, final Material expected) {
		if (block == null) {
			return;
		}

		Bukkit.getScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				if (block.getType() == Material.AIR) {
					block.setType(expected, false);
				}
			}
		});
	}
}