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
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.events.GraveInteractEvent;
import com.bencodez.gravestonesplus.events.GraveInteractionType;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveDisplayType;
import com.bencodez.simpleapi.messages.MessageAPI;

/**
 * Prevents griefing/physics/piston/explosion interactions with grave blocks.
 * Also routes break interactions to grave claim logic.
 */
public class PlayerBreakBlock implements Listener {

	private final GraveStonesPlus plugin;

	/**
	 * Constructor.
	 *
	 * @param plugin Plugin instance
	 */
	public PlayerBreakBlock(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	private boolean isProtected(Block b) {
		if (b == null) {
			return false;
		}
		return MiscUtils.getInstance().getBlockMeta(b, "Grave") != null || b.hasMetadata("Grave");
	}

	private Grave getGrave(Block b) {
		if (b == null) {
			return null;
		}
		Object obj = MiscUtils.getInstance().getBlockMeta(b, "Grave");
		if (obj instanceof Grave) {
			return (Grave) obj;
		}
		return null;
	}

	private boolean isPistonBase(Block b) {
		if (b == null) {
			return false;
		}
		Material t = b.getType();
		return t == Material.PISTON || t == Material.STICKY_PISTON;
	}

	private boolean isAnyGraveMarker(Material type) {
		return type == Material.PLAYER_HEAD || type == Material.BARRIER || type == Material.CHEST;
	}

	private boolean isProtectedGraveMarker(Block b) {
		return b != null && isAnyGraveMarker(b.getType()) && isProtected(b);
	}

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

	private void restoreIfRemovedNextTick(final Block b, final Material expected) {
		if (b == null) {
			return;
		}
		Bukkit.getScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				if (b.getType() == Material.AIR) {
					b.setType(expected, false);
				}
			}
		});
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		for (Block b : event.blockList().toArray(new Block[0])) {
			if (isProtectedGraveMarker(b)) {
				event.blockList().remove(b);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event) {
		for (Block b : event.blockList().toArray(new Block[0])) {
			if (isProtectedGraveMarker(b)) {
				event.blockList().remove(b);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onItemDrop(BlockDropItemEvent event) {
		Block b = event.getBlock();
		if (isProtectedGraveMarker(b)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onWaterMove(BlockFromToEvent event) {
		if (event.getBlock().getType() == Material.WATER || event.getBlock().getType() == Material.LAVA) {
			Block to = event.getToBlock();
			if (isProtectedGraveMarker(to)) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onWaterCreate(PlayerBucketEmptyEvent event) {
		Block b = event.getBlock();
		if (isProtectedGraveMarker(b)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onBlockBreak(BlockBreakEvent event) {
		if (event.getPlayer() == null) {
			return;
		}

		Block b = event.getBlock();
		if (!isAnyGraveMarker(b.getType())) {
			return;
		}

		Grave grave = getGrave(b);
		if (grave == null) {
			return;
		}

		// Extra safety: only handle if this block is the correct marker for that grave
		if (!grave.isValid()) {
			event.setCancelled(true);
			return;
		}

		// Fire interact event for break attempt
		GraveInteractEvent ge = new GraveInteractEvent(grave, event.getPlayer(), GraveInteractionType.BREAK_ATTEMPT);
		Bukkit.getPluginManager().callEvent(ge);
		if (ge.isCancelled()) {
			event.setCancelled(true);
			return;
		}

		if (grave.getGravesConfig().isDestroyed()) {
			plugin.debug("Grave already broken: " + grave.getGravesConfig().getLocation().toString());
			event.setCancelled(true);
			return;
		}

		GraveDisplayType type = plugin.getConfigFile().getGraveDisplayTypeEnum();
		try {
			String stored = grave.getGravesConfig().getGraveDisplayType();
			if (stored != null && !stored.trim().isEmpty()) {
				type = GraveDisplayType.valueOf(stored.trim().toUpperCase());
			}
		} catch (Exception ignored) {
			// keep config default
		}

		// Barrier graves are never breakable
		if (type == GraveDisplayType.DISPLAY_ENTITY && b.getType() == Material.BARRIER) {
			event.setCancelled(true);
			return;
		}

		// Claim logic for breakable grave markers (HEAD/CHEST)
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
			}
			plugin.debug("Config option disabled to break other graves");
		} else {
			plugin.debug("No permission to break other graves");
		}

		event.getPlayer().sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatNotYourGrave()));
		event.setCancelled(true);
	}

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

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPhysics(BlockPhysicsEvent event) {
		Block b = event.getBlock();

		if (isProtectedGraveMarker(b)) {
			event.setCancelled(true);

			Material t = b.getType();
			if (t == Material.BARRIER) {
				restoreIfRemovedNextTick(b, Material.BARRIER);
			} else if (t == Material.PLAYER_HEAD) {
				restoreIfRemovedNextTick(b, Material.PLAYER_HEAD);
			} else if (t == Material.CHEST) {
				restoreIfRemovedNextTick(b, Material.CHEST);
			}
		}
	}
}