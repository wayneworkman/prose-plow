package us.theworkmans.proseplow.llama

import org.junit.Assert.assertEquals
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelSpec

/**
 * The engine's largest evaluation chunk and the batch the native session is opened with are one
 * number written in two modules. A chunk above the batch would be refused on the phone, and a
 * batch above the chunk pays for a work buffer no chunk uses, so the two must not drift apart.
 */
class EvaluationBatchTest {

    @Test
    fun `the largest evaluation chunk is the batch the native session is opened with`() {
        assertEquals(ModelSpec.MAX_EVALUATION_CHUNK, NativeSettings().batchTokens)
    }
}
