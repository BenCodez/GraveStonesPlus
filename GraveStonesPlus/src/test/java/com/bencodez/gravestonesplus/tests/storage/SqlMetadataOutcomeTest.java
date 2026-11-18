package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.simpleapi.sql.mysql.*;

class SqlMetadataOutcomeTest {
    static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static final class Fixture {
        final GravesMySQLTable table = mock(GravesMySQLTable.class, CALLS_REAL_METHODS);
        final GravesConfig grave = mock(GravesConfig.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement update = mock(PreparedStatement.class), query = mock(PreparedStatement.class);
        final ResultSet row = mock(ResultSet.class);
        final List<String> statements = new ArrayList<>();
        Fixture() throws Exception {
            var mysql = mock(MySQL.class); var connections = mock(ConnectionManager.class);
            when(mysql.getConnectionManager()).thenReturn(connections); when(connections.getConnection()).thenReturn(connection);
            field(table, AbstractSqlTable.class, "mysql", mysql); field(table, AbstractSqlTable.class, "tableName", "graves");
            field(table, AbstractSqlTable.class, "dbType", DbType.MYSQL);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0); statements.add(sql); return sql.startsWith("SELECT ") ? query : update;
            });
            when(query.executeQuery()).thenReturn(row); when(row.next()).thenReturn(true, false);
            when(grave.getUuid()).thenReturn(UUID.randomUUID()); when(grave.getWorldId()).thenReturn(UUID.randomUUID());
            when(grave.getLocation()).thenReturn(new Location(null, 1,64,2)); when(grave.getTime()).thenReturn(1000L);
            when(grave.getGraveDisplayType()).thenReturn("CHEST"); when(row.getString("grave_display_type")).thenReturn("CHEST");
            when(grave.getItems()).thenThrow(new AssertionError("metadata must not inspect contents"));
        }
        void write(boolean recreation) { if (recreation) table.saveRecreatedGrave(grave); else table.saveDisplayIdentity(grave); }
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void acknowledgedSingleRowNeedsNoReconciliationRead(boolean recreation) throws Exception {
        var h = new Fixture(); when(h.update.executeUpdate()).thenReturn(1); h.write(recreation);
        verifyNoInteractions(h.query); verify(h.grave, never()).getItems();
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void unchangedRetryAcknowledgesOnlyMatchingMetadata(boolean recreation) throws Exception {
        var h = new Fixture(); h.write(recreation);
        assertEquals(2, h.statements.size()); assertFalse(h.statements.getLast().contains("items_encoded"));
        assertFalse(h.statements.getLast().contains("exp")); assertTrue(h.statements.getLast().endsWith("LIMIT 2;")); verify(h.grave, never()).getItems();
        verify(h.query).setString(1, h.grave.getUuid().toString()); verify(h.query).setLong(6,1000L);
        verify(h.row).close(); verify(h.query).close(); verify(h.update).close(); verify(h.connection).close();
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void confirmedMissingIdentityIsTerminalOnlyAfterCleanup(boolean recreation) throws Exception {
        var h = new Fixture(); when(h.row.next()).thenReturn(false);
        assertThrows(SqlConditionNotMet.class, () -> h.write(recreation));
        verify(h.row).close(); verify(h.query).close(); verify(h.update).close(); verify(h.connection).close();
    }
    @Test void displayOnHistoricalRowIsAConfirmedPredicateMiss() throws Exception {
        var h = new Fixture(); when(h.row.getLong("broken")).thenReturn(1L);
        assertThrows(SqlConditionNotMet.class, () -> h.write(false));
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void mismatchedExistingMetadataIsUnknownAndRetriable(boolean recreation) throws Exception {
        var h = new Fixture(); when(h.row.getString("display_uuid")).thenReturn(UUID.randomUUID().toString());
        var failure = assertThrows(IllegalStateException.class, () -> h.write(recreation));
        assertFalse(failure instanceof SqlConditionNotMet); assertInstanceOf(SQLException.class, failure.getCause());
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void transportFailureCannotMasqueradeAsMissing(boolean recreation) throws Exception {
        var h = new Fixture(); when(h.query.executeQuery()).thenThrow(new SQLException("synthetic read failure"));
        var failure = assertThrows(IllegalStateException.class, () -> h.write(recreation)); assertFalse(failure instanceof SqlConditionNotMet);
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void cleanupFailureOverridesOtherwiseConfirmedAbsence(boolean recreation) throws Exception {
        var h = new Fixture(); when(h.row.next()).thenReturn(false); doThrow(new SQLException("synthetic close failure")).when(h.connection).close();
        var failure = assertThrows(IllegalStateException.class, () -> h.write(recreation)); assertFalse(failure instanceof SqlConditionNotMet);
        assertInstanceOf(SQLException.class, failure.getCause());
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void duplicateRowsCannotAcknowledgeAnExactIdentity(boolean recreation) throws Exception {
        var h = new Fixture(); when(h.row.next()).thenReturn(true,true,false);
        var failure = assertThrows(IllegalStateException.class, () -> h.write(recreation)); assertFalse(failure instanceof SqlConditionNotMet);
    }
    @ParameterizedTest @ValueSource(strings = {"row", "query", "update", "connection"})
    void everyCleanupBoundaryMustSucceedBeforeConfirmingAMiss(String resource) throws Exception {
        var h = new Fixture(); when(h.row.next()).thenReturn(false);
        switch (resource) {
            case "row" -> doThrow(new SQLException("synthetic cleanup failure")).when(h.row).close();
            case "query" -> doThrow(new SQLException("synthetic cleanup failure")).when(h.query).close();
            case "update" -> doThrow(new SQLException("synthetic cleanup failure")).when(h.update).close();
            case "connection" -> doThrow(new SQLException("synthetic cleanup failure")).when(h.connection).close();
            default -> throw new AssertionError(resource);
        }
        var failure = assertThrows(IllegalStateException.class, () -> h.write(false));
        assertFalse(failure instanceof SqlConditionNotMet); assertInstanceOf(SQLException.class, failure.getCause());
    }
}
