package jp.funmake.espresso.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.NonNls

@NonNls
class InsertChildAtPositionAction : PsiElementBaseIntentionAction(), IntentionAction {

    override fun getText(): String = "Insert childAtPosition function"

    override fun getFamilyName(): String = "Insert childAtPosition function"

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        return true
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (editor == null) return

        TemplateManager.getInstance(project).run {
            val template = createTemplate("", "").apply {
                isToReformat = true
                addTextSegment(
                    """
                    fun childAtPosition(parentMatcher: Matcher<View>, position: Int): Matcher<View> {

                        return object : TypeSafeMatcher<View>() {
                            override fun describeTo(description: Description) {
                                description.appendText("Child at position ${'$'}position in parent ")
                                parentMatcher.describeTo(description)
                            }

                            override fun matchesSafely(view: View): Boolean {
                                val parent = view.parent
                                return parent is ViewGroup && parentMatcher.matches(parent)
                                        && view == parent.getChildAt(position)
                            }
                        }
                    }
                    """.trimIndent()
                )
            }
            startTemplate(editor, template)
        }
    }
}