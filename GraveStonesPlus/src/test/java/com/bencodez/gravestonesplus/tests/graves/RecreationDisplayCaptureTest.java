package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.storage.GravesConfig;

class RecreationDisplayCaptureTest {
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void capturesSpawnBeforeInitializationAndNeverExposesClaimMetadata(int failure)throws Exception {
        var world=mock(World.class);var display=mock(ItemDisplay.class);var player=mock(OfflinePlayer.class);
        var plugin=mock(GraveStonesPlus.class);var item=mock(ItemStack.class);
        UUID owner=UUID.randomUUID(),oldDisplay=UUID.randomUUID(),oldInteraction=UUID.randomUUID();
        var config=new GravesConfig(owner,"Synthetic",new Location(world,1,64,2),new HashMap<>(),17,"synthetic",1000,true,2000,oldDisplay,oldInteraction,"DISPLAY_ENTITY");
        var grave=new Grave(plugin,config);var captured=new AtomicReference<ItemDisplay>();
        when(world.spawn(any(Location.class),eq(ItemDisplay.class))).thenReturn(display);
        doAnswer(call->{assertSame(display,captured.get(),"attempt must own the handle before native initialization");
            if(failure==1)throw new IllegalStateException("synthetic display initialization failure");
            if(failure==2)throw new AssertionError("synthetic display initialization error");return null;}).when(display).setItemStack(item);
        try(var bukkit=mockStatic(Bukkit.class);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(item))) {
            bukkit.when(()->Bukkit.getOfflinePlayer(owner)).thenReturn(player);
            var method=GraveDisplayEntityHandle.class.getDeclaredMethod("createRecreationDisplay",Grave.class,java.util.function.Consumer.class);method.setAccessible(true);
            var handler=new GraveDisplayEntityHandle(plugin);
            if(failure==0)assertSame(display,method.invoke(handler,grave,(java.util.function.Consumer<ItemDisplay>)captured::set));
            else {
                var thrown=assertThrows(InvocationTargetException.class,()->method.invoke(handler,grave,(java.util.function.Consumer<ItemDisplay>)captured::set));
                if(failure==1)assertInstanceOf(IllegalStateException.class,thrown.getCause());else assertInstanceOf(AssertionError.class,thrown.getCause());
            }
            assertSame(display,captured.get());verify(world,times(1)).spawn(any(Location.class),eq(ItemDisplay.class));
            verify(display,never()).setMetadata(anyString(),any());verify(display,never()).getPersistentDataContainer();verify(display,never()).remove();
            assertTrue(config.isDestroyed());assertEquals(2000,config.getDestroyedTime());assertEquals(oldDisplay,config.getDisplayUUID());assertEquals(oldInteraction,config.getInteractUUID());
            assertEquals(17,config.getExp());assertEquals("DISPLAY_ENTITY",config.getGraveDisplayType());
            verify(display,times(failure==0?1:0)).setVisibleByDefault(true);
        }
    }
}
