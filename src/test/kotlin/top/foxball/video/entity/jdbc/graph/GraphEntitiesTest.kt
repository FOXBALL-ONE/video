package top.foxball.video.entity.jdbc.graph

import jakarta.persistence.Column
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphEntitiesTest {
    @Test
    fun `workflow snapshot retains the complete ComfyUI document as jsonb`() {
        val document = GraphWorkflowSnapshot::document
        val column = document.javaField?.getAnnotation(Column::class.java)
        val jdbcType = document.javaField?.getAnnotation(JdbcTypeCode::class.java)

        assertEquals("jsonb", column?.columnDefinition)
        assertEquals(SqlTypes.JSON, jdbcType?.value)
        assertTrue(column?.nullable == false)
    }

    @Test
    fun `link projection distinguishes floating links and preserves their wire format`() {
        val floating = GraphLink::floating.javaField?.getAnnotation(Column::class.java)
        val format = GraphLink::serializedLinkKind.javaField
            ?.getAnnotation(Column::class.java)

        assertEquals("is_floating", floating?.name)
        assertEquals("serialized_link_kind", format?.name)
        assertTrue(SerializedLinkKind.entries.contains(SerializedLinkKind.ARRAY))
        assertTrue(SerializedLinkKind.entries.contains(SerializedLinkKind.OBJECT))
    }

    @Test
    fun `subgraph instance contains the complete node projection fields`() {
        val properties = GraphSubgraphInstance::class.memberProperties.map { it.name }.toSet()

        assertTrue(
            properties.containsAll(
                setOf(
                    "title",
                    "shape",
                    "boxColor",
                    "properties",
                    "widgetsValuesNamed",
                    "extensions",
                    "serializedPayload",
                ),
            ),
        )
    }

    @Test
    fun `graph identifiers are unique only inside a project version`() {
        val unique = GraphCanvas::class.java.getAnnotation(Table::class.java)
            ?.uniqueConstraints
            ?.singleOrNull()

        assertNotNull(unique)
        assertEquals(
            setOf("project_id", "project_version_id", "graph_uuid"),
            unique.columnNames.toSet(),
        )
    }

    @Test
    fun `node and reroute projections prevent duplicate keys within one graph`() {
        fun Table.uniqueColumns(): Set<Set<String>> = uniqueConstraints
            .map(UniqueConstraint::columnNames)
            .map(Array<String>::toSet)
            .toSet()

        assertTrue(
            setOf("graph_id", "node_key") in
                requireNotNull(GraphNode::class.java.getAnnotation(Table::class.java)).uniqueColumns(),
        )
        assertTrue(
            setOf("graph_id", "reroute_key") in
                requireNotNull(GraphReroute::class.java.getAnnotation(Table::class.java)).uniqueColumns(),
        )
    }
}
