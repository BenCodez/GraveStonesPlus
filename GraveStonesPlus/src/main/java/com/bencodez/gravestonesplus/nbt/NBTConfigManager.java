package com.bencodez.gravestonesplus.nbt;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class NBTConfigManager {
    private GraveStonesPlus plugin;
    private FileConfiguration config;
    private List<NBTRule> ignoreNbtRules;
    private List<NBTRule> keepNbtRules;
    private List<NBTRule> graveNbtRules;

    public NBTConfigManager(GraveStonesPlus plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "nbt_rules.yml");
        if (!file.exists()) {
            plugin.saveResource("nbt_rules.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(file);


        this.ignoreNbtRules = loadRulesFromSection("ignore_nbt_rules");
        this.keepNbtRules = loadRulesFromSection("keep_nbt_rules");
        this.graveNbtRules = loadRulesFromSection("grave_nbt_rules");

        plugin.getLogger().info("Loaded " + ignoreNbtRules.size() + " NBT ignore rules.");
        plugin.getLogger().info("Loaded " + keepNbtRules.size() + " NBT keep rules.");
        plugin.getLogger().info("Loaded " + graveNbtRules.size() + " NBT grave rules.");
    }

    private List<NBTRule> loadRulesFromSection(String sectionName) {
        List<NBTRule> rules = new ArrayList<>();
        if (config.isConfigurationSection(sectionName)) {
            ConfigurationSection section = config.getConfigurationSection(sectionName);
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String path = section.getString(key + ".path");
                    String type = section.getString(key + ".type");
                    Object value = section.get(key + ".value");

                    if (path != null && type != null) {
                        rules.add(new NBTRule(key, path, type, value));
                    } else {
                        plugin.getLogger().warning("Invalid NBT rule in section '" + sectionName + "', key '" + key + "'. Missing path or type.");
                    }
                }
            }
        }
        return rules;
    }


    public List<NBTRule> getIgnoreNbtRules() {
        return ignoreNbtRules;
    }

    public List<NBTRule> getKeepNbtRules() {
        return keepNbtRules;
    }

    public List<NBTRule> getGraveNbtRules() {
        return graveNbtRules;
    }


    public void reloadConfig() {
        loadConfig();
    }
}