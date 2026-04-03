package com.androidmcp.core.registry

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonSchemaBuilderTest {

    @Test
    fun `empty schema produces valid object type`() {
        val schema = jsonSchema { }
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(0, schema["properties"]?.jsonObject?.size)
        assertFalse(schema.containsKey("required"))
    }

    @Test
    fun `string property with default required`() {
        val schema = jsonSchema {
            string("name", "A name")
        }

        val props = schema["properties"]!!.jsonObject
        assertEquals(1, props.size)
        assertEquals("string", props["name"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("A name", props["name"]!!.jsonObject["description"]?.jsonPrimitive?.content)

        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("name" in required)
    }

    @Test
    fun `optional string not in required list`() {
        val schema = jsonSchema {
            string("name", "A name", required = false)
        }

        assertFalse(schema.containsKey("required"))
    }

    @Test
    fun `integer property`() {
        val schema = jsonSchema {
            integer("count", "A count")
        }

        val props = schema["properties"]!!.jsonObject
        assertEquals("integer", props["count"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `number property`() {
        val schema = jsonSchema {
            number("price", "A price")
        }

        val props = schema["properties"]!!.jsonObject
        assertEquals("number", props["price"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `boolean property defaults to optional`() {
        val schema = jsonSchema {
            boolean("flag", "A flag")
        }

        assertFalse(schema.containsKey("required"),
            "boolean should default to optional (required=false)")
    }

    @Test
    fun `boolean property can be required`() {
        val schema = jsonSchema {
            boolean("flag", "A flag", required = true)
        }

        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("flag" in required)
    }

    @Test
    fun `enum property has enum values`() {
        val schema = jsonSchema {
            enum("color", "Pick a color", listOf("red", "green", "blue"))
        }

        val props = schema["properties"]!!.jsonObject
        val colorProp = props["color"]!!.jsonObject
        assertEquals("string", colorProp["type"]?.jsonPrimitive?.content)

        val enumValues = colorProp["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("red", "green", "blue"), enumValues)
    }

    @Test
    fun `mixed required and optional properties`() {
        val schema = jsonSchema {
            string("name", "Required name")
            string("nickname", "Optional nickname", required = false)
            integer("age", "Required age")
            boolean("active", "Optional flag")
        }

        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("name", "age"), required)

        val props = schema["properties"]!!.jsonObject
        assertEquals(4, props.size)
    }

    @Test
    fun `schema is valid JSON Schema structure`() {
        val schema = jsonSchema {
            string("query", "Search query")
            integer("limit", "Max results", required = false)
        }

        // Must be a JSON object
        assertTrue(schema is JsonObject)
        // Must have type: object
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        // Must have properties as an object
        assertTrue(schema["properties"] is JsonObject)
        // Required must be an array of strings if present
        val required = schema["required"]
        if (required != null) {
            assertTrue(required is JsonArray)
            required.jsonArray.forEach { assertTrue(it is JsonPrimitive) }
        }
    }
}
