package com.bencodez.gravestonesplus.config;

import java.io.File;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.yml.YMLFile;
import com.bencodez.advancedcore.api.yml.annotation.AnnotationHandler;

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
}
