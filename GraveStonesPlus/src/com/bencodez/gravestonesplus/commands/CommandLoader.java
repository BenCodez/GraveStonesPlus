package com.bencodez.gravestonesplus.commands;

import org.bukkit.command.CommandSender;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.gravestonesplus.GraveStonesPlus;

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
	}
}
