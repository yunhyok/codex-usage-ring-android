package io.github.yunhyok.usagering

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetMetadataTest {
    @Test
    fun widgetStartsAtOneCellAndCanResizeDownToOneCellOnOlderLaunchers() {
        val path = listOf(
            Path.of("src/main/res/xml/usage_ring_widget_info.xml"),
            Path.of("app/src/main/res/xml/usage_ring_widget_info.xml"),
        ).first { Files.isRegularFile(it) }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val provider = factory.newDocumentBuilder().parse(path.toFile()).documentElement
        val android = "http://schemas.android.com/apk/res/android"
        listOf("minWidth", "minHeight", "minResizeWidth", "minResizeHeight").forEach {
            assertEquals(it, "40dp", provider.getAttributeNS(android, it))
        }
        listOf("targetCellWidth", "targetCellHeight").forEach {
            assertEquals(it, "1", provider.getAttributeNS(android, it))
        }
        assertEquals("horizontal|vertical", provider.getAttributeNS(android, "resizeMode"))
    }
}
