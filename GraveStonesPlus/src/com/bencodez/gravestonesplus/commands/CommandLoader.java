package com.bencodez.gravestonesplus.commands;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;

public class CommandLoader {
	private GraveStonesPlus plugin;

	public CommandLoader(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	public void loadCommands() {
		plugin.getCommands()
				.add(new CommandHandler(new String[] { "Reload" }, "GraveStonesPlus.Reload", "Reload the plugin") {

					@Override
					public void execute(CommandSender sender, String[] args) {
						plugin.reload();
						sendMessage(sender, "&cGraveStonesPlus reloaded");
					}
				});

		plugin.getCommands().add(
				new CommandHandler(new String[] { "Graves" }, "GraveStonesPlus.Graves", "See current graves", false) {

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

		plugin.getCommands().add(new CommandHandler(new String[] { "Graves", "(player)" },
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

		plugin.getCommands().add(new CommandHandler(new String[] { "Teleport" }, "GraveStonesPlus.Teleport",
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
	}
}
