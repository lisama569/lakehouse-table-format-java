package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.model.DataType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataTypeTest {

    private static final Logger LOG = LoggerFactory.getLogger(DataTypeTest.class);

    @Test
    void coerceValidValues() {
        assertEquals("abc", DataType.STRING.coerce("abc"));
        assertEquals(42L, DataType.LONG.coerce(42));
        assertEquals(42L, DataType.LONG.coerce(42L));
        assertEquals(1.0d, DataType.DOUBLE.coerce(1));
        assertEquals(2.5d, DataType.DOUBLE.coerce(2.5d));
        assertEquals(Boolean.TRUE, DataType.BOOLEAN.coerce(true));
        assertNull(DataType.STRING.coerce(null));
        LOG.info("[type] valid coercions accepted: int->LONG, int->DOUBLE, String, Boolean");
    }

    @Test
    void rejectMismatchedTypesWithDistinctFailure() {
        assertThrows(IllegalArgumentException.class, () -> DataType.LONG.coerce(1.5d));
        assertThrows(IllegalArgumentException.class, () -> DataType.LONG.coerce("1"));
        assertThrows(IllegalArgumentException.class, () -> DataType.DOUBLE.coerce("1.2"));
        assertThrows(IllegalArgumentException.class, () -> DataType.BOOLEAN.coerce("true"));
        LOG.warn("[type:reject] TYPE_MISMATCH basis=strict coercion (fractional->long, string->number/boolean)");
    }
}
