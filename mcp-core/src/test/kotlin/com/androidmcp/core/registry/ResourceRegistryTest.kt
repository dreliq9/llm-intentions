package com.androidmcp.core.registry

import com.androidmcp.core.protocol.ReadResourceResult
import com.androidmcp.core.protocol.Resource
import com.androidmcp.core.protocol.ResourceContents
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResourceRegistryTest {

    private fun dummyResource(uri: String) = McpResourceDef(
        info = Resource(uri = uri, name = "Resource $uri"),
        handler = { u -> ReadResourceResult(contents = listOf(ResourceContents.text(u, "ok"))) }
    )

    @Test
    fun `register and retrieve resource`() {
        val registry = ResourceRegistry()
        registry.register(dummyResource("test://a"))

        assertNotNull(registry.get("test://a"))
        assertEquals("test://a", registry.get("test://a")!!.info.uri)
        assertEquals(1, registry.size())
    }

    @Test
    fun `get returns null for unknown uri`() {
        val registry = ResourceRegistry()
        assertNull(registry.get("test://nope"))
    }

    @Test
    fun `register replaces existing resource with same uri`() {
        val registry = ResourceRegistry()
        registry.register(dummyResource("test://a"))
        registry.register(McpResourceDef(
            info = Resource(uri = "test://a", name = "Replaced"),
            handler = { u -> ReadResourceResult(contents = listOf(ResourceContents.text(u, "replaced"))) }
        ))

        assertEquals(1, registry.size())
        assertEquals("Replaced", registry.get("test://a")!!.info.name)
    }

    @Test
    fun `unregister removes resource`() {
        val registry = ResourceRegistry()
        registry.register(dummyResource("test://a"))
        registry.register(dummyResource("test://b"))

        assertEquals(2, registry.size())
        registry.unregister("test://a")
        assertEquals(1, registry.size())
        assertNull(registry.get("test://a"))
        assertNotNull(registry.get("test://b"))
    }

    @Test
    fun `clear removes all resources`() {
        val registry = ResourceRegistry()
        registry.register(dummyResource("test://a"))
        registry.register(dummyResource("test://b"))

        registry.clear()
        assertEquals(0, registry.size())
        assertNull(registry.get("test://a"))
    }

    @Test
    fun `list returns all resource infos`() {
        val registry = ResourceRegistry()
        registry.register(dummyResource("test://alpha"))
        registry.register(dummyResource("test://beta"))

        val list = registry.list()
        assertEquals(2, list.size)
        assertEquals(setOf("test://alpha", "test://beta"), list.map { it.uri }.toSet())
    }

    @Test
    fun `concurrent register from multiple threads`() {
        val registry = ResourceRegistry()
        val threads = (1..100).map { i ->
            Thread { registry.register(dummyResource("test://r_$i")) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(100, registry.size())
    }
}
