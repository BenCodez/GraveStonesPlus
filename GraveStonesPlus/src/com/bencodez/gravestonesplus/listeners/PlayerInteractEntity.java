package com.bencodez.gravestonesplus.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;

// TODO: Auto-generated Javadoc
/**
 * The Class PlayerInteract.
 */
public class PlayerInteractEntity implements Listener {

	/** The plugin. */
	@SuppressWarnings("unused")
	private GraveStonesPlus plugin;

	/**
	 * Instantiates a new player interact.
	 *
	 * @param plugin the plugin
	 */
	public PlayerInteractEntity(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onEntityInteract(PlayerInteractAtEntityEvent event) {
		Entity clicked = event.getRightClicked();
		if (clicked.getType().equals(EntityType.ARMOR_STAND)) {
			Object obj = MiscUtils.getInstance().getEntityMeta(clicked, "Grave");
			if (obj == null) {
				return;
			}
			Grave grave = (Grave) obj;
			long lastClick = grave.getLastClick();
			long cTime = System.currentTimeMillis();
			grave.setLastClick(cTime);
			if (cTime - lastClick > 500) {
				grave.onClick(event.getPlayer());
			}
		}

	}
}
