package com.bencodez.gravestonesplus.listeners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

class ItemRuleEvaluatorTest {
    ItemStack item(String... lore) {
        ItemStack item = mock(ItemStack.class); ItemMeta meta = mock(ItemMeta.class);
        Material material = mock(Material.class); when(material.isAir()).thenReturn(false);
        when(item.getType()).thenReturn(material); when(item.getAmount()).thenReturn(2);
        when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta);
        when(meta.hasLore()).thenReturn(lore.length > 0); when(meta.getLore()).thenReturn(List.of(lore));
        when(meta.getEnchants()).thenReturn(Map.of()); return item;
    }
    Predicate<ItemStack> failStage() { return ignored -> { fail("lower precedence provider called"); return false; }; }
    @Test void ignoreShortCircuitsAllLowerRules() {
        var evaluator = new ItemRuleEvaluator(i -> true, failStage(), "keep", failStage(), failStage(), failStage());
        assertEquals(ItemRuleEvaluator.Outcome.IGNORE, evaluator.evaluate(item("keep")));
    }
    @Test void soulboundBeatsLoreAndNbt() {
        var evaluator = new ItemRuleEvaluator(i -> false, i -> true, "keep", failStage(), failStage(), failStage());
        assertEquals(ItemRuleEvaluator.Outcome.SOULBOUND, evaluator.evaluate(item("keep")));
    }
    @Test void exactLoreUsesWholeLineCaseInsensitiveAndBeatsKeepNbt() {
        var evaluator = new ItemRuleEvaluator(i -> false, i -> false, "\u00a7aOwner Alex", failStage(), failStage(), failStage());
        ItemStack held = item("\u00a7aOWNER ALEX");
        assertEquals(ItemRuleEvaluator.Outcome.KEEP_LORE, evaluator.evaluate(held));
        assertFalse(ItemRuleEvaluator.matchesLore(item("prefix \u00a7aOwner Alex"), "\u00a7aOwner Alex"));
        assertFalse(ItemRuleEvaluator.matchesLore(item("Owner Alex"), "\u00a7aOwner Alex"));
    }
    @Test void keepNbtAndGraveNbtKeepTheirOrder() {
        assertEquals(ItemRuleEvaluator.Outcome.KEEP_NBT,
            new ItemRuleEvaluator(i -> false, i -> false, "", i -> true, failStage(), failStage()).evaluate(item()));
        assertEquals(ItemRuleEvaluator.Outcome.GRAVE_NBT,
            new ItemRuleEvaluator(i -> false, i -> false, "", i -> false, i -> true, failStage()).evaluate(item()));
    }
    @Test void defaultAndEmptyRoutingConsumeNoRngOrMutation() {
        ItemStack held = item("ordinary"); ItemMeta meta = held.getItemMeta();
        assertEquals(ItemRuleEvaluator.Outcome.GRAVE_DEFAULT,
            new ItemRuleEvaluator(i -> false, i -> false, "", i -> false, i -> false, i -> false).evaluate(held));
        assertEquals(2, held.getAmount()); assertEquals(List.of("ordinary"), meta.getLore());
        verify(held, never()).setAmount(anyInt()); verify(held, never()).setItemMeta(any());
        verify(meta, never()).setLore(any()); verify(meta, never()).setDisplayName(any());
        assertEquals(ItemRuleEvaluator.Outcome.EMPTY,
            new ItemRuleEvaluator(failStage(), failStage(), "", failStage(), failStage(), failStage()).evaluate(null));
    }
    @Test void vanishingIsAfterGraveNbtAndBeforeDefault() {
        assertEquals(ItemRuleEvaluator.Outcome.GRAVE_NBT,
            new ItemRuleEvaluator(i -> false, i -> false, "", i -> false, i -> true, failStage()).evaluate(item()));
        assertEquals(ItemRuleEvaluator.Outcome.VANISH,
            new ItemRuleEvaluator(i -> false, i -> false, "", i -> false, i -> false, i -> true).evaluate(item()));
    }
    @Test void chanceIsOnlyDescribedNotSampledAndPreservesEffectiveLegacyLimits() {
        assertEquals(0, ItemRuleEvaluator.effectiveChance(-1)); assertEquals(0, ItemRuleEvaluator.effectiveChance(0));
        assertEquals(50, ItemRuleEvaluator.effectiveChance(50)); assertEquals(100, ItemRuleEvaluator.effectiveChance(100));
        assertEquals(100, ItemRuleEvaluator.effectiveChance(101));
    }
    @Test void oneRulesSnapshotIsImmutableAcrossPublicationGenerations() {
        var oldRules = new com.bencodez.gravestonesplus.nbt.NBTRule("old", "path", "STRING", "old");
        var newerRules = new com.bencodez.gravestonesplus.nbt.NBTRule("new", "path", "STRING", "new");
        var old = new com.bencodez.gravestonesplus.nbt.NBTConfigManager.RulesSnapshot(List.of(), List.of(oldRules), List.of());
        var published = new com.bencodez.gravestonesplus.nbt.NBTConfigManager.RulesSnapshot(List.of(), List.of(), List.of(newerRules));
        assertEquals("old", old.keep().get(0).getName());
        assertTrue(old.grave().isEmpty());
        assertEquals("new", published.grave().get(0).getName());
        assertThrows(UnsupportedOperationException.class, () -> old.ignore().add(newerRules));
    }
    @Test void productionFactoryReusesPlaceholderLoreAndNeverTouchesMissingNbtApi() {
        com.bencodez.gravestonesplus.GraveStonesPlus plugin = mock(com.bencodez.gravestonesplus.GraveStonesPlus.class);
        com.bencodez.gravestonesplus.config.Config config = mock(com.bencodez.gravestonesplus.config.Config.class);
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(plugin.getConfigFile()).thenReturn(config); when(config.getKeepItemsWithLore()).thenReturn("Owner %player%");
        when(player.getName()).thenReturn("Alex"); when(player.getDisplayName()).thenReturn("Alex");
        ItemStack held = item("OWNER ALEX"); when(held.clone()).thenReturn(held);
        assertEquals(ItemRuleEvaluator.Outcome.KEEP_LORE, ItemRuleEvaluator.forPlayer(plugin, player).evaluate(held));
        verify(plugin, never()).getNbtConfigManager(); verify(held, never()).setAmount(anyInt()); verify(held, never()).setItemMeta(any());
    }
    @Test void heldInspectionUsesProductionFactoryWithoutNbtRngOrMetadataMutation() {
        com.bencodez.gravestonesplus.GraveStonesPlus plugin = mock(com.bencodez.gravestonesplus.GraveStonesPlus.class);
        com.bencodez.gravestonesplus.config.Config config = mock(com.bencodez.gravestonesplus.config.Config.class);
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(plugin.getConfigFile()).thenReturn(config); when(config.getKeepItemsWithLore()).thenReturn("Owner %player%");
        when(config.getPercentageDrops()).thenReturn(50); when(player.getName()).thenReturn("Alex"); when(player.getDisplayName()).thenReturn("Alex");
        ItemStack held = item("OWNER ALEX"); when(held.clone()).thenReturn(held);
        org.bukkit.persistence.PersistentDataContainer pdc = mock(org.bukkit.persistence.PersistentDataContainer.class);
        ItemMeta meta = held.getItemMeta(); when(meta.getPersistentDataContainer()).thenReturn(pdc); when(pdc.getKeys()).thenReturn(Set.of());
        List<String> result = ItemRuleEvaluator.inspect(plugin, player, held);
        assertTrue(result.stream().anyMatch(line -> line.contains("KEEP_LORE")));
        assertTrue(result.stream().anyMatch(line -> line.contains("50% per stack")));
        assertTrue(result.stream().anyMatch(line -> line.contains("NBTAPI unavailable")));
        verify(plugin, never()).getNbtConfigManager(); verify(held, never()).setAmount(anyInt()); verify(held, never()).setItemMeta(any());
        verify(meta, never()).setLore(any()); verify(pdc, never()).set(any(), any(), any());
    }

}
