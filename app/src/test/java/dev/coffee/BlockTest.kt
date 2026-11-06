package dev.coffee

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BlockTest {
    @Test fun legacyHistorySurvivesCacheRoundTrip() {
        val legacy = JSONArray("""[
            {"id":1,"t":1000,"role":"user","type":"message","text":"Yes, I went.","typed":"ya","key":"m1"},
            {"id":2,"t":2000,"role":"user","type":"image","path":"/old/photo.jpg"},
            {"id":3,"t":3000,"role":"coffee","type":"status","text":"Reading notes"},
            {"id":4,"t":4000,"role":"user","type":"reply","ref":1,"text":"quoted text"}
        ]""")
        val blocks = Block.list(legacy)
        val restored = Block.list(JSONArray(blocks.map { it.json() }))
        assertEquals(blocks, restored)
        assertEquals("ya", restored[0].typed)
        assertEquals("/old/photo.jpg", restored[1].path)
        assertEquals(1L, restored[3].ref)
        assertFalse(restored[2].user)
    }

    @Test fun minimalServerMessageNeedsNoLegacyFields() {
        val b = Block.of(JSONObject("""{"id":5,"t":1000,"role":"coffee","type":"message","text":"Hello"}"""))
        assertEquals("Hello", b.text)
        assertTrue(b.message)
        assertFalse(b.err)
        assertEquals("", b.path)
    }
}
