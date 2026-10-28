package pro.sketchware.ai.tools

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Small, deterministic XML reader/writer for Android manifest fragments and `res/values` files.
 * Deliberately avoids `Transformer`: output order, indentation and entity escaping are fixed so
 * repeated edits never churn a file.
 */
internal object XmlLite {

    private const val INDENT = "    "
    private const val WRAPPER_OPEN =
        "<root xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
            "xmlns:tools=\"http://schemas.android.com/tools\" xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\">"

    fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isIgnoringComments = false
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
        }
        try {
            return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw IllegalArgumentException("XML tidak valid: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}")
        }
    }

    /** Parses a sequence of sibling elements (a fragment) by wrapping it in a synthetic root. */
    fun parseFragment(xml: String): Document = parse("$WRAPPER_OPEN\n$xml\n</root>")

    fun childElements(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
        }
        return out
    }

    /** Serializes the children of a fragment root, one top-level element/comment per entry. */
    fun serializeFragment(root: Element): String {
        val parts = ArrayList<String>()
        for (n in meaningfulChildren(root)) {
            val sb = StringBuilder()
            when (n.nodeType) {
                Node.ELEMENT_NODE -> writeElement(sb, n as Element, 0)
                Node.COMMENT_NODE -> sb.append("<!--").append(n.nodeValue).append("-->")
            }
            if (sb.isNotEmpty()) parts.add(sb.toString())
        }
        return parts.joinToString("\n")
    }

    fun serializeElement(el: Element, depth: Int = 0): String {
        val sb = StringBuilder()
        writeElement(sb, el, depth)
        return sb.toString()
    }

    fun serializeDocument(doc: Document, withDeclaration: Boolean): String {
        val sb = StringBuilder()
        if (withDeclaration) sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        writeElement(sb, doc.documentElement, 0)
        sb.append('\n')
        return sb.toString()
    }

    private fun meaningfulChildren(el: Element): List<Node> {
        val out = ArrayList<Node>()
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            when (n.nodeType) {
                Node.ELEMENT_NODE, Node.COMMENT_NODE -> out.add(n)
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> if (!n.nodeValue.isNullOrBlank()) out.add(n)
            }
        }
        return out
    }

    private fun hasText(el: Element): Boolean {
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if ((n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) && !n.nodeValue.isNullOrBlank()) return true
        }
        return false
    }

    private fun writeElement(sb: StringBuilder, el: Element, depth: Int) {
        val pad = INDENT.repeat(depth)
        sb.append(pad)
        if (hasText(el)) {
            writeInline(sb, el)
            return
        }
        val kids = meaningfulChildren(el)
        sb.append('<').append(el.tagName)
        appendAttributes(sb, el)
        if (kids.isEmpty()) {
            sb.append("/>")
            return
        }
        sb.append(">\n")
        for (n in kids) {
            when (n.nodeType) {
                Node.ELEMENT_NODE -> {
                    writeElement(sb, n as Element, depth + 1)
                    sb.append('\n')
                }
                Node.COMMENT_NODE -> sb.append(pad).append(INDENT).append("<!--").append(n.nodeValue).append("-->\n")
            }
        }
        sb.append(pad).append("</").append(el.tagName).append('>')
    }

    /** Mixed content (text and inline markup) is written verbatim so string values never gain whitespace. */
    private fun writeInline(sb: StringBuilder, node: Node) {
        when (node.nodeType) {
            Node.ELEMENT_NODE -> {
                val el = node as Element
                sb.append('<').append(el.tagName)
                appendAttributes(sb, el)
                val kids = el.childNodes
                if (kids.length == 0) {
                    sb.append("/>")
                } else {
                    sb.append('>')
                    for (i in 0 until kids.length) writeInline(sb, kids.item(i))
                    sb.append("</").append(el.tagName).append('>')
                }
            }
            Node.TEXT_NODE -> sb.append(escapeText(node.nodeValue ?: ""))
            Node.CDATA_SECTION_NODE -> sb.append("<![CDATA[").append(node.nodeValue).append("]]>")
            Node.COMMENT_NODE -> sb.append("<!--").append(node.nodeValue).append("-->")
        }
    }

    private fun appendAttributes(sb: StringBuilder, el: Element) {
        val attrs = el.attributes
        val names = ArrayList<String>()
        for (i in 0 until attrs.length) names.add(attrs.item(i).nodeName)
        names.sortWith(compareBy<String>({ !(it == "android:name" || it == "name") }, { it }))
        for (n in names) {
            sb.append(' ').append(n).append("=\"").append(escapeAttr(el.getAttribute(n))).append('"')
        }
    }

    fun escapeText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun escapeAttr(s: String): String =
        escapeText(s).replace("\"", "&quot;").replace("\n", "&#10;").replace("\t", "&#9;")
}
