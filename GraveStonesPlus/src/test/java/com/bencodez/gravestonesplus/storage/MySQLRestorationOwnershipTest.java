package com.bencodez.gravestonesplus.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.sql.mysql.*;

class MySQLRestorationOwnershipTest {
    private static void field(Object target, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static final class Fixture implements AutoCloseable {
        final FeatureIO io = new FeatureIO();
        final GraveStonesPlus plugin = mock(GraveStonesPlus.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Queue<Runnable> ownerTasks = new ConcurrentLinkedQueue<>();
        final GravesMySQLTable table = mock(GravesMySQLTable.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
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
            var backend = mock(MySQLGraveStorageBackend.class);
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
