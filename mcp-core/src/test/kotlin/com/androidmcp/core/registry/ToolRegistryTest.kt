package com.androidmcp.core.registry

import com.androidmcp.core.protocol.ContentBlock
import com.androidmcp.core.protocol.ToolCallResult
import com.androidmcp.core.protocol.ToolInfo
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ToolRegistryTest {

    private fun dummyTool(name: String) = McpToolDef(
        info = ToolInfo(
            name = name,
            description = "Test tool $name",
            inputSchema = buildJsonObject { }
        ),
        handler = { ToolCallResult(content = listOf(ContentBlock.text("ok"))) }
    )

    @Test
    fun `register and retrieve tool`() {
        val registry = ToolRegistry()
        registry.register(dummyTool("test"))

        assertNotNull(registry.get("test"))
        assertEquals("test", registry.get("test")!!.info.name)
        assertEquals(1, registry.size())
    }

    @Test
    fun `get returns null for unknown tool`() {
        val registry = ToolRegistry()
        assertNull(registry.get("nonexistent"))
    }

    @Test
    fun `register replaces existing tool with same name`() {
        val registry = ToolRegistry()
        registry.register(dummyTool("test"))
        registry.register(McpToolDef(
            info = ToolInfo("test", "replaced", buildJsonObject {}),
            handler = { ToolCallResult(content = listOf(ContentBlock.text("replaced"))) }
        ))

        assertEquals(1, registry.size())
        assertEquals("replaced", registry.get("test")!!.info.description)
    }

    @Test
    fun `unregister removes tool`() {
        val registry = ToolRegistry()
        registry.register(dummyTool("a"))
        registry.register(dummyTool("b"))

        assertEquals(2, registry.size())
        registry.unregister("a")
        assertEquals(1, registry.size())
        assertNull(registry.get("a"))
        assertNotNull(registry.get("b"))
    }

    @Test
    fun `unregister nonexistent tool is no-op`() {
        val registry = ToolRegistry()
        registry.register(dummyTool("a"))
        registry.unregister("nonexistent")
        assertEquals(1, registry.size())
    }

    @Test
    fun `clear removes all tools`() {
        val registry = ToolRegistry()
        registry.register(dummyTool("a"))
        registry.register(dummyTool("b"))
        registry.register(dummyTool("c"))

        assertEquals(3, registry.size())
        registry.clear()
        assertEquals(0, registry.size())
        assertNull(registry.get("a"))
    }

    @Test
    fun `list returns all tool infos`() {
        val registry = ToolRegistry()
        registry.register(dummyTool("alpha"))
        registry.register(dummyTool("beta"))

        val list = registry.list()
        assertEquals(2, list.size)
        val names = list.map { it.name }.toSet()
        assertEquals(setOf("alpha", "beta"), names)
    }

    @Test
    fun `list is empty for empty registry`() {
        val registry = ToolRegistry()
        assertEquals(0, registry.list().size)
    }

    @Test
    fun `concurrent register from multiple threads`() {
        val registry = ToolRegistry()
        val threads = (1..100).map { i ->
            Thread { registry.register(dummyTool("tool_$i")) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(100, registry.size())
    }

    @Test
    fun `concurrent read during write`() {
        val registry = ToolRegistry()
        // Pre-populate
        (1..50).forEach { registry.register(dummyTool("tool_$it")) }

        val errors = java.util.concurrent.atomic.AtomicInteger(0)

        // Readers and writers running concurrently
        val writers = (51..100).map { i ->
            Thread { registry.register(dummyTool("tool_$i")) }
        }
        val readers = (1..50).map {
            Thread {
                try {
                    registry.list() // Should not throw
                    registry.size()
                } catch (_: Exception) {
                    errors.incrementAndGet()
                }
            }
        }

        (writers + readers).shuffled().forEach { it.start() }
        (writers + readers).forEach { it.join() }

        assertEquals(0, errors.get(), "Concurrent read/write caused errors")
        assertEquals(100, registry.size())
    }
}
