package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Entity;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.inventory.BInventory;
import com.bencodez.advancedcore.api.item.ItemBuilder;

class HistoricalMenuOwnershipTest {
    @Test void historicalClicksAuthorizeOnlyOnActorOwnerAndDispatchEachIntent() throws Exception {
        for (ClickType intent : List.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)) {
            try (var h = new GraveActionsTest.Harness();
                    var builders = mockConstruction(ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
                var owned = new AtomicBoolean();
                var grave = spy(h.grave);
                doNothing().when(grave).openGUIWithItems(h.helper);
                doNothing().when(grave).teleportHistorical(h.helper);
                when(h.plugin.recreateBrokenGrave(grave, h.helper)).thenReturn(CompletableFuture.completedFuture(true));
                when(h.helper.hasPermission(anyString())).thenAnswer(call -> {
                    assertTrue(owned.get(), "permission access must occur on the actor owner");
                    return call.getArgument(0).equals("GraveStonesPlus.AllBrokenGraves");
                });
                var click = mock(BInventory.ClickEvent.class);
                when(click.getPlayer()).thenReturn(h.helper); when(click.getClick()).thenReturn(intent);
                clearInvocations(h.helper);
                grave.getGUIItemBroken().onClick(click);
                verify(h.helper, never()).hasPermission(anyString());
                verify(h.plugin, never()).recreateBrokenGrave(any(), any());
                assertEquals(1, h.entityWork.size());
                owned.set(true); h.entityWork.remove().run();
                if (intent == ClickType.SHIFT_RIGHT) verify(h.plugin).recreateBrokenGrave(grave, h.helper);
                else if (intent == ClickType.SHIFT_LEFT) verify(grave).openGUIWithItems(h.helper);
                else verify(grave).teleportHistorical(h.helper);
                verify(h.scheduler, never()).runTask(eq(h.plugin), any(Runnable.class));
                verify(h.scheduler, atLeastOnce()).runTask(eq(h.plugin), any(Runnable.class), eq((Entity) h.helper));
            }
        }
    }

    @Test void historicalClicksRevalidatePermissionOnlineAndPluginStateBeforeMutation() throws Exception {
        for (String change : List.of("revoked", "offline", "disabled")) {
            try (var h = new GraveActionsTest.Harness();
                    var builders = mockConstruction(ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
                when(h.helper.hasPermission("GraveStonesPlus.AllBrokenGraves")).thenReturn(true);
                var click = mock(BInventory.ClickEvent.class);
                when(click.getPlayer()).thenReturn(h.helper); when(click.getClick()).thenReturn(ClickType.SHIFT_RIGHT);
                h.grave.getGUIItemBroken().onClick(click);
                switch (change) {
                    case "revoked" -> when(h.helper.hasPermission("GraveStonesPlus.AllBrokenGraves")).thenReturn(false);
                    case "offline" -> when(h.helper.isOnline()).thenReturn(false);
                    case "disabled" -> when(h.plugin.isEnabled()).thenReturn(false);
                }
                h.entityWork.remove().run();
                verify(h.plugin, never()).recreateBrokenGrave(any(), any());
                verify(h.helper, never()).teleport(any(org.bukkit.Location.class));
                verify(h.helper, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
            }
        }
    }
}
