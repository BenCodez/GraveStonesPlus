package com.bencodez.gravestonesplus.tests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.simpleapi.time.ParsedDuration;

import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.graves.GraveDisplayType;

class LegacyConfigTest {
    @Test
    void legacyDisplayAndGlowSettingsSurviveRepeatedLoads() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("UseDisplayEntities: true\nGlowingEffectNearGrave: false\n"
                + "GlowingEffectDistance: 5\nGraveTimeLimit: 1800\nBrokenGraveTimeLimit: 96\n"
                + "DisableArmorStands: true\n");
        Config config = mock(Config.class);
        when(config.getData()).thenReturn(yaml);
        doCallRealMethod().when(config).loadValues();
        doCallRealMethod().when(config).getGraveDisplayTypeEnum();
        config.loadValues();
        assertEquals(GraveDisplayType.DISPLAY_ENTITY, config.getGraveDisplayTypeEnum());
        assertFalse(yaml.getBoolean("GlowingEffect.Enabled"));
        assertEquals(5, yaml.getInt("GlowingEffect.Distance"));
        assertEquals(1800, yaml.getInt("GraveTimeLimit"));
        assertEquals(96, yaml.getInt("BrokenGraveTimeLimit"));
        String migrated = yaml.saveToString();
        config.loadValues();
        assertEquals(migrated, yaml.saveToString());
        verify(config, times(1)).saveData();
    }

    @Test
    void explicitModernDisplaySettingWinsOverLegacyFlag() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("UseDisplayEntities: true\nGraveDisplayType: CHEST\n");
        Config config = mock(Config.class);
        when(config.getData()).thenReturn(yaml);
        doCallRealMethod().when(config).loadValues();
        doCallRealMethod().when(config).getGraveDisplayTypeEnum();
        config.loadValues();
        assertEquals(GraveDisplayType.CHEST, config.getGraveDisplayTypeEnum());
    }
    @Test
    void numericLegacyLifetimeUsesMinutesAndNegativeSentinelDoesNotSchedule() {
        Grave grave = mock(Grave.class);
        doCallRealMethod().when(grave).checkTimeLimit(anyString());
        doCallRealMethod().when(grave).checkTimeLimit(anyInt());
        doCallRealMethod().when(grave).checkTimeLimit(any(ParsedDuration.class));
        grave.checkTimeLimit("-1");
        grave.checkTimeLimit("0");
        // The legacy disabled values must return before accessing a plugin or record.
        verify(grave, never()).removeGrave(any());
        reset(grave);
        doCallRealMethod().when(grave).checkTimeLimit(anyString());
        doCallRealMethod().when(grave).checkTimeLimit(anyInt());
        grave.checkTimeLimit("1800");
        ArgumentCaptor<ParsedDuration> duration = ArgumentCaptor.forClass(ParsedDuration.class);
        verify(grave).checkTimeLimit(duration.capture());
        assertEquals(1800L * 60L * 1000L, duration.getValue().getMillis());
    }

}
