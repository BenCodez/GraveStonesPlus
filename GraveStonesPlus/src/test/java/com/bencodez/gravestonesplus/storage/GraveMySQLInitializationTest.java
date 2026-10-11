package com.bencodez.gravestonesplus.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.mysql.ConnectionManager;
import com.bencodez.simpleapi.sql.mysql.DbType;

class GraveMySQLInitializationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedPostConnectInitializationReleasesPoolAndWorkersPreservingFailure(boolean cleanupFails) {
        var plugin = mock(AdvancedCorePlugin.class, RETURNS_DEEP_STUBS);
        var config = new YamlConfiguration();
        config.set("Host", "localhost"); config.set("Port", 3306);
        config.set("Database", "test"); config.set("Username", "test");
        config.set("Password", "fixture"); config.set("MaxConnections", 1);
        var setup = new IllegalStateException("fixture table initialization failure");
        var cleanup = new IllegalStateException("fixture close failure");
        var workers = new ArrayList<ExecutorService>();
        try (var executors = mockStatic(Executors.class);
             var managers = mockConstruction(ConnectionManager.class, (manager, context) -> {
                 when(manager.open()).thenReturn(true); when(manager.getDbType()).thenReturn(DbType.MYSQL);
                 var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
                 when(manager.getConnectionChecked()).thenReturn(connection);
                 when(connection.prepareStatement(anyString())).thenReturn(statement);
                 if (cleanupFails) doThrow(cleanup).when(manager).close();
             })) {
            executors.when(() -> Executors.newFixedThreadPool(anyInt())).thenAnswer(call -> {
                var worker = mock(ExecutorService.class); workers.add(worker); return worker;
            });
            var thrown = assertThrows(IllegalStateException.class,
                    () -> new GravesMySQLTable(plugin, "graves", config) {
                        @Override public String buildCreateTableSql(DbType type) { throw setup; }
                    });
            assertSame(setup, thrown);
            verify(managers.constructed().getFirst()).close();
            assertFalse(workers.isEmpty());
            workers.forEach(worker -> verify(worker).shutdown());
            if (cleanupFails) assertArrayEquals(new Throwable[] { cleanup }, thrown.getSuppressed());
            else assertEquals(0, thrown.getSuppressed().length);
        }
    }
}
