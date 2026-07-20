package com.pokebuddy.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct coverage of the hand-rolled tokenizer under [BackupCodec]. */
class JsonTest {

    @Test fun scalars_round_trip() {
        for (v in listOf("hi", 42, -7, 9_000_000_000L, true, false, null)) {
            assertEquals(v, Json.parse(Json.write(v)))
        }
    }

    @Test fun a_string_with_every_escape_round_trips() {
        val s = "quote\" back\\slash new\nline tab\ttab slash/ end"
        assertEquals(s, Json.parse(Json.write(s)))
    }

    @Test fun empty_containers() {
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
        assertEquals(emptyMap<String, Any?>(), Json.parse("{}"))
    }

    @Test fun nested_object_and_array() {
        val doc = mapOf("a" to listOf(1, 2, mapOf("b" to null)), "c" to "x")
        assertEquals(doc, Json.parse(Json.write(doc)))
    }

    @Test fun whitespace_between_tokens_is_ignored() {
        assertEquals(
            mapOf("a" to 1, "b" to listOf(2, 3)),
            Json.parse("  { \"a\" : 1 , \"b\" : [ 2 , 3 ] }  "),
        )
    }

    @Test fun a_large_number_becomes_long_not_overflowed_int() {
        assertEquals(1_784_432_327_641L, Json.parse("1784432327641"))
        assertTrue(Json.parse("1784432327641") is Long)
    }

    @Test fun trailing_content_is_rejected() {
        assertThrows(Json.ParseException::class.java) { Json.parse("{} garbage") }
    }

    @Test fun unterminated_string_is_rejected() {
        assertThrows(Json.ParseException::class.java) { Json.parse("\"no end") }
    }

    @Test fun a_bare_null_parses_to_null() {
        assertNull(Json.parse("null"))
    }
}
