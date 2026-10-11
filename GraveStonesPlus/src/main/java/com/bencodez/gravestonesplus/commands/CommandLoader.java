package com.bencodez.gravestonesplus.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.persistence.PersistentDataType;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.inventory.BInventory;
import com.bencodez.advancedcore.api.inventory.BInventoryButton;
import com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.simpleapi.command.TabCompleteHandle;
import com.bencodez.simpleapi.command.TabCompleteHandler;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveCreationTask;
import com.bencodez.gravestonesplus.graves.TrustedGraveAccess;
import com.bencodez.gravestonesplus.storage.GravesConfig;

import net.md_5.bungee.api.chat.TextComponent;

public class CommandLoader {
	private GraveStonesPlus plugin;

	public CommandLoader(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	public ArrayList<TextComponent> helpText(CommandSender sender) {
		ArrayList<TextComponent> msg = new ArrayList<TextComponent>();
		HashMap<String, TextComponent> unsorted = new HashMap<String, TextComponent>();

		boolean requirePerms = plugin.getConfigFile().isFormatHelpRequirePermission();

		for (CommandHandler cmdHandle : plugin.getCommands()) {
			if (!requirePerms || cmdHandle.hasPerm(sender)) {
				unsorted.put(cmdHandle.getHelpLineCommand("/gravestonesplus"),
						cmdHandle.getHelpLine("/gravestonesplus"));
			}
		}
		ArrayList<String> unsortedList = new ArrayList<String>();
		unsortedList.addAll(unsorted.keySet());
		Collections.sort(unsortedList, String.CASE_INSENSITIVE_ORDER);
		for (String cmd : unsortedList) {
			msg.add(unsorted.get(cmd));
		}

		return msg;
	}

	public void loadCommands() {
        // Registered placeholder names are also the bundled dispatcher's argument grammar.
        TabCompleteHandler.getInstance().addTabCompleteOption(new TabCompleteHandle("(GraveHelper)") {
            @Override public void reload() { updateReplacements(); }
            @Override public void updateReplacements() {
                ArrayList<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
                setReplace(names);
            }
        }.updateOnLoginLogout());
        TabCompleteHandler.getInstance().addTabCompleteOption("(GraveAccessKind)", "track", "recover");
        TabCompleteHandler.getInstance().addTabCompleteOption("(GraveDuration)", "30m", "2h", "7d");
		plugin.getCommands().add(
				new CommandHandler(plugin, new String[] { "Reload" }, "GraveStonesPlus.Reload", "Reload the plugin") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						plugin.reloadAsync().whenComplete((ignored, failure) -> {
							Runnable reply = () -> {
								if (sender instanceof Player player && (!plugin.isEnabled() || !player.isOnline()
										|| !player.hasPermission("GraveStonesPlus.Reload"))) return;
								if (failure == null) sendMessage(sender, "&cGraveStonesPlus reloaded");
								else sendMessage(sender, "&cGraveStonesPlus reload failed; inspect the server log");
							};
							if (sender instanceof Player player) plugin.getBukkitScheduler().runTask(plugin, reply, player);
							else plugin.getBukkitScheduler().runTask(plugin, reply);
						});
					}
				});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "Help" }, "GraveStonesPlus.Help", "Shows this page") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						sendMessageJson(sender, helpText(sender));
					}
				});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "Perms" }, "GraveStonesPlus.Perms", "List all perms") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						ArrayList<String> msg = new ArrayList<String>();
						for (CommandHandler handle : plugin.getCommands()) {
							if (sender instanceof Player) {
								if (handle.hasPerm(sender)) {
									msg.add("&6" + handle.getHelpLineCommand("/gravestonesplus") + " : "
											+ handle.getPerm().split(Pattern.quote("|"))[0] + " : &atrue");
								} else {
									msg.add("&6" + handle.getHelpLineCommand("/gravestonesplus") + " : "
											+ handle.getPerm().split(Pattern.quote("|"))[0] + " : &cfalse");
								}
							} else {
								msg.add(handle.getHelpLineCommand("/gravestonesplus") + " : "
										+ handle.getPerm().split(Pattern.quote("|"))[0]);
							}
						}

						for (Permission perm : plugin.getDescription().getPermissions()) {
							if (sender instanceof Player) {
								Set<String> child = perm.getChildren().keySet();
								if (child.size() > 0) {
									if (sender.hasPermission(perm)) {
										msg.add("&6" + perm.getName() + " : &atrue");
									} else {
										msg.add("&6" + perm.getName() + " : &cfalse");
									}
								} else {
									if (sender.hasPermission(perm)) {
										msg.add("&6" + perm.getName() + " : &atrue");
									} else {
										msg.add("&6" + perm.getName() + " : &cfalse");
									}
								}

							} else {
								msg.add(perm.getName());
							}
						}
						sendMessage(sender, msg);
					}
				});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Graves" }, "GraveStonesPlus.Graves",
				"See current graves", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
                if (!(sender instanceof Player player)) return;
                ownerDispatch(player, "GraveStonesPlus.Graves").accept(() -> {
                    BInventory inv = new BInventory("Accessible Graves");
                    for (Grave gr : plugin.getAccessibleGraves(player)) inv.addButton(gr.getNavigationGUIItem(player));
                    inv.openInventory(player);
                });
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "AllGraves" }, "GraveStonesPlus.AllGraves",
				"See all current graves", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				List<Grave> graves = plugin.getGraves();
				BInventory inv = new BInventory("All Graves");
				for (Grave gr : graves) {
					inv.addButton(gr.getGUIItem());
				}

				inv.openInventory((Player) sender);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "AllGraves", "(Player)" },
				"GraveStonesPlus.AllGraves.Player", "See all current graves of player", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				List<Grave> graves = plugin.getGraves();
				BInventory inv = new BInventory("All Graves: " + args[1]);
				for (Grave gr : graves) {
					if (gr.isOwner(args[1])) {
						inv.addButton(gr.getGUIItem());
					}
				}

				inv.openInventory((Player) sender);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "AllBrokenGraves" },
				"GraveStonesPlus.AllBrokenGraves", "See all current recent broken graves", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				List<Grave> graves = plugin.getBrokenGraves();
				BInventory inv = new BInventory("All Broken Graves");
				for (Grave gr : graves) {
					inv.addButton(gr.getGUIItemBroken());
				}

				inv.openInventory((Player) sender);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "AllBrokenGraves", "(Player)" },
				"GraveStonesPlus.AllBrokenGraves.Player", "See all current recent broken graves", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				List<Grave> graves = plugin.getBrokenGraves();
				BInventory inv = new BInventory("All Broken Graves: " + args[1]);
				for (Grave gr : graves) {
					if (gr.isOwner(args[1])) {
						inv.addButton(gr.getGUIItemBroken());
					}
				}

				inv.openInventory((Player) sender);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "CheckValidGraves" },
				"GraveStonesPlus.CheckValidGraves", "Check and remove invalid graves") {

			@Override
			public void execute(CommandSender sender, String[] args) {
				sendMessage(sender, "&aChecking and removing invalid graves");
				List<Grave> graves = plugin.getGraves();

				for (Grave gr : graves) {
					if (!gr.isValid()) {
						sendMessage(sender, "&aRemoving grave: " + gr.getAllGraveMessage());
						gr.removeGrave(GraveRemoveReason.INVALID);
					}
				}

			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Graves", "(player)" },
				"GraveStonesPlus.Graves.Other", "See current graves", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				List<Grave> graves = plugin.getGraves(args[1]);
				ArrayList<String> msg = new ArrayList<String>();
				msg.add("Current graves for " + args[1] + ":");
				for (Grave gr : graves) {
					msg.add(gr.getGraveMessage());
				}
				sendMessage(sender, msg);
			}
		});

		plugin.getCommands()
				.add(new CommandHandler(plugin, new String[] { "KillGravesRadius" }, "GraveStonesPlus.KillGravesRadius",
						"Kills armor stands/graves from the plugin within a radius of 10", false) {

					@Override
					public void execute(CommandSender sender, String[] args) {
						final Player p = (Player) sender;
						plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

							@Override
							public void run() {

								int amount = 0;
								for (Grave gr : plugin.getGraves()) {
									double distance = gr.getDistance(p);
									if (distance < 10 && distance >= 0) {
										gr.removeGrave(GraveRemoveReason.ADMIN_REMOVED);
										amount++;
									}
								}
								for (Entity entity : p.getNearbyEntities(10, 10, 10)) {
									if (entity.getType().equals(EntityType.ARMOR_STAND)
											|| entity.getType().equals(EntityType.ITEM_DISPLAY)) {
										if (entity.getPersistentDataContainer().has(plugin.getKey(),
												PersistentDataType.INTEGER)) {
											int value = entity.getPersistentDataContainer().get(plugin.getKey(),
													PersistentDataType.INTEGER);
											if (value == 1) {
												entity.remove();
												amount++;
											}
										}

									}
								}
								sendMessage(sender, "Finished removing armor stands in a radius of 10, removed "
										+ amount + " armor stands");
							}
						}, p.getLocation());

					}
				});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Teleport" }, "GraveStonesPlus.Teleport",
				"Teleport to latest grave", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Grave grave = plugin.getLatestGrave((Player) sender);
				if (grave != null) {
					Location loc = grave.getGravesConfig().getLocation();
					Player p = (Player) sender;
					plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							p.teleport(loc.clone().add(0, 1, 0));
						}
					}, loc);
				} else {
					sendMessage(sender, "&cYou don't have a grave");
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Compass" }, "GraveStonesPlus.Compass",
				"Get compass to last grave", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Grave grave = plugin.getGraveNavigation().target((Player) sender);
                if (grave == null) grave = plugin.getLatestGrave((Player) sender);
				if (grave != null) {
					grave.giveCompass((Player) sender);
				} else {
					sendMessage(sender, "&cYou don't have a grave");
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "ClaimGrave" }, "GraveStonesPlus.ClaimGrave",
				"Claim Grave nearby", false) {

            @Override
            public void execute(CommandSender sender, String[] args) {
                if (!(sender instanceof Player p)) return;
                ownerDispatch(p, "GraveStonesPlus.ClaimGrave").accept(() -> {
				List<Grave> graves = plugin.activeGraveSnapshot();
				if (graves != null) {
					for (Grave grave : graves) {
                        if (!plugin.isLiveGrave(grave) || plugin.getGraveClaimLedger().isBlocked(
                                com.bencodez.gravestonesplus.graves.GraveIdentity.of(grave.getGravesConfig()))) continue;
						double distance = grave.getDistance(p);
						if (distance < plugin.getConfigFile().getGraveClaimDistance() && distance >= 0) {
							if (!plugin.canRecover(grave, p) && !grave.legacyClaimAllowed(p)) continue;
                            grave.claimNearby(p);
							return;
						}
					}
					sendMessage(sender, "&cNo graves found");
				} else {
					sendMessage(sender, "&cYou don't have a grave");
				}
                });
            }
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "ViewGrave" }, "GraveStonesPlus.ViewGrave",
				"View current grave items that you are looking at", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Player p = (Player) sender;
				Block b = p.getTargetBlock(null, 7);
				for (Grave grave : plugin.getGraves()) {
					if (grave.isGrave(b)) {
						grave.openGUIWithItems(p);
					}
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "GenerateGrave", "(Player)" },
				"GraveStonesPlus.GenerateGrave", "Generate a grave for a player (test command)", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				if (!(sender instanceof Player playerSender)) {
					sender.sendMessage("Only players can use this command");
					return;
				}

				if (args.length < 2) {
					sender.sendMessage("/gravestonesplus gengrave <player>");
					return;
				}

				String playerName = args[1];
				Player online = plugin.getServer().getPlayerExact(playerName);

				UUID uuid;
				String name;

				if (online != null) {
					uuid = online.getUniqueId();
					name = online.getName();
				} else {
					@SuppressWarnings("deprecation")
					OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
					uuid = offline.getUniqueId();
					name = offline.getName() != null ? offline.getName() : playerName;
				}

				Location location = playerSender.getLocation();

				HashMap<Integer, ItemStack> items = generateRandomItems();
				int exp = ThreadLocalRandom.current().nextInt(5, 51);
				String deathMessage = name + " died mysteriously (dev test)";

				createTestGrave(uuid, name, location, items, exp, deathMessage, sender);
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Track" }, "GraveStonesPlus.Track", "Track the grave you are looking at", false) {
			@Override public void execute(CommandSender sender, String[] args) {
				if (!(sender instanceof Player player)) return;
				plugin.getBukkitScheduler().runTask(plugin, () -> {
					if (!plugin.isEnabled() || !player.isOnline() || !player.hasPermission("GraveStonesPlus.Track")) return;
					Grave grave = targetGrave(player);
					if (grave == null || !plugin.getGraveNavigation().select(player, grave)) { sendMessage(sender, "&cYou are not allowed to track that grave."); return; }
					sendMessage(sender, "&aSelected grave: " + grave.getNavigationMessage(player));
				}, player);
			}
		});
		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "TrustList" }, "GraveStonesPlus.Trust", "List helper grants for the grave you are looking at", false) {
			@Override public void execute(CommandSender sender, String[] args) {
				if (!(sender instanceof Player owner)) return;
                ownerDispatch(owner, "GraveStonesPlus.Trust").accept(() -> {
                    Grave grave = ownerGrave(owner);
                    if (grave == null || !grave.isOwner(owner) || !plugin.isLiveGrave(grave)) { sendMessage(sender, "&cLook at your grave first."); return; }
                    for (TrustedGraveAccess grant : plugin.getTrustedGraveAccessStore().list(com.bencodez.gravestonesplus.graves.GraveIdentity.of(grave.getGravesConfig()))) sendMessage(sender, "&7" + grant.getHelper().toString() + " &f" + grant.getKind() + " until " + new java.util.Date(grant.getExpiresAt()));
                });
			}
		});
		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "Trust", "(GraveHelper)", "(GraveAccessKind)", "(GraveDuration)" }, "GraveStonesPlus.Trust", "Grant bounded tracking or recovery access", false) {
			@Override public void execute(CommandSender sender, String[] args) {
				if (!(sender instanceof Player owner)) return;
                var dispatch = ownerDispatch(owner, "GraveStonesPlus.Trust");
                dispatch.accept(() -> {
                    if (args.length != 4) { sendMessage(sender, "&cUsage: /gravestonesplus trust <player> <track|recover> <duration>"); return; }
                    TrustedGraveAccess.Kind kind;
                    try { kind = TrustedGraveAccess.Kind.valueOf(args[2].toUpperCase(java.util.Locale.ROOT)); }
                    catch (IllegalArgumentException invalid) { sendMessage(sender, "&cType must be track or recover."); return; }
                    try {
                        long duration = com.bencodez.simpleapi.time.ParsedDuration.parse(args[3], java.util.concurrent.TimeUnit.MILLISECONDS).getMillis();
                        if (duration <= 0 || duration > 90L * 24 * 60 * 60 * 1000) throw new IllegalArgumentException();
                        java.util.UUID helper = knownHelper(args[1]);
                        if (helper == null) { sendMessage(sender, "&cUse an online player name or a UUID."); return; }
                        Grave grave = ownerGrave(owner); if (grave == null || !grave.isOwner(owner) || !plugin.isLiveGrave(grave)) { sendMessage(sender, "&cLook at your grave first."); return; }
                        grave.grantHelper(owner, helper, kind, System.currentTimeMillis() + duration)
                                .whenComplete((saved, failure) -> dispatch.accept(() -> sendMessage(sender,
                                        failure == null && Boolean.TRUE.equals(saved) ? "&aGrant saved. Tracking and recovery remain separate." : "&cGrant was not durably saved.")));
                    } catch (Exception e) { sendMessage(sender, "&cDuration must be valid and no longer than 90 days."); }
                });
			}
		});
		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "RevokeTrust", "(GraveHelper)", "(GraveAccessKind)" }, "GraveStonesPlus.Trust.Revoke", "Revoke helper access", false) {
			@Override public void execute(CommandSender sender, String[] args) {
				if (!(sender instanceof Player owner)) return;
                var dispatch = ownerDispatch(owner, "GraveStonesPlus.Trust.Revoke");
                dispatch.accept(() -> {
                    if (args.length != 3) return;
                    TrustedGraveAccess.Kind kind;
                    try { kind = TrustedGraveAccess.Kind.valueOf(args[2].toUpperCase(java.util.Locale.ROOT)); }
                    catch (IllegalArgumentException invalid) { sendMessage(sender, "&cType must be track or recover."); return; }
                    java.util.UUID helper = knownHelper(args[1]);
                    if (helper == null) { sendMessage(sender, "&cUse an online player name or a UUID."); return; }
                    Grave grave = ownerGrave(owner); if (grave == null || !grave.isOwner(owner) || !plugin.isLiveGrave(grave)) return;
                    grave.revokeHelper(owner, helper, kind)
                            .whenComplete((removed, failure) -> dispatch.accept(() -> sendMessage(sender,
                                    failure != null || !Boolean.TRUE.equals(removed) ? "&cGrant revocation could not be saved; access is denied locally." : "&aGrant revoked.")));
                });
			}
		});
		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "InspectItem" }, "GraveStonesPlus.InspectItem", "Explain held-item rule context", false) {
			@Override public void execute(CommandSender sender, String[] args) {
				if (!(sender instanceof Player player)) return;
                ownerDispatch(player, "GraveStonesPlus.InspectItem").accept(() -> {
                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item == null || item.getType().isAir()) { sendMessage(sender, "&eHold an item to inspect."); return; }
                    sendMessage(sender, new ArrayList<>(com.bencodez.gravestonesplus.listeners.ItemRuleEvaluator.inspect(plugin, player, item)));
                });
			}
		});

	}

    /** The bundled dispatcher is async. Reuse reload's existing authorization fence for owner work and replies. */
    private java.util.function.Consumer<Runnable> ownerDispatch(Player player, String permission) {
        var manager = plugin.getPlayerBreakManager();
        long generation = manager == null ? 0 : manager.getAuthorizationGeneration().get();
        return action -> plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline() || !player.hasPermission(permission)
                    || plugin.getPlayerBreakManager() != manager
                    || manager != null && manager.getAuthorizationGeneration().get() != generation) return;
            action.run();
        }, player);
    }

    private UUID knownHelper(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
        Player online = Bukkit.getPlayerExact(value);
        if (online != null) return online.getUniqueId();
        // Cached lookup avoids profile/network I/O on a gameplay callback.
        return null;
    }
    private Grave ownerGrave(Player player) {
        Grave selected = plugin.getGraveNavigation().target(player);
        if (selected != null && selected.isOwner(player)) return selected;
        return targetGrave(player);
    }

	private Grave targetGrave(Player player) {
		Block block = player.getTargetBlock(null, 7);
		for (Grave grave : plugin.activeGraveSnapshot()) if (plugin.isLiveGrave(grave) && grave.isGrave(block)) return grave;
		return null;
	}

	public void createTestGrave(UUID uuid, String name, Location deathLocation,
			HashMap<Integer, ItemStack> itemsWithSlot, int droppedExp, String deathMessage, CommandSender sender) {
		if (plugin.getConfigFile().getDisabledWorlds().contains(deathLocation.getWorld().getName())) {
			sender.sendMessage("World is disabled for graves");
			return;
		}

		Location emptyBlock;
		if (deathLocation.getBlock().isEmpty() && deathLocation.getBlockY() > deathLocation.getWorld().getMinHeight()
				&& deathLocation.getBlockY() < deathLocation.getWorld().getMaxHeight()) {
			emptyBlock = deathLocation;
		} else {
			emptyBlock = getAirBlock(deathLocation); // or move helper elsewhere
		}

		if (emptyBlock == null) {
			sender.sendMessage("Failed to find air block for grave");
			return;
		}
		final Location emptyBlockFinal = emptyBlock;

		GraveCreationTask creation = new GraveCreationTask(plugin, () -> {
			Grave grave = new Grave(plugin,
					new GravesConfig(uuid, name, emptyBlockFinal, itemsWithSlot, droppedExp, deathMessage,
							System.currentTimeMillis(), false, 0, null, null,
							plugin.getConfigFile().getGraveDisplayTypeEnum().name()));

			grave.loadChunk(false);
			grave.createGrave();
			grave.createHologram();
			grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
			plugin.addGrave(grave);
			grave.loadBlockMeta(emptyBlockFinal.getBlock());

			sender.sendMessage("Generated grave for " + name + " at " + emptyBlockFinal.getBlockX() + ", "
					+ emptyBlockFinal.getBlockY() + ", " + emptyBlockFinal.getBlockZ());
		});
        creation.schedule(emptyBlockFinal);
        boolean accepted = false;
        try {
            if (plugin.numberOfGraves(uuid) >= plugin.getConfigFile().getGraveLimit()) {
                Grave oldest = plugin.getOldestGrave(uuid);
                if (oldest != null) {
                    if (plugin.getConfigFile().isDropItemsOnGraveRemoval()) {
                        oldest.dropAndRemoveForLimit(null);
                    } else oldest.removeGrave(GraveRemoveReason.LIMIT_REACHED);
                }
            }
            creation.accept();
            accepted = true;
        } finally {
            if (!accepted) creation.cancel();
        }
	}

	private HashMap<Integer, ItemStack> generateRandomItems() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		HashMap<Integer, ItemStack> items = new HashMap<>();

		Material[] materials = new Material[] { Material.DIAMOND_SWORD, Material.IRON_SWORD, Material.BREAD,
				Material.COOKED_BEEF, Material.TORCH, Material.COBBLESTONE, Material.OAK_LOG, Material.DIAMOND,
				Material.IRON_INGOT, Material.GOLD_INGOT, Material.EMERALD, Material.ARROW, Material.BOW,
				Material.SHIELD };

		int stacks = random.nextInt(5, 13);

		for (int i = 0; i < stacks; i++) {
			int slot = random.nextInt(36);
			Material mat = materials[random.nextInt(materials.length)];
			int amount = mat.getMaxStackSize() == 1 ? 1 : random.nextInt(1, Math.min(32, mat.getMaxStackSize()) + 1);
			items.put(slot, new ItemStack(mat, amount));
		}

		if (random.nextBoolean())
			items.put(-1, new ItemStack(Material.DIAMOND_HELMET));
		if (random.nextBoolean())
			items.put(-2, new ItemStack(Material.IRON_CHESTPLATE));
		if (random.nextBoolean())
			items.put(-3, new ItemStack(Material.CHAINMAIL_LEGGINGS));
		if (random.nextBoolean())
			items.put(-4, new ItemStack(Material.GOLDEN_BOOTS));
		if (random.nextBoolean())
			items.put(-5, new ItemStack(Material.SHIELD));

		return items;
	}

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
