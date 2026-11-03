package com.bencodez.gravestonesplus.graves;

import java.util.*;
import java.util.function.IntSupplier;
import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.entity.Player;

/** Existing per-stack chance and slot placement, with explicit recipient-location overflow. */
public final class GravePayout {
    private GravePayout() { }
    public static Map<Integer, ItemStack> select(Map<Integer, ItemStack> stored, int chance, IntSupplier random) {
        Map<Integer, ItemStack> result = new TreeMap<>();
        for (var entry : stored.entrySet()) if (entry.getValue() != null && !entry.getValue().getType().isAir()
                && (chance == 100 || random.getAsInt() < chance)) result.put(entry.getKey(), entry.getValue().clone());
        return result;
    }
    /** Called exclusively on recipient entity owner; succeeds after every selected value is delivered. */
    public static boolean deliver(Player player, Map<Integer, ItemStack> items, int experience) {
        return deliver(player, items, experience, overflow -> player.getWorld().dropItem(player.getLocation(), overflow));
    }
    public static boolean deliver(Player player, Map<Integer, ItemStack> items, int experience, Consumer<ItemStack> overflowSink) {
        PlayerInventory inventory = player.getInventory();
        boolean displaced = false;
        List<ItemStack> displacedItems = new ArrayList<>();
        for (var entry : items.entrySet()) {
            int slot = switch (entry.getKey()) { case -1 -> 39; case -2 -> 38; case -3 -> 37; case -4 -> 36; case -5 -> 40; default -> entry.getKey(); };
            ItemStack item = entry.getValue();
            if (slot >= 0 && slot <= 40 && (inventory.getItem(slot) == null || inventory.getItem(slot).getType().isAir())) inventory.setItem(slot, item);
            else {
                displaced = true;
                displacedItems.add(item);
            }
        }
        for (ItemStack displacedItem : displacedItems) {
            for (ItemStack overflow : inventory.addItem(displacedItem).values()) overflowSink.accept(overflow);
        }
        player.giveExp(experience);
        return displaced;
    }
}
