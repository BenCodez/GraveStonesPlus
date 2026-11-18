package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.gravestonesplus.storage.*;

class SqlRemovalOutcomeTest {
    private static SqlMetadataOutcomeTest.Fixture fixture() throws Exception {
        var h=new SqlMetadataOutcomeTest.Fixture();when(h.grave.isDestroyed()).thenReturn(true);when(h.grave.getExp()).thenReturn(17);
        when(h.grave.getDestroyedTime()).thenReturn(2000L);
        var cached=GravesConfig.class.getDeclaredMethod("encodedItemsForStorage");cached.setAccessible(true);
        when((String)cached.invoke(h.grave)).thenReturn("synthetic-items");return h;
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void changedAndUnchangedDriversRequireOneExactStoredTerminalSnapshot(int affected) throws Exception {
        var h=fixture();when(h.update.executeUpdate()).thenReturn(affected);h.table.removeExistingGraveChecked(h.grave);
        assertEquals(2,h.statements.size());assertTrue(h.statements.getFirst().startsWith("UPDATE "));
        assertTrue(h.statements.getLast().startsWith("SELECT 1 "));assertTrue(h.statements.getLast().endsWith("LIMIT 2;"));
        assertTrue(h.statements.getLast().contains("CAST(`items_encoded` AS BINARY) <=> CAST(? AS BINARY)"));
        verify(h.query).setInt(7,1);verify(h.query).setInt(10,17);verify(h.query).setInt(11,1);verify(h.query).setLong(12,2000L);
        verify(h.query).setString(16,"synthetic-items");verify(h.grave,never()).getItems();
        verify(h.row).close();verify(h.query).close();verify(h.update).close();verify(h.connection).close();
    }
    @Test void missingOrDifferentStoredSnapshotIsHeldNotSuccessfulOrInsertCapable() throws Exception {
        var h=fixture();when(h.row.next()).thenReturn(false);
        var error=assertThrows(IllegalStateException.class,()->h.table.removeExistingGraveChecked(h.grave));
        assertFalse(error instanceof SqlConditionNotMet);assertInstanceOf(SQLException.class,error.getCause());
        assertTrue(h.statements.stream().noneMatch(sql->sql.contains("INSERT")));verify(h.connection).close();
    }
    @Test void duplicateStoredSnapshotsCannotAcknowledgeRemoval() throws Exception {
        var h=fixture();when(h.row.next()).thenReturn(true,true,false);
        assertThrows(IllegalStateException.class,()->h.table.removeExistingGraveChecked(h.grave));verify(h.row).close();
    }
    @ParameterizedTest @ValueSource(ints={-1,2})
    void unexpectedAffectedCountCannotAcknowledgeRemoval(int affected) throws Exception {
        var h=fixture();when(h.update.executeUpdate()).thenReturn(affected);
        assertThrows(IllegalStateException.class,()->h.table.removeExistingGraveChecked(h.grave));verifyNoInteractions(h.query);
    }
    @Test void transportFailureCannotAcknowledgeRemoval() throws Exception {
        var h=fixture();when(h.query.executeQuery()).thenThrow(new SQLException("synthetic confirmation failure"));
        assertThrows(IllegalStateException.class,()->h.table.removeExistingGraveChecked(h.grave));verify(h.connection).close();
    }
    @ParameterizedTest @ValueSource(strings={"row","query","update","connection"})
    void cleanupFailureCannotAcknowledgeRemoval(String resource) throws Exception {
        var h=fixture();var error=new SQLException("synthetic cleanup failure");
        switch(resource) {case "row"->doThrow(error).when(h.row).close();case "query"->doThrow(error).when(h.query).close();
            case "update"->doThrow(error).when(h.update).close();case "connection"->doThrow(error).when(h.connection).close();default->throw new AssertionError();}
        assertThrows(IllegalStateException.class,()->h.table.removeExistingGraveChecked(h.grave));
    }
    @Test void nonAutocommitAndNonTerminalInputsDoNotMutateRows() throws Exception {
        var h=fixture();when(h.connection.getAutoCommit()).thenReturn(false);
        assertThrows(IllegalStateException.class,()->h.table.removeExistingGraveChecked(h.grave));verify(h.update,never()).executeUpdate();
        when(h.grave.isDestroyed()).thenReturn(false);assertThrows(IllegalArgumentException.class,()->h.table.removeExistingGraveChecked(h.grave));
    }
    @Test void legacyCheckedUpdateKeepsItsMissingRowNoOpContract() throws Exception {
        var h=fixture();var legacy=GravesMySQLTable.class.getDeclaredMethod("updateExistingGraveChecked",GravesConfig.class,boolean.class);
        legacy.setAccessible(true);legacy.invoke(h.table,h.grave,true);verifyNoInteractions(h.query);verify(h.update).executeUpdate();
    }
    @Test void mysqlBackendRoutesExactRemovalAndFailsClosedBeforeInitialization() throws Exception {
        var h=fixture();var backend=mock(MySQLGraveStorageBackend.class,CALLS_REAL_METHODS);
        assertThrows(IllegalStateException.class,()->backend.removeExistingGrave(h.grave));
        SqlMetadataOutcomeTest.field(backend,MySQLGraveStorageBackend.class,"table",h.table);
        backend.removeExistingGrave(h.grave);verify(h.table).removeExistingGraveChecked(h.grave);
        verify(h.query,times(1)).executeQuery();
    }
}
