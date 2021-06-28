package com.bencodez.gravestonesplus.commands.executor;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.gravestonesplus.GraveStonesPlus;

public class CommandGraveStonesPlus implements CommandExecutor {

	private  GraveStonesPlus plugin;

	/**
	 * Instantiates a new command vote.
	 *
	 * @param plugin the plugin
	 */
	public CommandGraveStonesPlus(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.bukkit.command.CommandExecutor#onCommand(org.bukkit.command.
	 * CommandSender , org.bukkit.command.Command, java.lang.String,
	 * java.lang.String[])
	 */
	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

		for (CommandHandler commandHandler : plugin.getCommands()) {
			if (commandHandler.runCommand(sender, args)) {
				return true;
			}
		}

		// invalid command
		sender.sendMessage(ChatColor.RED + "No valid arguments, see /gravestonesplus help!");
		return true;
	}
}
