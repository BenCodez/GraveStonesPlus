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
import com.bencodez.simpleapi.file.annotation.ConfigDataParsedDuration;
import com.bencodez.simpleapi.file.annotation.ConfigDataString;
import com.bencodez.simpleapi.time.ParsedDuration;

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
	 * Converts legacy config keys to the new config structure when useful.
	 */
	private void migrateLegacyConfigKeys() {
		boolean changed = false;

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
			changed = true;
		}

		/*
		 * Migration: GlowingEffectNearGrave -> GlowingEffect.Enabled
		 * GlowingEffectDistance -> GlowingEffect.Distance
		 */
		if (!getData().isSet("GlowingEffect.Enabled") && getData().isSet("GlowingEffectNearGrave")) {
			getData().set("GlowingEffect.Enabled", getData().getBoolean("GlowingEffectNearGrave"));
			changed = true;
		}

		if (!getData().isSet("GlowingEffect.Distance") && getData().isSet("GlowingEffectDistance")) {
			getData().set("GlowingEffect.Distance", getData().getInt("GlowingEffectDistance"));
			changed = true;
		}

		if (changed) {
			saveData();
		}
	}

	@Getter
	@ConfigDataBoolean(path = "Breaking.Own.RequireBreakTime", secondPath = "Breaking.Default.RequireBreakTime")
	private boolean breakOwnGraveRequireBreakTime = false;

	@Getter
	@ConfigDataParsedDuration(path = "Breaking.Own.BreakTime", secondPath = "Breaking.Default.BreakTime")
	private ParsedDuration breakOwnGraveTime = ParsedDuration.ofMillis(20000);

	@Getter
	@ConfigDataParsedDuration(path = "Breaking.Own.HitTimeout", secondPath = "Breaking.Default.HitTimeout")
	private ParsedDuration breakOwnGraveHitTimeout = ParsedDuration.ofMillis(5000);

	@Getter
	@ConfigDataBoolean(path = "Breaking.Own.SendMessage", secondPath = "Breaking.Default.SendMessage")
	private boolean breakOwnGraveSendMessage = true;

	@Getter
	@ConfigDataBoolean(path = "Breaking.Own.ActionBarMessage", secondPath = "Breaking.Default.ActionBarMessage")
	private boolean breakOwnGraveActionBarMessage = true;

	/**
	 * Enables breaking other players' graves.
	 */
	@Getter
	@ConfigDataBoolean(path = "Breaking.Other.Enabled", secondPath = "BreakOtherGraves.Enabled")
	private boolean breakOtherGravesEnabled = false;

	@Getter
	@ConfigDataBoolean(path = "Breaking.Other.RequirePermission", secondPath = "BreakOtherGraves.RequirePermission")
	private boolean breakOtherGravesRequirePermission = true;

	@Getter
	@ConfigDataParsedDuration(path = "Breaking.Other.TimeOutBeforeBreakable")
	private ParsedDuration breakOtherGravesTimeBeforeBreakable = ParsedDuration.ofMillis(0);

	@Getter
	@ConfigDataBoolean(path = "Breaking.Other.RequireBreakTime", secondPath = "Breaking.Default.RequireBreakTime")
	private boolean breakOtherGravesRequireBreakTime = false;

	@Getter
	@ConfigDataParsedDuration(path = "Breaking.Other.BreakTime", secondPath = "Breaking.Default.BreakTime")
	private ParsedDuration breakOtherGravesTime = ParsedDuration.ofMillis(20000);

	@Getter
	@ConfigDataParsedDuration(path = "Breaking.Other.HitTimeout", secondPath = "Breaking.Default.HitTimeout")
	private ParsedDuration breakOtherGravesHitTimeout = ParsedDuration.ofMillis(5000);

	@Getter
	@ConfigDataBoolean(path = "Breaking.Other.SendMessage", secondPath = "Breaking.Default.SendMessage")
	private boolean breakOtherGravesSendMessage = true;

	@Getter
	@ConfigDataBoolean(path = "Breaking.Other.ActionBarMessage", secondPath = "Breaking.Default.ActionBarMessage")
	private boolean breakOtherGravesActionBarMessage = true;

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
	@ConfigDataBoolean(path = "GlowingEffect.Enabled", secondPath = "GlowingEffectNearGrave")
	private boolean glowingEffectNearGrave = true;

	@Getter
	@ConfigDataBoolean(path = "DisableArmorStands")
	private boolean disableArmorStands = false;

	@Getter
	@ConfigDataInt(path = "GlowingEffect.Distance", secondPath = "GlowingEffectDistance")
	private int glowingEffectDistance = 30;

	@Getter
	@ConfigDataString(path = "GraveTimeLimit")
	private String graveTimeLimit = "";

	@Getter
	@ConfigDataString(path = "BrokenGraveTimeLimit")
	private String brokenGraveTimeLimit = "7d";

	@Getter
	@ConfigDataInt(path = "GraveClaimDistance")
	private int graveClaimDistance = 10;

	@Getter
	@ConfigDataInt(path = "PercentageDrops")
	private int percentageDrops = 100;

	@Getter
	@ConfigDataInt(path = "GraveLimit")
	private int graveLimit = 10;

	@Getter
	@ConfigDataString(path = "KeepItemsWithLore")
	private String keepItemsWithLore = "";

	/**
	 * New display option for graves. Stored as a string in config for compatibility
	 * with annotation loading.
	 */
	@Getter
	@ConfigDataString(path = "GraveDisplayType")
	private String graveDisplayType = GraveDisplayType.PLAYER_HEAD.name();

	/**
	 * Legacy option, replaced by GraveDisplayType. Only kept for backward
	 * compatibility during migration.
	 */
	@Deprecated
	@Getter
	@ConfigDataBoolean(path = "UseDisplayEntities")
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

	@Getter
	@ConfigDataBoolean(path = "CreateGraveForEmptyInventories")
	private boolean createGraveForEmptyInventories = false;

	@Getter
	@ConfigDataBoolean(path = "GiveBreakOtherGravesPermission")
	private boolean giveBreakOtherGravesPermission = false;

	@Getter
	@ConfigDataBoolean(path = "CreateBackups")
	private boolean createBackups = false;

	@Getter
	@ConfigDataString(path = "DebugLevel")
	private String debugLevel = "NONE";

	@Getter
	@ConfigDataString(path = "Format.Death")
	private String formatDeath = "&aYour grave is at %x%, %y%, %z%";

	@Getter
	@ConfigDataString(path = "Format.GraveBroke")
	private String formatGraveBroke = "&aYou broke your grave!";

	@Getter
	@ConfigDataString(path = "Format.GraveBrokeOther")
	private String formatGraveBrokeOther = "&aYou broke %player%'s grave!";

	@Getter
	@ConfigDataString(path = "Format.StartedBreakingGrave")
	private String formatStartedBreakingGrave = "&aStarted breaking grave! Keep hitting to break it!";

	@Getter
	@ConfigDataString(path = "Format.StoppedBreakingGrave")
	private String formatStoppedBreakingGrave = "&aStopped breaking grave!";

	@Getter
	@ConfigDataString(path = "Format.ItemsNotInCorrectSlot")
	private String formatItemsNotInGrave = "&aSome items didn't return to the correct slot";

	@Getter
	@ConfigDataString(path = "Format.GraveLimitBreak")
	private String formatGraveLimitBreak = "&cBreaking oldest grave due to limit reached";

	@Getter
	@ConfigDataString(path = "Format.NotYourGrave")
	private String formatNotYourGrave = "&cNot your grave!";

	@Getter
	@ConfigDataString(path = "Format.ClickMessage")
	private String formatClickMessage = "&a%player%'s grave. Died at %time%. Reason: %reason%";

	@Getter
	@ConfigDataString(path = "Format.UnableToClaimDelay")
	private String formatUnableToClaimDelay = "&cNot able to claim yet! %time% left before you can claim this grave!";

	@Getter
	@ConfigDataString(path = "Format.NotEnoughPermission", secondPath = "Format.NonEnoughPermission")
	private String formatNotEnoughPermission = "&cYou don't have permission to do that!";

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