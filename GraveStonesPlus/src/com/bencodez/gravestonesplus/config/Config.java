package com.bencodez.gravestonesplus.config;

import java.io.File;
import java.util.ArrayList;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.yml.YMLFile;
import com.bencodez.advancedcore.api.yml.annotation.AnnotationHandler;
import com.bencodez.advancedcore.api.yml.annotation.ConfigDataBoolean;
import com.bencodez.advancedcore.api.yml.annotation.ConfigDataInt;
import com.bencodez.advancedcore.api.yml.annotation.ConfigDataListString;
import com.bencodez.advancedcore.api.yml.annotation.ConfigDataString;

import lombok.Getter;

public class Config extends YMLFile {
	public Config(AdvancedCorePlugin plugin) {
		super(plugin, new File(plugin.getDataFolder(), "Config.yml"), true);
	}

	@Override
	public void loadValues() {
		new AnnotationHandler().load(getData(), this);
	}

	@Override
	public void onFileCreation() {
		getPlugin().saveResource("Config.yml", true);
	}

	@Getter
	@ConfigDataBoolean(path = "BreakOtherGravesWithPermission")
	private boolean breakOtherGravesWithPermission = false;

	@Getter
	@ConfigDataListString(path = "DisabledWorlds")
	private ArrayList<String> disabledWorlds = new ArrayList<String>();

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

	@Getter
	@ConfigDataString(path = "Format.Death")
	private String formatDeath = "Your grave is at %x%, %y%, %z%";
	@Getter
	@ConfigDataString(path = "Format.GraveBroke")
	private String formatGraveBroke = "You broke your grave!";
	@Getter
	@ConfigDataString(path = "Format.ItemsNotInCorrectSite")
	private String formatItemsNotInGrave = "Some items didn't return to the correct slot";
	@Getter
	@ConfigDataString(path = "Format.GraveLimitBreak")
	private String formatGraveLimitBreak = "Breaking oldest grave due to limit reached";
	@Getter
	@ConfigDataString(path = "Format.NotYourGrave")
	private String formatNotYourGrave = "Not your grave!";

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
