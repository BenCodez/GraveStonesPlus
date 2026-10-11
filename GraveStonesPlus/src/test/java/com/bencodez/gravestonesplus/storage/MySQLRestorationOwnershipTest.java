package com.bencodez.gravestonesplus.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.sql.mysql.*;

class MySQLRestorationOwnershipTest {
    enum Write {
        UPSERT, UPDATE, BROKEN, DELETE;
        void backend(MySQLGraveStorageBackend backend, GravesConfig grave) {
            switch (this) {
                case UPSERT -> backend.upsertGrave(grave, false);
                case UPDATE -> backend.updateExistingGrave(grave, true);
                case BROKEN -> backend.setBroken(grave, true);
                case DELETE -> backend.deleteGrave(grave);
            }
        }
        void legacy(GravesMySQLTable table, GravesConfig grave) {
            switch (this) {
                case UPSERT -> table.upsertGrave(grave, false);
                case UPDATE -> table.updateExistingGrave(grave, true);
                case BROKEN -> table.setBroken(grave, true);
                case DELETE -> table.deleteGrave(grave);
            }
        }
    }
    private static void checkedBackend(Fixture h) throws Exception {
        recreation(h);
        doNothing().when(h.table).debug(any(Throwable.class));
        doCallRealMethod().when(h.backend).upsertGrave(any(), anyBoolean());
        doCallRealMethod().when(h.backend).updateExistingGrave(any(), anyBoolean());
        doCallRealMethod().when(h.backend).setBroken(any(), anyBoolean());
        doCallRealMethod().when(h.backend).deleteGrave(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Write.class)
    void checkedBackendReportsSqlFailureWithoutChangingLegacyTableContract(Write write) throws Exception {
        try (var h = new Fixture()) {
            checkedBackend(h);
            when(h.statement.executeUpdate()).thenThrow(new SQLException("fixture write failure"));
            var grave = recreated(h);
            assertThrows(ExecutionException.class, () -> h.io.submit(() -> { write.backend(h.backend, grave); return true; }).get(3, TimeUnit.SECONDS));
            assertTrue(h.io.submit(() -> { write.legacy(h.table, grave); return true; }).get(3, TimeUnit.SECONDS));
            verify(h.table).debug(any(Throwable.class));
            verify(h.statement, times(2)).close(); verify(h.connection, times(2)).close();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Write.class)
    void checkedBackendRejectsUncommittedConnectionsBeforeExecuting(Write write) throws Exception {
        try (var h = new Fixture()) {
            checkedBackend(h); when(h.connection.getAutoCommit()).thenReturn(false);
            assertThrows(ExecutionException.class, () -> h.io.submit(() -> { write.backend(h.backend, recreated(h)); return true; }).get(3, TimeUnit.SECONDS));
            verify(h.statement, never()).executeUpdate();
            verify(h.connection, never()).commit(); verify(h.connection, never()).rollback();
            verify(h.statement).close(); verify(h.connection).close();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Write.class)
    void idempotentZeroAffectedRowsStillAcknowledgesSuccessfulSql(Write write) throws Exception {
        try (var h = new Fixture()) {
            checkedBackend(h); when(h.statement.executeUpdate()).thenReturn(0);
            assertTrue(h.io.submit(() -> { write.backend(h.backend, recreated(h)); return true; }).get(3, TimeUnit.SECONDS));
            verify(h.statement).executeUpdate(); verify(h.statement).close(); verify(h.connection).close();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Write.class)
    void statementCleanupFailureIsNotAcknowledgedAsAConfirmedWrite(Write write) throws Exception {
        try (var h = new Fixture()) {
            checkedBackend(h); when(h.statement.executeUpdate()).thenReturn(1);
            doThrow(new SQLException("fixture cleanup failure")).when(h.statement).close();
            assertThrows(ExecutionException.class, () -> h.io.submit(() -> { write.backend(h.backend, recreated(h)); return true; }).get(3, TimeUnit.SECONDS));
            verify(h.statement).executeUpdate(); verify(h.statement).close(); verify(h.connection).close();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Write.class)
    void uninitializedBackendCannotReportAWriteAsSuccessful(Write write) throws Exception {
        try (var h = new Fixture()) {
            checkedBackend(h); field(h.backend, MySQLGraveStorageBackend.class, "table", null);
            assertThrows(IllegalStateException.class, () -> write.backend(h.backend, recreated(h)));
            verify(h.connection, never()).prepareStatement(anyString());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedIncrementalSqlWriteRetainsCapturedStateUntilConfirmedRetry(boolean update) throws Exception {
        try (var h = new Fixture()) {
            recreation(h);
            doNothing().when(h.table).debug(any(Throwable.class));
            doCallRealMethod().when(h.backend).upsertGrave(any(), anyBoolean());
            doCallRealMethod().when(h.backend).updateExistingGrave(any(), anyBoolean());
            var config = recreated(h);
            var grave = mock(com.bencodez.gravestonesplus.graves.Grave.class);
            when(grave.getGravesConfig()).thenReturn(config);
            var owner = Thread.currentThread();
            when(config.isDestroyed()).thenReturn(update);
            when(h.plugin.activeGraveSnapshot()).thenAnswer(call -> {
                assertSame(owner, Thread.currentThread(), "recovery must not rescan gameplay state on the disk worker");
                return update ? List.of() : List.of(grave);
            });
            when(h.plugin.brokenGraveSnapshot()).thenReturn(update ? List.of(grave) : List.of());
            when(h.statement.executeUpdate()).thenThrow(new SQLException("fixture unavailable"));

            if (update) h.storage.updateExistingGrave(config, true);
            else h.storage.upsertGrave(config, false);
            h.io.barrier().get(3, TimeUnit.SECONDS);

            var retained = GraveStorageManager.class.getDeclaredField("retained"); retained.setAccessible(true);
            synchronized (h.storage) { assertNotNull(retained.get(h.storage), "failed SQL must retain the captured grave"); }
            verify(h.statement, atLeastOnce()).close(); verify(h.connection, atLeastOnce()).close();
            doReturn(1).when(h.statement).executeUpdate();
            h.io.barrier().get(3, TimeUnit.SECONDS);
            synchronized (h.storage) { assertNull(retained.get(h.storage), "confirmed same-identity recovery can retire the snapshot"); }
            verify(h.statement, atLeast(2)).executeUpdate();
            var replay = org.mockito.ArgumentCaptor.forClass(GravesConfig.class);
            verify(h.table, atLeastOnce()).upsertGraveChecked(replay.capture(), eq(update));
            for (var captured : replay.getAllValues()) {
                assertEquals(config.getUuid(), captured.getUuid()); assertEquals(42L, captured.getTime());
                assertEquals(update, captured.isDestroyed(), "recovery must not revive a broken transition");
            }
        }
    }

    @Test void missingDatabaseFallsBackToFlatFileForAllLaterOperations() throws Exception {
        var active = mock(GravesConfig.class); var broken = mock(GravesConfig.class);
        try (var io = new FeatureIO(); var files = mockConstruction(GraveLocations.class, (file, context) -> {
            when(file.loadGraves()).thenReturn(List.of(active));
            when(file.loadBrokenGraves()).thenReturn(List.of(broken));
        })) {
            var plugin = mock(GraveStonesPlus.class);
            var config = mock(Config.class);
            var data = new YamlConfiguration(); data.set("DataStorage", "MYSQL");
            when(plugin.getFeatureIO()).thenReturn(io); when(plugin.getConfigFile()).thenReturn(config);
            when(config.getData()).thenReturn(data); when(plugin.getDataFolder()).thenReturn(new java.io.File("target/storage-init-regression"));
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            var manager = new GraveStorageManager(plugin);
            manager.init();
            assertFalse(manager.isMySQL());
            assertEquals(List.of(active), manager.loadGraves());
            assertEquals(List.of(broken), manager.loadBrokenGraves());
            verify(files.constructed().getFirst()).reloadData();
            assertTrue(manager.loadMySQLForRestoration().isCompletedExceptionally());
        }
    }
    private static void field(Object target, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static final class Fixture implements AutoCloseable {
        final FeatureIO io = new FeatureIO();
        final GraveStonesPlus plugin = mock(GraveStonesPlus.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Queue<Runnable> ownerTasks = new ConcurrentLinkedQueue<>();
        final GravesMySQLTable table = mock(GravesMySQLTable.class, CALLS_REAL_METHODS);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
        final MySQLGraveStorageBackend backend = mock(MySQLGraveStorageBackend.class);
        final GraveStorageManager storage;
        final UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        Fixture() throws Exception {
            when(plugin.getFeatureIO()).thenReturn(io); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
            when(plugin.isEnabled()).thenReturn(true);
            doAnswer(call -> { ownerTasks.add(call.getArgument(1)); return null; })
                    .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            MySQL mysql = mock(MySQL.class); ConnectionManager connections = mock(ConnectionManager.class);
            when(mysql.getConnectionManager()).thenReturn(connections);
            when(connections.getConnection()).thenAnswer(call -> {
                assertTrue(Thread.currentThread().getName().startsWith("gsp-feature-io")); return connection;
            });
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows);
            field(table, AbstractSqlTable.class, "mysql", mysql);
            field(table, AbstractSqlTable.class, "tableName", "graves");
            field(table, AbstractSqlTable.class, "dbType", DbType.MYSQL);
            doCallRealMethod().when(table).loadDeferred(anyBoolean());
            field(backend, MySQLGraveStorageBackend.class, "table", table);
            doCallRealMethod().when(backend).loadDeferred(anyBoolean());
            storage = new GraveStorageManager(plugin);
            field(storage, GraveStorageManager.class, "backend", backend);
            when(rows.next()).thenReturn(true, false, false);
            when(rows.getString("player_uuid")).thenReturn(player.toString());
            when(rows.getString("world_uuid")).thenReturn(world.toString());
            when(rows.getString("items_encoded")).thenReturn("encoded");
            when(rows.getInt("x")).thenReturn(11); when(rows.getInt("y")).thenReturn(64);
            when(rows.getInt("z")).thenReturn(-9); when(rows.getLong("time")).thenReturn(42L);
        }
        public void close() { io.close(); }
    }
    private static GravesConfig recreated(Fixture h) {
        GravesConfig grave = mock(GravesConfig.class);
        when(grave.getUuid()).thenReturn(h.player);
        when(grave.getWorldId()).thenReturn(h.world);
        when(grave.getLocation()).thenReturn(new Location(null, 11, 64, -9));
        when(grave.getItems()).thenReturn(new HashMap<>());
        when(grave.getTime()).thenReturn(42L);
        when(grave.getExp()).thenReturn(17);
        when(grave.getGraveDisplayType()).thenReturn("PLAYER_HEAD");
        return grave;
    }
    private static void recreation(Fixture h) throws Exception {
        when(h.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(h.plugin.activeGraveSnapshot()).thenReturn(List.of());
        when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
        field(h.storage, GraveStorageManager.class, "type", DataStorageType.MYSQL);
        doCallRealMethod().when(h.table).saveRecreatedGrave(any());
        doCallRealMethod().when(h.backend).saveRecreatedGrave(any());
        when(h.connection.getAutoCommit()).thenReturn(true);
    }
    @Test void displayRepairUpdatesOnlyExactActiveMetadataAndRequiresAcknowledgement() throws Exception {
        for (int affected : List.of(0, 1, 2)) try (var h = new Fixture()) {
            recreation(h);
            doCallRealMethod().when(h.table).saveDisplayIdentity(any());
            doCallRealMethod().when(h.backend).saveDisplayIdentity(any());
            when(h.statement.executeUpdate()).thenReturn(affected);
            var grave = recreated(h); UUID display = UUID.randomUUID(); when(grave.getDisplayUUID()).thenReturn(display);
            var saved = h.storage.saveDisplayIdentity(grave);
            if (affected == 1) assertTrue(saved.get(3, TimeUnit.SECONDS));
            else assertThrows(ExecutionException.class, () -> saved.get(3, TimeUnit.SECONDS));
            verify(h.statement).setString(1, display.toString()); verify(h.statement).setString(3, h.player.toString());
            verify(h.statement).setString(4, h.world.toString()); verify(h.statement).setLong(8, 42L);
            var sql = org.mockito.ArgumentCaptor.forClass(String.class); verify(h.connection).prepareStatement(sql.capture());
            assertTrue(sql.getValue().replace("`", "").replace("\"", "").contains("broken=0 AND destroyed=0"), sql.getValue());
            String assignments = sql.getValue().split(" WHERE ")[0];
            assertFalse(assignments.contains("items_encoded")); assertFalse(assignments.contains("`exp`"));
            assertFalse(assignments.contains("`broken`")); assertFalse(assignments.contains("`destroyed`"));
            verify(h.statement).close(); verify(h.connection).close();
        }
    }
    @Test void recreationWaitsForTheExactExistingRowWriteAndPreservesContents() throws Exception {
        try (var h = new Fixture()) {
            recreation(h);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            when(h.statement.executeUpdate()).thenAnswer(call -> {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return 1;
            });
            var result = h.storage.saveRecreatedGrave(recreated(h));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS)); assertFalse(result.isDone());
            } finally { release.countDown(); }
            assertTrue(result.get(3, TimeUnit.SECONDS));
            verify(h.statement).setInt(1, 0); verify(h.statement).setInt(2, 0);
            verify(h.statement).setString(7, h.player.toString());
            verify(h.statement).setString(8, h.world.toString());
            verify(h.statement).setLong(12, 42L);
            var sql = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(h.connection).prepareStatement(sql.capture());
            assertFalse(sql.getValue().contains("items_encoded"));
            assertFalse(sql.getValue().contains("`exp`"));
            verify(h.statement).close(); verify(h.connection).close();
        }
    }
    @Test void recreationDoesNotAcknowledgeMissingOrMultipleRows() throws Exception {
        for (int rows : List.of(0, 2)) try (var h = new Fixture()) {
            recreation(h); when(h.statement.executeUpdate()).thenReturn(rows);
            assertThrows(ExecutionException.class, () -> h.storage.saveRecreatedGrave(recreated(h)).get(3, TimeUnit.SECONDS));
            verify(h.statement).close(); verify(h.connection).close();
        }
    }
    @Test void recreationSurfacesSqlFailureAndClosesResources() throws Exception {
        try (var h = new Fixture()) {
            recreation(h); when(h.statement.executeUpdate()).thenThrow(new SQLException("fixture write failed"));
            assertThrows(ExecutionException.class, () -> h.storage.saveRecreatedGrave(recreated(h)).get(3, TimeUnit.SECONDS));
            verify(h.statement).close(); verify(h.connection).close();
        }
    }
    @Test void recreationCannotAcknowledgeAnUncommittedBorrowedTransaction() throws Exception {
        try (var h = new Fixture()) {
            recreation(h); when(h.connection.getAutoCommit()).thenReturn(false);
            assertThrows(ExecutionException.class, () -> h.storage.saveRecreatedGrave(recreated(h)).get(3, TimeUnit.SECONDS));
            verify(h.statement, never()).executeUpdate();
            verify(h.statement).close(); verify(h.connection).close();
        }
    }

    @Test void actualJdbcReadDefersWorldAndItemsUntilServerOwnerThenRetainsIdentity() throws Exception {
        try (var h = new Fixture(); var bukkit = mockStatic(Bukkit.class); var inventory = mockStatic(InventorySerializer.class)) {
            Thread owner = Thread.currentThread(); World world = mock(World.class); when(world.getUID()).thenReturn(h.world);
            bukkit.when(() -> Bukkit.getWorld(h.world)).thenAnswer(call -> { assertSame(owner, Thread.currentThread()); return world; });
            var item = mock(ItemStack.class); HashMap<Integer, ItemStack> items = new HashMap<>(); items.put(3, item);
            inventory.when(() -> InventorySerializer.decode("encoded")).thenAnswer(call -> { assertSame(owner, Thread.currentThread()); return items; });
            var result = h.storage.loadMySQLForRestoration();
            h.io.barrier().get(5, TimeUnit.SECONDS);
            assertFalse(result.isDone()); assertEquals(1, h.ownerTasks.size());
            bukkit.verifyNoInteractions(); inventory.verifyNoInteractions(); verify(h.connection, times(2)).close();
            h.ownerTasks.remove().run();
            var records = result.get(5, TimeUnit.SECONDS); assertEquals(1, records.active().size()); assertTrue(records.broken().isEmpty());
            var record = records.active().getFirst(); assertEquals(h.world, record.getWorldId()); assertSame(world, record.getLocation().getWorld());
            assertSame(item, record.getItems().get(3)); assertEquals(42, record.getTime()); assertEquals(11, record.getLocation().getBlockX());
        }
    }
    @Test void largeSqlResultUsesBoundedOwnerBatchesWithoutLoadingChunks() throws Exception {
        try (var h = new Fixture(); var bukkit = mockStatic(Bukkit.class); var inventory = mockStatic(InventorySerializer.class)) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            when(h.rows.next()).thenAnswer(call -> calls.incrementAndGet() <= 260);
            World world = mock(World.class); when(world.getUID()).thenReturn(h.world);
            bukkit.when(() -> Bukkit.getWorld(h.world)).thenReturn(world);
            inventory.when(() -> InventorySerializer.decode("encoded")).thenReturn(new HashMap<>());
            var result = h.storage.loadMySQLForRestoration(); h.io.barrier().get(5, TimeUnit.SECONDS);
            assertEquals(1, h.ownerTasks.size());
            h.ownerTasks.remove().run(); assertFalse(result.isDone()); assertEquals(1, h.ownerTasks.size());
            inventory.verify(() -> InventorySerializer.decode("encoded"), times(128));
            h.ownerTasks.remove().run(); assertFalse(result.isDone()); assertEquals(1, h.ownerTasks.size());
            inventory.verify(() -> InventorySerializer.decode("encoded"), times(256));
            h.ownerTasks.remove().run(); assertEquals(260, result.get(5, TimeUnit.SECONDS).active().size());
            assertTrue(h.ownerTasks.isEmpty()); verify(world, never()).getChunkAt(anyInt(), anyInt());
        }
    }
    @Test void incompleteSqlReadFailsRatherThanPublishingAnEmptyOrPartialRestoration() throws Exception {
        try (var h = new Fixture()) {
            when(h.rows.next()).thenReturn(true).thenThrow(new SQLException("fixture interrupted read"));
            var result = h.storage.loadMySQLForRestoration();
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(h.ownerTasks.isEmpty()); verify(h.rows).close(); verify(h.statement).close(); verify(h.connection).close();
        }
    }
    @Test void disableBeforeOwnerMaterializationRetainsBackendAndFailsThePendingResult() throws Exception {
        try (var h = new Fixture(); var bukkit = mockStatic(Bukkit.class); var inventory = mockStatic(InventorySerializer.class)) {
            var result = h.storage.loadMySQLForRestoration(); h.io.barrier().get(5, TimeUnit.SECONDS);
            when(h.plugin.isEnabled()).thenReturn(false); h.ownerTasks.remove().run();
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            bukkit.verifyNoInteractions(); inventory.verifyNoInteractions();
        }
    }
}
