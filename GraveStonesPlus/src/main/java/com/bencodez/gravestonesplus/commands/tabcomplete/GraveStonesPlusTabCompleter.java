package com.bencodez.gravestonesplus.commands.tabcomplete;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import com.bencodez.advancedcore.api.command.AdvancedCoreTabCompleteHandler;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.simpleapi.messages.MessageAPI;

// TODO: Auto-generated Javadoc
/**
 * The Class VoteTabCompleter.
 */
public class GraveStonesPlusTabCompleter implements TabCompleter {

	/** The plugin. */
	private GraveStonesPlus plugin;

	public GraveStonesPlusTabCompleter(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.bukkit.command.TabCompleter#onTabComplete(org.bukkit.command.
	 * CommandSender, org.bukkit.command.Command, java.lang.String,
	 * java.lang.String[])
	 */
	@Override
	public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {

		ArrayList<String> tab = new ArrayList<String>();

		Set<String> cmds = new HashSet<String>();

		cmds.addAll(AdvancedCoreTabCompleteHandler.getInstance().getTabCompleteOptions(plugin.getCommands(), sender,
				args, args.length - 1));

		for (String str : cmds) {
			if (MessageAPI.startsWithIgnoreCase(str, args[args.length - 1])) {
				tab.add(str);
			}
		}

		Collections.sort(tab, String.CASE_INSENSITIVE_ORDER);

		return tab;
	}

}
