package com.bencodez.gravestonesplus.tests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

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
}
