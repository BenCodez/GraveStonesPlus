package com.bencodez.gravestonesplus.config;

import java.io.File;
import java.util.ArrayList;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.yml.YMLFile;
import com.bencodez.advancedcore.api.yml.annotation.AnnotationHandler;
import com.bencodez.advancedcore.api.yml.annotation.ConfigDataBoolean;
import com.bencodez.advancedcore.api.yml.annotation.ConfigDataInt;
import com.bencodez.advancedcore.api.yml.annotation.ConfigDataListString;

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
	@ConfigDataInt(path = "GlowingEffectDistance")
	private int glowingEffectDistance = 30;

	@Getter
	@ConfigDataInt(path = "GraveTimeLimit")
	private int GraveTimeLimit = -1;
}
