package com.bencodez.gravestonesplus.listeners;

import java.util.*;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.nbt.NBTRule;
import com.bencodez.gravestonesplus.nbt.NBTConfigManager.RulesSnapshot;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;

/** Shared ordered death routing. No random sampling, writes, or death mutation. */
public final class ItemRuleEvaluator {
    public enum Outcome { IGNORE, SOULBOUND, KEEP_LORE, KEEP_NBT, GRAVE_NBT, VANISH, GRAVE_DEFAULT, EMPTY }
    private final Predicate<ItemStack> ignore, soulbound, keep, grave, vanish;
    private final String lore;
    public ItemRuleEvaluator(Predicate<ItemStack> ignore, Predicate<ItemStack> soulbound, String lore,
            Predicate<ItemStack> keep, Predicate<ItemStack> grave) {
        this(ignore, soulbound, lore, keep, grave, ItemRuleEvaluator::vanishes);
    }
    public ItemRuleEvaluator(Predicate<ItemStack> ignore, Predicate<ItemStack> soulbound, String lore,
            Predicate<ItemStack> keep, Predicate<ItemStack> grave, Predicate<ItemStack> vanish) {
        this.ignore = ignore; this.soulbound = soulbound; this.lore = lore; this.keep = keep; this.grave = grave; this.vanish = vanish;
    }
    public static ItemRuleEvaluator forPlayer(GraveStonesPlus plugin, Player player) {
        final RulesSnapshot snapshot = plugin.isNbtAPIHooked() && plugin.getNbtConfigManager() != null
                ? plugin.getNbtConfigManager().snapshot() : null;
        return forPlayer(plugin, player, snapshot);
    }
    private static ItemRuleEvaluator forPlayer(GraveStonesPlus plugin, Player player, RulesSnapshot snapshot) {
        HashMap<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName()); placeholders.put("displayname", player.getDisplayName());
        String lore = PlaceholderUtils.replacePlaceHolder(snapshot == null ? plugin.getConfigFile().getKeepItemsWithLore() : snapshot.keepLore(), placeholders);
        return new ItemRuleEvaluator(item -> matches(plugin, item, 0, snapshot),
            item -> plugin.getSlimefun() != null && plugin.getSlimefun().isSoulBoundItem(item.clone()), lore,
            item -> matches(plugin, item, 1, snapshot), item -> matches(plugin, item, 2, snapshot));
    }
    private static boolean matches(GraveStonesPlus plugin, ItemStack item, int stage, RulesSnapshot snapshot) {
        if (!plugin.isNbtAPIHooked() || snapshot == null) return false;
        List<NBTRule> rules = switch (stage) { case 0 -> snapshot.ignore(); case 1 -> snapshot.keep(); default -> snapshot.grave(); };
        return new PlayerDeathNBTHandle().doesItemMatchAnyRule(plugin, item.clone(), rules);
    }
    public Outcome evaluate(ItemStack item) {
        if (item == null || item.getType().isAir()) return Outcome.EMPTY;
        if (ignore.test(item)) return Outcome.IGNORE;
        if (soulbound.test(item)) return Outcome.SOULBOUND;
        if (matchesLore(item, lore)) return Outcome.KEEP_LORE;
        if (keep.test(item)) return Outcome.KEEP_NBT;
        if (grave.test(item)) return Outcome.GRAVE_NBT;
        if (vanish.test(item)) return Outcome.VANISH;
        return Outcome.GRAVE_DEFAULT;
    }
    public static boolean matchesLore(ItemStack item, String text) {
        if (item == null || text == null || text.isEmpty() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return false;
        for (String line : meta.getLore()) if (line != null && line.equalsIgnoreCase(text)) return true;
        return false;
    }
    public static boolean vanishes(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getEnchants().containsKey(Enchantment.VANISHING_CURSE);
    }
    public static int effectiveChance(int configured) { return Math.max(0, Math.min(100, configured)); }
    public static List<String> inspect(GraveStonesPlus plugin, Player player, ItemStack held) {
        ItemStack item = held.clone();
        List<String> lines = new ArrayList<>();
        lines.add("&7Held: &f" + item.getType() + " x" + item.getAmount());
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            lines.add("&7Metadata: &fdisplay=" + (meta.hasDisplayName() ? meta.getDisplayName() : "none")
                    + ", lore=" + (meta.hasLore() ? meta.getLore() : "none") + ", enchants=" + meta.getEnchants()
                    + ", flags=" + meta.getItemFlags() + ", PDC keys=" + meta.getPersistentDataContainer().getKeys());
        }
        lines.add("&7Order: Ignore NBT > Slimefun > exact lore > Keep NBT > Grave NBT > Vanishing > grave default");
        RulesSnapshot snapshot = plugin.isNbtAPIHooked() && plugin.getNbtConfigManager() != null
                ? plugin.getNbtConfigManager().snapshot() : null;
        if (plugin.isNbtAPIHooked() && plugin.getNbtConfigManager() != null) {
            PlayerDeathNBTHandle matcher = new PlayerDeathNBTHandle();
            lines.add("&7NBT matches: Ignore=" + matcher.matchingRuleNames(plugin, item, snapshot.ignore())
                    + "; Keep=" + matcher.matchingRuleNames(plugin, item, snapshot.keep())
                    + "; Grave=" + matcher.matchingRuleNames(plugin, item, snapshot.grave()));
        } else lines.add("&7NBTAPI unavailable: NBT stages are inactive.");
        lines.add("&7When grave creation proceeds: &f" + forPlayer(plugin, player, snapshot).evaluate(item));
        lines.add("&7Later recovery/drop chance: &f" + effectiveChance(plugin.getConfigFile().getPercentageDrops()) + "% per stack; no chance sampled.");
        lines.add("&eRead only. World/permission/keep-inventory/combat/placement checks and external death hooks can change eligibility. NBT list indices are unsupported.");
        return List.copyOf(lines);
    }
}
