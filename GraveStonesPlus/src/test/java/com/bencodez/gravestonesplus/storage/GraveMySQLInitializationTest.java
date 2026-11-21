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
    @org.junit.jupiter.api.Test
    void legacySuperclassLoggingDoesNotRequireTheSubclassPluginField() {
        // CALLS_REAL_METHODS leaves the subclass field unassigned, matching a
        // virtual callback from an older SimpleAPI superclass constructor.
        var table = mock(GravesMySQLTable.class, CALLS_REAL_METHODS);
        var logger = mock(java.util.logging.Logger.class);
        var failure = new java.sql.SQLException("fixture authentication rejected");
        try (var loggers = mockStatic(java.util.logging.Logger.class)) {
            loggers.when(() -> java.util.logging.Logger.getLogger("GraveStonesPlus")).thenReturn(logger);
            table.logSevere("fixture initialization failed");
            table.logInfo("fixture setup");
            table.debug("fixture debug");
            table.debug(failure);
            verify(logger).severe("fixture initialization failed");
            verify(logger).info("fixture setup");
            verify(logger).fine("fixture debug");
            verify(logger).log(java.util.logging.Level.FINE, "SQL initialization diagnostic", failure);
        }
    }

    @org.junit.jupiter.api.Test
    void legacyFailedPoolIsRejectedBeforeTableQueriesAndWorkersAreRetired() {
        var plugin = mock(AdvancedCorePlugin.class, RETURNS_DEEP_STUBS);
        var config = new YamlConfiguration();
        var workers = new ArrayList<ExecutorService>();
        try (var executors = mockStatic(Executors.class);
             var managers = mockConstruction(ConnectionManager.class, (manager, context) -> {
                 // A legacy constructor reports success/returns, but its pool is unusable.
                 when(manager.open()).thenReturn(true);
                 when(manager.isClosed()).thenReturn(true);
                 var connection = mock(Connection.class);
                 when(manager.getConnectionChecked()).thenReturn(connection);
                 when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
             })) {
            executors.when(() -> Executors.newFixedThreadPool(anyInt())).thenAnswer(call -> {
                var worker = mock(ExecutorService.class);
                workers.add(worker); return worker;
            });
            var failure = assertThrows(IllegalStateException.class,
                    () -> new GravesMySQLTable(plugin, "graves", config) {
                        @Override public String buildCreateTableSql(DbType type) {
                            fail("A failed pool must not reach table initialization"); return "";
                        }
                    });
            assertTrue(failure.getMessage().contains("did not initialize"));
            verify(managers.constructed().getFirst()).close();
            assertFalse(workers.isEmpty());
            workers.forEach(worker -> verify(worker).shutdown());
        }
    }

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
                var worker = mock(ExecutorService.class);
                var stopped = new java.util.concurrent.atomic.AtomicBoolean();
                when(worker.isShutdown()).thenAnswer(ignored -> stopped.get());
                doAnswer(ignored -> { stopped.set(true); return null; }).when(worker).shutdown();
                workers.add(worker); return worker;
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
