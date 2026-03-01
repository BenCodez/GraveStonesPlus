package com.bencodez.gravestonesplus.config;

import java.io.File;
import java.util.ArrayList;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.gravestonesplus.graves.GraveDisplayType;
import com.bencodez.simpleapi.file.YMLFile;
import com.bencodez.simpleapi.file.annotation.AnnotationHandler;
import com.bencodez.simpleapi.file.annotation.ConfigDataBoolean;
import com.bencodez.simpleapi.file.annotation.ConfigDataInt;
import com.bencodez.simpleapi.file.annotation.ConfigDataListString;
import com.bencodez.simpleapi.file.annotation.ConfigDataString;

import lombok.Getter;

/**
 * Plugin configuration.
 */
public class Config extends YMLFile {

	/**
	 * Creates the config instance.
	 *
	 * @param plugin Plugin instance
	 */
	public Config(AdvancedCorePlugin plugin) {
		super(plugin, new File(plugin.getDataFolder(), "Config.yml"), true);
	}

	@Override
	public void loadValues() {
		migrateLegacyConfigKeys();
		new AnnotationHandler().load(getData(), this);
	}

	@Override
	public void onFileCreation() {
		getPlugin().saveResource("Config.yml", true);
	}

	/**
	 * Converts legacy config keys to the new config structure. This allows older
	 * Config.yml versions to continue working.
	 */
	private void migrateLegacyConfigKeys() {
		/*
		 * Migration: UseDisplayEntities (boolean) -> GraveDisplayType (string)
		 *
		 * Old: UseDisplayEntities: true/false
		 *
		 * New: GraveDisplayType: PLAYER_HEAD / DISPLAY_ENTITY / CHEST
		 */
		if (!getData().isSet("GraveDisplayType")) {
			if (getData().isSet("UseDisplayEntities")) {
				boolean legacyUseDisplayEntities = getData().getBoolean("UseDisplayEntities", false);
				getData().set("GraveDisplayType", legacyUseDisplayEntities ? GraveDisplayType.DISPLAY_ENTITY.name()
						: GraveDisplayType.PLAYER_HEAD.name());
			} else {
				getData().set("GraveDisplayType", GraveDisplayType.PLAYER_HEAD.name());
			}

			saveData();
		}
	}

	@Getter
	@ConfigDataBoolean(path = "BreakOtherGravesWithPermission")
	private boolean breakOtherGravesWithPermission = false;

	@Getter
	@ConfigDataBoolean(path = "GiveCompassOnRespawn")
	private boolean giveCompassOnRespawn = false;

	@Getter
	@ConfigDataListString(path = "DisabledWorlds")
	private ArrayList<String> disabledWorlds = new ArrayList<String>();

	@Getter
	@ConfigDataBoolean(path = "KeepAllExp")
	private boolean keepAllExp = false;

	@Getter
	@ConfigDataBoolean(path = "DropItemsOnGraveRemoval")
	private boolean dropItemsOnGraveRemoval = true;

	@Getter
	@ConfigDataBoolean(path = "GlowingEffectNearGrave")
	private boolean glowingEffectNearGrave = true;

	@Getter
	@ConfigDataBoolean(path = "DisableArmorStands")
	private boolean disableArmorStands = false;

	@Getter
	@ConfigDataInt(path = "GlowingEffectDistance")
	private int glowingEffectDistance = 30;

	@Getter
	@ConfigDataInt(path = "GraveTimeLimit")
	private int graveTimeLimit = -1;

	@Getter
	@ConfigDataInt(path = "BrokenGraveTimeLimit")
	private int bokenGraveTimeLimit = 96;

	@Getter
	@ConfigDataInt(path = "GraveClaimDistance")
	private int graveClaimDistance = 10;

	@Getter
	@ConfigDataInt(path = "PercentageDrops")
	private int percentageDrops = 100;

	@Getter
	@ConfigDataInt(path = "GraveLimit")
	private int graveLimit = 10;

	@ConfigDataString(path = "KeepItemsWithLore")
	@Getter
	private String keepItemsWithLore = "";

	/**
	 * New display option for graves. Stored as a string in config for compatibility
	 * with annotation loading.
	 */
	@ConfigDataString(path = "GraveDisplayType")
	@Getter
	private String graveDisplayType = GraveDisplayType.PLAYER_HEAD.name();

	/**
	 * Legacy option, replaced by GraveDisplayType. Only kept for backward
	 * compatibility during migration.
	 */
	@Deprecated
	@ConfigDataBoolean(path = "UseDisplayEntities")
	@Getter
	private boolean useDisplayEntities = false;

	/**
	 * Gets the grave display type as an enum. Defaults to PLAYER_HEAD if config is
	 * invalid.
	 *
	 * @return Grave display type
	 */
	public GraveDisplayType getGraveDisplayTypeEnum() {
		try {
			return GraveDisplayType.valueOf(graveDisplayType.toUpperCase());
		} catch (Exception e) {
			return GraveDisplayType.PLAYER_HEAD;
		}
	}

	@ConfigDataBoolean(path = "CreateGraveForEmptyInventories")
	@Getter
	private boolean createGraveForEmptyInventories = false;

	@ConfigDataBoolean(path = "GiveBreakOtherGravesPermission")
	@Getter
	private boolean giveBreakOtherGravesPermission = false;

	@Getter
	@ConfigDataString(path = "Format.Death")
	private String formatDeath = "Your grave is at %x%, %y%, %z%";

	@Getter
	@ConfigDataString(path = "Format.GraveBroke")
	private String formatGraveBroke = "You broke your grave!";

	@Getter
	@ConfigDataString(path = "Format.ItemsNotInCorrectSlot")
	private String formatItemsNotInGrave = "Some items didn't return to the correct slot";

	@Getter
	@ConfigDataString(path = "Format.GraveLimitBreak")
	private String formatGraveLimitBreak = "Breaking oldest grave due to limit reached";

	@Getter
	@ConfigDataString(path = "Format.NotYourGrave")
	private String formatNotYourGrave = "Not your grave!";

	@Getter
	@ConfigDataString(path = "Format.ClickMessage")
	private String formatClickMessage = "%player%'s grave. Died at %time%. Reason: %reason%";

	@Getter
	@ConfigDataBoolean(path = "Format.Help.RequirePermission")
	private boolean formatHelpRequirePermission = true;

	@Getter
	@ConfigDataString(path = "Format.Grave.Top")
	private String formatGraveTop = "%player% died here!";

	@Getter
	@ConfigDataString(path = "Format.Grave.Middle")
	private String formatGraveMiddle = "Died at %time%";

	@Getter
	@ConfigDataString(path = "Format.Grave.Bottom")
	private String formatGraveBottom = "Reason: %reason%";
}