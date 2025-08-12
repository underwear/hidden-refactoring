package com.hiddenrefactoring.gutter

import com.hiddenrefactoring.keys.KeyFactory
import com.hiddenrefactoring.storage.CommentsService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.util.FunctionUtil
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.openapi.ui.Messages
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import java.awt.event.MouseEvent

class CommentLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        if (elements.isEmpty()) return
        val project: Project = elements.first().project
        val svc = project.service<CommentsService>()

        for (element in elements) {
            // Only place icon on the NAME IDENTIFIER token of declarations
            val parent = element.parent
            val key = when (parent) {
                is Method -> if (parent.nameIdentifier == element) KeyFactory.fromMethod(parent) else null
                is Function -> if (parent.nameIdentifier == element) KeyFactory.fromFunction(parent) else null
                is PhpClass -> if (parent.nameIdentifier == element) KeyFactory.fromClass(parent) else null
                else -> null
            } ?: continue

            val comments = svc.getComments(key)
            if (comments.isEmpty()) continue

            val handler = GutterIconNavigationHandler<PsiElement> { _: MouseEvent, _: PsiElement ->
                val text = comments.joinToString("\n\n") { c -> c.text }
                Messages.showMessageDialog(project, text, "Hidden Refactoring: Comments (${comments.size})", Messages.getInformationIcon())
            }

            val info = LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.General.Balloon,
                FunctionUtil.constant("Hidden Refactoring: ${comments.size} comment(s)"),
                handler,
                GutterIconRenderer.Alignment.RIGHT,
                { "Hidden Refactoring Comments" }
            )
            @Suppress("UNCHECKED_CAST")
            (result as MutableCollection<LineMarkerInfo<PsiElement>>).add(info)
        }
    }
}
