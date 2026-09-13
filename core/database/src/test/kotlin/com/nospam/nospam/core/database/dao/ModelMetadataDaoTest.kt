package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.ModelMetadataEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ModelMetadataDao (via InMemoryModelMetadataDao) had no dedicated test -- named gap. */
class ModelMetadataDaoTest {

    @Test fun `get returns null before any upsert`() = runTest {
        val dao = InMemoryModelMetadataDao()
        assertNull(dao.get())
    }

    @Test fun `upsert then get returns the stored row`() = runTest {
        val dao = InMemoryModelMetadataDao()
        val entity = ModelMetadataEntity(version = "1.0.0", threshold = 0.5, updatedAt = 100L)
        dao.upsert(entity)
        assertEquals(entity, dao.get())
    }

    @Test fun `a second upsert replaces the single row rather than accumulating`() = runTest {
        val dao = InMemoryModelMetadataDao()
        dao.upsert(ModelMetadataEntity(version = "1.0.0"))
        dao.upsert(ModelMetadataEntity(version = "2.0.0"))
        assertEquals("2.0.0", dao.get()?.version)
    }
}
