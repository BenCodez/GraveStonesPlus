package com.bencodez.gravestonesplus.listeners;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.nbt.NBTRule;
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.messages.MessageAPI;

public class PlayerDeathListener implements Listener {
	private GraveStonesPlus plugin;

	/**
	 * Instantiates a new player interact.
	 *
	 * @param plugin the plugin
	 */
	public PlayerDeathListener(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	// Players in separate Folia regions can die/respawn concurrently. Each value is
    // one death generation; delayed callbacks must never consume a successor's items.
    private static final class KeptBatch {
        final Map<Integer, ItemStack> items;
        volatile boolean accepted;
        KeptBatch(Map<Integer, ItemStack> source) {
            HashMap<Integer, ItemStack> snapshot = new HashMap<>();
            source.forEach((slot, item) -> snapshot.put(slot, item.clone()));
            items = Map.copyOf(snapshot);
        }
    }
    private static final class KeptItems {
        final List<KeptBatch> batches;
        // A new identity fences callbacks even when the new death captures no items.
        KeptItems(KeptItems previous) { batches = previous.batches; }
        KeptItems(List<KeptBatch> batches) { this.batches = List.copyOf(batches); }
        KeptItems(KeptItems previous, Map<Integer, ItemStack> source) {
            List<KeptBatch> merged = previous == null ? new ArrayList<>() : new ArrayList<>(previous.batches);
            merged.add(new KeptBatch(source));
            batches = List.copyOf(merged);
        }
        KeptBatch newest() { return batches.get(batches.size() - 1); }
        boolean ready() { return batches.stream().allMatch(batch -> batch.accepted); }
    }
    private static final int MAX_PENDING_KEPT_SNAPSHOTS = 8;
    private final ConcurrentMap<UUID, KeptItems> deathItems = new ConcurrentHashMap<>();

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerDeath(PlayerDeathEvent event) {
        final UUID playerId = event.getEntity().getUniqueId();
        deathItems.computeIfPresent(playerId, (id, previous) -> new KeptItems(previous));
		final Location deathLocation = event.getEntity().getLocation();
		if (plugin.getConfigFile().getDisabledWorlds().contains(deathLocation.getWorld().getName())) {
			plugin.debug("Graves in " + deathLocation.getWorld().getName() + " are disabled");
			return;
		}

		if (event.getEntity().hasMetadata("NPC")) {
			plugin.getLogger().info("Not creating grave for NPC");
			return;
		}

		if (!event.getEntity().hasPermission("GraveStonesPlus.AllowGrave")) {
			plugin.getLogger().info("Not creating grave for " + event.getEntity().getName() + ", no permission");
			return;
		}

		if (event.getKeepInventory()) {
			plugin.getLogger().info("Inventory was not dropped, not making grave");
			return;
		}

		if (plugin.getPvpManager() != null) {
			if (!plugin.getPvpManager().canHaveGrave(event.getEntity())) {
				plugin.getLogger().info("Can't create grave, player was in combat");
				return;
			}
		}

		if (isInventoryEmpty(event.getEntity().getInventory())) {
			if (!plugin.getConfigFile().isCreateGraveForEmptyInventories()) {
				plugin.getLogger().info("Not creating grave, player has an empty inventory");
				return;
			}
		}

		final Player entity = event.getEntity();
        final String playerName = entity.getName();

		Location emptyBlock = null;
		if (deathLocation.getBlock().isEmpty() && deathLocation.getBlockY() > deathLocation.getWorld().getMinHeight()
				&& deathLocation.getBlockY() < deathLocation.getWorld().getMaxHeight()) {
			emptyBlock = deathLocation;
		} else {
			emptyBlock = getAirBlock(deathLocation);
		}

		if (emptyBlock == null) {
			plugin.getLogger().info("Failed to find air block, can't make grave");
			return;
		}

		PlayerInventory inv = event.getEntity().getInventory();

		// Important improvement: Define the placeholder map here and use it inside
		// processItem
		// This ensures that placeholders are correctly replaced for Lore rules each
		// time an item is processed
		final HashMap<String, String> playerPlaceholders = new HashMap<>();
		playerPlaceholders.put("player", entity.getName());
		playerPlaceholders.put("displayname", entity.getDisplayName());
		// Add any other player-related placeholders you might need here

		final String deathMessage = event.getDeathMessage();
		final int droppedExp = plugin.getConfigFile().isKeepAllExp()
				? getTotalExperience(event.getEntity()) : event.getDroppedExp();

		// Store items that will go into the grave and items that will be kept
		HashMap<Integer, ItemStack> itemsWithSlot = new HashMap<Integer, ItemStack>(); // Items to be placed in the
																						// grave
		HashMap<Integer, ItemStack> keepItems = new HashMap<Integer, ItemStack>(); // Items to be kept in the player's
																					// inventory

        final ItemRuleEvaluator evaluator = ItemRuleEvaluator.forPlayer(plugin, entity);
        BiConsumer<Integer, ItemStack> processItem = (idx, item) -> {
            switch (evaluator.evaluate(item)) {
                case KEEP_LORE, KEEP_NBT -> keepItems.put(idx, item);
                case GRAVE_NBT, GRAVE_DEFAULT -> itemsWithSlot.put(idx, item.clone());
                default -> { }
            }
        };

		// Iterate through player inventory slots and process items
		for (int i = 0; i < 36; i++) { // Main inventory (0-35)
			ItemStack item = inv.getItem(i);
			processItem.accept(i, item);
		}
		processItem.accept(-1, inv.getHelmet()); // Helmet
		processItem.accept(-2, inv.getChestplate()); // Chestplate
		processItem.accept(-3, inv.getLeggings()); // Leggings
		processItem.accept(-4, inv.getBoots()); // Boots
		processItem.accept(-5, inv.getItemInOffHand()); // Offhand

        // Admission comes before limit removal, but a failed preflight must not
        // leave these same items eligible for both native drops and respawn.
        KeptItems[] admitted = { null };
        if (!keepItems.isEmpty()) {
			deathItems.compute(playerId, (id, pending) -> {
                if (pending != null && pending.batches.size() >= MAX_PENDING_KEPT_SNAPSHOTS) return pending;
                KeptItems merged = new KeptItems(pending, keepItems);
                admitted[0] = merged;
                return merged;
			});
			if (admitted[0] == null) {
				plugin.getLogger().warning("Kept items not retained: pending snapshot limit reached");
				return;
			}
		}

		final Location emptyBlockFinal = emptyBlock;


		CreationHandoff handoff = new CreationHandoff(plugin, new Runnable() {
			@Override
			public void run() {
				Grave grave = new Grave(plugin,
						new GravesConfig(playerId, playerName, emptyBlockFinal, itemsWithSlot,
								droppedExp, deathMessage, System.currentTimeMillis(), false, 0, null, null,
								plugin.getConfigFile().getGraveDisplayTypeEnum().name()));
				grave.loadChunk(false);

				grave.createGrave();

				grave.createHologram();
				grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
				plugin.addGrave(grave);
				grave.loadBlockMeta(emptyBlockFinal.getBlock());

				HashMap<String, String> placeholders = new HashMap<String, String>();
				placeholders.put("x", "" + emptyBlockFinal.getBlockX());
				placeholders.put("y", "" + emptyBlockFinal.getBlockY());
				placeholders.put("z", "" + emptyBlockFinal.getBlockZ());

				String msg = MessageAPI.colorize(com.bencodez.advancedcore.api.messages.PlaceholderUtils
						.replacePlaceHolder(plugin.getConfigFile().getFormatDeath(), placeholders));
				if (!msg.isEmpty()) {
                    try {
                        plugin.getBukkitScheduler().runTask(plugin, () -> {
                            if (plugin.isEnabled() && entity.isOnline()) entity.sendMessage(msg);
                        }, entity);
                    } catch (RuntimeException rejected) {
                        // A retired player cannot receive a message; the grave itself is already established.
                        plugin.getLogger().fine("Skipping grave notification: player scheduler unavailable");
                    }
				}

				plugin.getLogger().info("Grave: " + emptyBlockFinal.toString());
			}
		});
        boolean scheduled = false;
        try {
            handoff.schedule(emptyBlockFinal);
            scheduled = true;
        } finally {
            if (!scheduled && admitted[0] != null) rollbackKeptBatch(playerId, admitted[0]);
        }

        boolean accepted = false;
        try {
            if (plugin.numberOfGraves(playerId) >= plugin.getConfigFile().getGraveLimit()) {
                Grave oldest = plugin.getOldestGrave(playerId);
                if (oldest != null) {
                    if (plugin.getConfigFile().isDropItemsOnGraveRemoval()) {
                        oldest.dropAndRemoveForLimit(entity);
                    } else {
                        oldest.removeGrave(GraveRemoveReason.LIMIT_REACHED);
                        entity.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatGraveLimitBreak()));
                    }
                }
            }
            event.setDroppedExp(0);
            event.getDrops().clear();
            if (admitted[0] != null) admitted[0].newest().accepted = true;
            handoff.accept();
            accepted = true;
        } finally {
            if (!accepted) {
                handoff.cancel();
                if (admitted[0] != null) rollbackKeptBatch(playerId, admitted[0]);
            }
        }

	}

    /** A location callback may run before the player region finishes the death event.
     * The admitted timer retries only that short preparation window; it performs
     * creation once and cancels itself. No second scheduler admission is required
     * after native drops are consumed. */
    private static final class CreationHandoff implements Runnable {
        private static final int PREPARING = 0, ACCEPTED = 1, RUNNING = 2, FINISHED = 3;
        private final GraveStonesPlus plugin;
        private final Runnable creation;
        private final java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> task = new java.util.concurrent.atomic.AtomicReference<>();
        CreationHandoff(GraveStonesPlus plugin, Runnable creation) {
            this.plugin = plugin; this.creation = creation;
        }
        void schedule(Location location) {
            try {
                // The legacy location overload interpreted its delay in seconds.
                // Preserve that delay explicitly while obtaining a cancellable handle.
                var scheduled = plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtLocationTimer(
                        location, this, 2L, 1L, java.util.concurrent.TimeUnit.SECONDS);
                task.set(scheduled);
                if (scheduled == null || scheduled.isCancelled())
                    throw new java.util.concurrent.RejectedExecutionException("Grave creation scheduler unavailable");
                if (phase.get() == FINISHED) cancel();
            } catch (RuntimeException rejected) { cancel(); throw rejected; }
        }
        void accept() { phase.compareAndSet(PREPARING, ACCEPTED); }
        void cancel() {
            phase.set(FINISHED);
            var current = task.getAndSet(null);
            if (current != null) try { current.cancel(); }
            catch (RuntimeException unavailable) {
                // The phase fence still prevents any retired callback from creating a grave.
                // A platform cancellation failure must not skip kept-item rollback.
                task.compareAndSet(null, current);
                plugin.getLogger().fine("Grave creation task cancellation unavailable");
            }
        }
        @Override public void run() {
            if (phase.get() == FINISHED) { cancel(); return; }
            if (!phase.compareAndSet(ACCEPTED, RUNNING)) return;
            try { if (plugin.isEnabled()) creation.run(); }
            finally { cancel(); }
        }
    }

    private void rollbackKeptBatch(UUID playerId, KeptItems admitted) {
        // Remove only this death's batch from the current generation. Restoring
        // an earlier whole snapshot would discard a successor's kept items.
        KeptBatch batch = admitted.newest();
        deathItems.computeIfPresent(playerId, (id, pending) -> {
            List<KeptBatch> retained = new ArrayList<>(pending.batches);
            retained.removeIf(candidate -> candidate == batch);
            return retained.isEmpty() ? null : new KeptItems(retained);
        });
    }

	private boolean doesItemMatchAnyRule(ItemStack item, List<NBTRule> keepNbtRules) {
		if (plugin.isNbtAPIHooked()) {
			return new PlayerDeathNBTHandle().doesItemMatchAnyRule(plugin, item, keepNbtRules);
		}
		return false;
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
        KeptItems pending = deathItems.get(uuid);
        if (pending != null) {
            try {
                plugin.getBukkitScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled() || !player.isOnline() || player.isDead() || !pending.ready() || !deathItems.remove(uuid, pending)) return;
				PlayerInventory inv = player.getInventory();
				pending.batches.forEach(batch -> batch.items.forEach((slot, item) -> {
					if (item == null || item.getType().isAir())
						return;
					if (slot >= 0) {
						if (inv.getItem(slot) == null)
							inv.setItem(slot, item);
						else
							dropOverflow(player, inv.addItem(item));
					} else {
						switch (slot) {
						case -1:
							if (isSlotAvailable(inv.getHelmet()))
								inv.setHelmet(item);
							else
								dropOverflow(player, inv.addItem(item));
							break;
						case -2:
							if (isSlotAvailable(inv.getChestplate()))
								inv.setChestplate(item);
							else
								dropOverflow(player, inv.addItem(item));
							break;
						case -3:
							if (isSlotAvailable(inv.getLeggings()))
								inv.setLeggings(item);
							else
								dropOverflow(player, inv.addItem(item));
							break;
						case -4:
							if (isSlotAvailable(inv.getBoots()))
								inv.setBoots(item);
							else
								dropOverflow(player, inv.addItem(item));
							break;
						case -5:
							if (isSlotAvailable(inv.getItemInOffHand()))
								inv.setItemInOffHand(item);
							else
								dropOverflow(player, inv.addItem(item));
							break;
						}
					}
				}));
                }, player);
            } catch (RuntimeException rejected) {
                // Keep this generation for a later respawn; never mutate a retired player off-owner.
                plugin.getLogger().warning("Kept items remain pending: player scheduler unavailable");
            }
		}

		if (plugin.getConfigFile().isGiveCompassOnRespawn()) {
			Runnable deliverCompass = () -> {
                if (!plugin.isEnabled() || !player.isOnline() || !player.hasPermission("GraveStonesPlus.Compass")) return;
				Grave grave = plugin.getLatestGrave(player);
				if (grave != null && plugin.canTrack(grave, player)) {
					grave.giveCompass(player);
				}
            };
            var folia = plugin.getBukkitScheduler().getFoliaLib();
            // The original respawn delay is five server ticks. Its Spigot/Paper entity-delay
            // overloads recurse, so use the synchronous delay there. Folia needs the
            // moving player's entity owner; a retired entity receives no compass.
            if (folia.isFolia()) folia.getImpl().runAtEntityLater(player, deliverCompass, () -> {}, 5L);
            else folia.getImpl().runLater(deliverCompass, 5L);
		}
	}

	private void dropOverflow(Player player, Map<Integer, ItemStack> leftovers) {
		if (leftovers == null) return;
		leftovers.values().forEach(item -> {
			if (item != null && !item.getType().isAir()) player.getWorld().dropItemNaturally(player.getLocation(), item);
		});
	}

	/**
	 * Calculates a player's total experience as an integer. This sums up the XP for
	 * each completed level plus the XP in the current level.
	 */
	private int getTotalExperience(Player player) {
		int level = player.getLevel();
		int exp = 0;

		// Sum XP required for each full level they've completed
		for (int i = 0; i < level; i++) {
			exp += getExpForLevel(i);
		}

		// Add the XP they've earned toward the next level
		exp += Math.round(player.getExp() * player.getExpToLevel());

		return exp;
	}

	/**
	 * Returns the XP required to go from level n to n+1.
	 */
	private int getExpForLevel(int n) {
		if (n <= 15) {
			return 2 * n + 7;
		} else if (n <= 30) {
			return 5 * n - 38;
		} else {
			return 9 * n - 158;
		}
	}

	private boolean isInventoryEmpty(PlayerInventory inventory) {
		// Check main inventory slots
		for (ItemStack item : inventory.getContents()) {
			if (item != null && !item.getType().isAir()) {
				return false;
			}
		}
		// Check armor slots
		for (ItemStack item : inventory.getArmorContents()) {
			if (item != null && !item.getType().isAir()) {
				return false;
			}
		}
		// Check offhand slot
		ItemStack offHandItem = inventory.getItemInOffHand(); // Corrected method call from IgetItemInOffHand()
		if (offHandItem != null && !offHandItem.getType().isAir()) {
			return false;
		}
		return true;
	}

	public boolean isSlotAvailable(ItemStack slot) {
		return slot == null || slot.getType().isAir();
	}

    public boolean keepItemsWithMatchingLore(ItemStack item, String text) { return ItemRuleEvaluator.matchesLore(item, text); }

    public boolean hasCurseOfVanishing(ItemStack item) { return ItemRuleEvaluator.vanishes(item); }

	public Location getAirBlock(Location loc) {
		int startingY = loc.getBlockY();
		boolean reverse = false;
		if (startingY < loc.getWorld().getMinHeight()) {
			startingY = loc.getWorld().getMinHeight();
		}
		if (startingY > loc.getWorld().getMaxHeight()) {
			reverse = true;
			startingY = loc.getWorld().getMaxHeight();
		}
		if (!reverse) {
			for (int i = startingY; i < loc.getWorld().getMaxHeight(); i++) {
				Block b = loc.getWorld().getBlockAt((int) loc.getX(), i, (int) loc.getZ());
				if (b.isEmpty() || isReplaceable(b.getType())) {
					return b.getLocation();
				}
			}
		} else {
			for (int i = startingY - 1; i > loc.getWorld().getMinHeight(); i--) {
				Block b = loc.getWorld().getBlockAt((int) loc.getX(), i, (int) loc.getZ());
				if (b.isEmpty() || isReplaceable(b.getType())) {
					return b.getLocation();
				}
			}
		}
		return null;
	}

	public boolean isReplaceable(Material material) {
		switch (material.toString()) {
		case "TALL_GRASS":
		case "GRASS":
		case "SHORT_GRASS":
		case "FERN":
		case "LARGE_FERN":
		case "SNOW":
			return true;
		default:
			return false;
		}
	}
}
