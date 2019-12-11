package jp.funmake.espresso.completion

import com.android.layoutinspector.model.ViewNode
import com.android.layoutinspector.parser.LayoutFileDataParser
import com.android.tools.idea.editors.layoutInspector.LayoutInspectorContext
import com.android.tools.idea.editors.layoutInspector.LayoutInspectorEditor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ProcessingContext
import java.io.FileWriter

class ViewInteractionCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val project = parameters.editor.project ?: throw IllegalStateException()
        val liEditors = getLayoutInspectorEditors(project)

        FileWriter("dump.txt").use { out ->
            for (editor in liEditors) {
                val liFile = editor.virtualFile?.let { VfsUtilCore.virtualToIoFile(it) } ?: continue
                val liContext = LayoutInspectorContext(LayoutFileDataParser.parseFromFile(liFile), editor)
                visit(liContext.root) { node, depth -> out.dump(node, depth) }

                val idCount = mutableMapOf<String, Int>()
                visit(liContext.root) { node, _ ->
                    node.id?.let { id ->
                        if (id.startsWith("id/")) {
                            idCount.compute(id.replace("id/", "")) { _, count ->
                                (count ?: 0) + 1
                            }
                        }
                    }
                }

                var sequenceNo = 1
                for (n in liContext.root.children) {
                    visit(n) { node, depth ->
                        result.addElement(createLookupElement(sequenceNo++, node, depth, idCount))
                    }
                }
            }
        }

        result.addElement(LookupElementBuilder.create("""
                    fun childAtPosition(parentMatcher: Matcher<View>, position: Int): Matcher<View> {

                        return object : TypeSafeMatcher<View>() {
                            override fun describeTo(description: Description) {
                                description.appendText("Child at position ${'$'}position in parent ")
                                parentMatcher.describeTo(description)
                            }

                            public override fun matchesSafely(view: View): Boolean {
                                val parent = view.parent
                                return parent is ViewGroup && parentMatcher.matches(parent)
                                        && view == parent.getChildAt(position)
                            }
                        }
                    }
                    """.trimIndent())
            .withLookupString("childAtPosition")
            .withPresentableText("fun childAtPosition")
            .withTailText("(parentMatcher: Matcher<View>, position: Int)(parentMatcher: Matcher<View>, position: Int)", true)
            .withTypeText("Matcher<View>", true)
        )
    }

    private fun createLookupElement(
        sequenceNo: Int,
        node: ViewNode,
        depth: Int,
        idCount: Map<String, Int>
    ): LookupElementBuilder {
        val parent = node.parent ?: throw IllegalArgumentException("The node must have the parent node.")
        val id = node.id?.replace("id/", "") ?: ""
        val order = "%03d".format(sequenceNo)
        val indent = " ".repeat(depth)

        return when (idCount.getOrDefault(id, 0)) {
            1 -> LookupElementBuilder.create(
                """
                onView(
                    allOf(
                        withId(R.id.$id),
                        isDisplayed()
                    )
                )
                """.trimIndent())
                .withOnView("onView($order:$indent[${node.index}]$id)")
            0 -> LookupElementBuilder.create(
                """
                onView(
                    allOf(
                        withClassName(`is`("${node.name}")),
                        childAtPosition(
                            ${getParentNode(parent, idCount)},
                            ${node.index}
                        ),
                        isDisplayed()
                    )
                )
                """.trimIndent())
                .withOnView("onView($order:$indent[${node.index}]${node.name.split(".").last()})")
            else -> LookupElementBuilder.create(
                """
                onView(
                    allOf(
                        withId(R.id.$id),
                        childAtPosition(
                            ${getParentNode(parent, idCount)},
                            ${node.index}
                        ),
                        isDisplayed()
                    )
                )
                """.trimIndent())
                .withOnView("onView($order:$indent[${node.index}]$id)")
        }
    }

    private fun LookupElementBuilder.withOnView(onView: String): LookupElementBuilder = this
        .withLookupStrings(mutableListOf("Espresso.$onView", onView))
        .withPresentableText("Espresso.$onView")
        .withTypeText("ViewInteraction", true)

    private fun getParentNode(node: ViewNode, idCount: Map<String, Int>): String {
        val parent = node.parent
        val id = node.id?.replace("id/", "") ?: ""

        if (parent == null) {
            return "withClassName(`is`(\"${node.name}\"))"
        }

        return when (idCount[id]) {
            1 -> "withId(R.id.$id)"
            else -> """
                childAtPosition(
                    ${getParentNode(parent, idCount)},
                    ${node.index}
                )
                """.trimIndent()
        }
    }

    private fun visit(node: ViewNode, depth: Int = 0, action: (ViewNode, Int) -> Unit) {
        action(node, depth)
        for (n in node.children) {
            visit(n, depth + 1, action)
        }
    }

    private val LayoutInspectorEditor.virtualFile: VirtualFile?
        get() {
            val clazz = LayoutInspectorEditor::class.java
            return clazz.getDeclaredField("myVirtualFile").let { virtualFileField ->
                virtualFileField.isAccessible = true
                virtualFileField.get(this) as VirtualFile
            }
        }

    private fun getLayoutInspectorEditors(project: Project): List<LayoutInspectorEditor> {
        return FileEditorManager.getInstance(project)
            .allEditors
            .filterIsInstance<LayoutInspectorEditor>()
    }

    private fun FileWriter.dump(node: ViewNode, depth: Int) {
        write("${"  ".repeat(depth)}${node.name} ${node.id} ${node.displayInfo.contentDesc}\n")
    }
}