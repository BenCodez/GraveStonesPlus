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
    private volatile RulesSnapshot rules = new RulesSnapshot(List.of(), List.of(), List.of());

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


        List<NBTRule> ignore = loadRulesFromSection("ignore_nbt_rules");
        List<NBTRule> keep = loadRulesFromSection("keep_nbt_rules");
        List<NBTRule> grave = loadRulesFromSection("grave_nbt_rules");
        String keepLore = plugin.getConfigFile() == null ? "" : plugin.getConfigFile().getKeepItemsWithLore();
        RulesSnapshot loaded = new RulesSnapshot(ignore, keep, grave, keepLore == null ? "" : keepLore);
        rules = loaded;

        plugin.getLogger().info("Loaded " + loaded.ignore().size() + " NBT ignore rules.");
        plugin.getLogger().info("Loaded " + loaded.keep().size() + " NBT keep rules.");
        plugin.getLogger().info("Loaded " + loaded.grave().size() + " NBT grave rules.");
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
        return rules.ignore();
    }

    public List<NBTRule> getKeepNbtRules() {
        return rules.keep();
    }

    public List<NBTRule> getGraveNbtRules() {
        return rules.grave();
    }

    public RulesSnapshot snapshot() { return rules; }

    public record RulesSnapshot(List<NBTRule> ignore, List<NBTRule> keep, List<NBTRule> grave, String keepLore) {
        public RulesSnapshot(List<NBTRule> ignore, List<NBTRule> keep, List<NBTRule> grave) { this(ignore, keep, grave, ""); }
        public RulesSnapshot { ignore = List.copyOf(ignore); keep = List.copyOf(keep); grave = List.copyOf(grave); }
    }


    public void reloadConfig() {
        loadConfig();
    }
}
