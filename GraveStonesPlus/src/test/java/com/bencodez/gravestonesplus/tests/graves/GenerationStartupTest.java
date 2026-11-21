package com.bencodez.gravestonesplus.tests.graves;

import com.bencodez.gravestonesplus.graves.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real plugin lifecycle entry points; selected initialization must not precede ownership. */
class GenerationStartupTest {
    @TempDir Path directory;
    static <T> T await(CompletableFuture<T> value)throws Exception{return value.get(5,TimeUnit.SECONDS);}
    void install(ClaimHarness h)throws Exception {
        when(h.plugin.getDataFolder()).thenReturn(directory.toFile());
        when(h.plugin.getFeatureIO()).thenCallRealMethod();
        doCallRealMethod().when(h.plugin).onPostLoad();doCallRealMethod().when(h.plugin).onUnLoad();
        doCallRealMethod().when(h.plugin).retireRecreationAttempts();
    }
    @Test void competingGenerationCannotInitializeSelectedStateAndIsDisabledOnPlatformOwner() throws Exception {
        try(var h=new ClaimHarness();var owner=new FeatureIO(8,directory)) {
            assertTrue(await(owner.ready()));install(h);h.plugin.onPostLoad();
            var rejected=h.plugin.getFeatureIO();assertFalse(await(rejected.ready()));
            // Future completion can precede enqueueing its platform notification.
            for(int n=0;n<100&&h.ownerWork.isEmpty();n++)Thread.sleep(5);
            h.drain();verify(h.events).disablePlugin(h.plugin);
            assertFalse(Files.exists(directory.resolve("Graves.yml")));
            verify(h.plugin,never()).getGravesConfig();h.plugin.onUnLoad();
        }
    }
    @Test void unloadBeforeReadyDispatchAndStaleCallbackNeverCreateEmptySelectedFiles() throws Exception {
        try(var h=new ClaimHarness()) {
            install(h);h.plugin.onPostLoad();var old=h.plugin.getFeatureIO();assertTrue(await(old.ready()));
            for(int n=0;n<100&&h.ownerWork.isEmpty();n++)Thread.sleep(5);
            assertFalse(h.ownerWork.isEmpty());when(h.plugin.isEnabled()).thenReturn(false);h.plugin.onUnLoad();
            assertTrue(old.isGenerationReleased());when(h.plugin.isEnabled()).thenReturn(true);
            h.plugin.onPostLoad();var next=h.plugin.getFeatureIO();assertTrue(await(next.ready()));
            when(h.plugin.isEnabled()).thenReturn(false);h.plugin.onUnLoad();h.drain();
            assertTrue(next.isGenerationReleased());assertFalse(Files.exists(directory.resolve("Graves.yml")));
            verify(h.events,never()).disablePlugin(h.plugin);
        }
    }
}
