package com.bencodez.gravestonesplus.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.persistence.PersistentDataType;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.inventory.BInventory;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;

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
		plugin.getCommands().add(
				new CommandHandler(plugin, new String[] { "Reload" }, "GraveStonesPlus.Reload", "Reload the plugin") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						plugin.reload();
						sendMessage(sender, "&cGraveStonesPlus reloaded");
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
				List<Grave> graves = plugin.getGraves((Player) sender);
				ArrayList<String> msg = new ArrayList<String>();
				msg.add("Current graves:");
				for (Grave gr : graves) {
					msg.add(gr.getGraveMessage());
				}
				sendMessage(sender, msg);
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
				Set<Grave> graves = plugin.getBrokenGraves();
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
				Set<Grave> graves = plugin.getBrokenGraves();
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
						gr.removeGrave();
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
						Bukkit.getScheduler().runTask(plugin, new Runnable() {

							@Override
							public void run() {

								int amount = 0;
								for (Grave gr : plugin.getGraves()) {
									double distance = gr.getDistance(p);
									if (distance < 10 && distance >= 0) {
										gr.removeGrave();
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
						});

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
					Bukkit.getScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							p.teleport(loc.clone().add(0, 1, 0));
						}
					});
				} else {
					sendMessage(sender, "&cYou don't have a grave");
				}
			}
		});

		plugin.getCommands().add(new CommandHandler(plugin, new String[] { "ClaimGrave" }, "GraveStonesPlus.ClaimGrave",
				"Claim Grave nearby", false) {

			@Override
			public void execute(CommandSender sender, String[] args) {
				Player p = (Player) sender;
				List<Grave> graves = plugin.getGraves(p);
				if (graves != null) {
					for (Grave grave : graves) {
						double distance = grave.getDistance(p);
						if (distance < plugin.getConfigFile().getGraveClaimDistance() && distance >= 0) {
							grave.removeSkull();
							grave.claim(p, p.getInventory());
							return;
						}
					}
					sendMessage(sender, "&cNo graves found");
				} else {
					sendMessage(sender, "&cYou don't have a grave");
				}
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
	}
}
