package com.hiddenrefactoring.codevision

import com.hiddenrefactoring.keys.KeyFactory
import com.hiddenrefactoring.storage.CommentsService
import com.intellij.codeInsight.codeVision.*
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.openapi.components.service
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.project.DumbService
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.HyperlinkLabel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JLabel
import com.intellij.codeInsight.codeVision.CodeVisionHost
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.intellij.util.ui.JBUI

class PhpCommentsCodeVisionProvider : CodeVisionProvider<Unit> {
    companion object {
        const val ID: String = "php.comments"
    }

    override fun isAvailableFor(project: Project): Boolean = true

    override fun precomputeOnUiThread(editor: Editor) { /* no-op */ }

    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        return runReadAction {
            val project = editor.project ?: return@runReadAction CodeVisionState.Ready(emptyList())
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction CodeVisionState.Ready(emptyList())
            if (!psiFile.language.isKindOf(PhpLanguage.INSTANCE)) return@runReadAction CodeVisionState.Ready(emptyList())

            val svc = project.service<CommentsService>()

            val entries = mutableListOf<Pair<TextRange, CodeVisionEntry>>()
            // Traverse only PHP declarations
            PsiTreeUtil.processElements(psiFile) { el ->
                val key = when (el) {
                    is Method -> KeyFactory.fromMethod(el)
                    is Function -> KeyFactory.fromFunction(el)
                    is PhpClass -> KeyFactory.fromClass(el)
                    else -> null
                }
                if (key != null && svc.hasComments(key)) {
                    val anchor: PsiElement = when (el) {
                        is Method -> el.nameIdentifier ?: el
                        is Function -> el.nameIdentifier ?: el
                        is PhpClass -> el.nameIdentifier ?: el
                        else -> el
                    }
                    getHintText(svc, key)?.let { text ->
                        entries += anchor.textRange to TextCodeVisionEntry(text, ID)
                    }
                }
                true
            }

            CodeVisionState.Ready(entries)
        }
    }

    override fun handleClick(editor: Editor, textRange: TextRange, entry: CodeVisionEntry) {
        val project = editor.project ?: return
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return

        val key = runReadAction {
            val at = psiFile.findElementAt(textRange.startOffset)
            // Prefer declarations (for hints over declarations)
            val decl: PsiElement? = PsiTreeUtil.getParentOfType(at, Method::class.java, Function::class.java, PhpClass::class.java)
            if (decl is Method) return@runReadAction KeyFactory.fromMethod(decl)
            if (decl is Function) return@runReadAction KeyFactory.fromFunction(decl)
            if (decl is PhpClass) return@runReadAction KeyFactory.fromClass(decl)
            // Otherwise handle call sites
            val callRef: PsiElement? = PsiTreeUtil.getParentOfType(at, MethodReference::class.java, FunctionReference::class.java)
            when (callRef) {
                is MethodReference -> (callRef.resolve() as? Method)?.let { KeyFactory.fromMethod(it) }
                is FunctionReference -> (callRef.resolve() as? Function)?.let { KeyFactory.fromFunction(it) }
                else -> null
            }
        } ?: return

        val svc = project.service<CommentsService>()
        val comment = svc.getComments(key).firstOrNull() ?: return

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val created = formatter.format(Instant.ofEpochMilli(comment.createdAt))

        val panel = JPanel(BorderLayout(10, 10))
        panel.border = JBUI.Borders.empty(12, 16)
        val header = JLabel("💬 Comment · $created")
        header.border = JBUI.Borders.empty(0, 0, 6, 0)
        panel.add(header, BorderLayout.NORTH)

        val area = JTextArea(comment.text)
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        area.margin = JBUI.insets(6, 8, 6, 8)
        val scroll = JScrollPane(area)
        scroll.border = JBUI.Borders.empty()
        panel.add(scroll, BorderLayout.CENTER)

        val actionsPanel = JPanel()
        actionsPanel.border = JBUI.Borders.empty(6, 0, 0, 0)
        var popupRef: JBPopup? = null
        val edit = HyperlinkLabel("Edit")
        edit.addHyperlinkListener {
            popupRef?.cancel()
            val updated = com.hiddenrefactoring.ui.AddCommentDialog.show(project, comment.text)
            if (updated != null && updated.isNotBlank()) {
                svc.updateComment(key, comment.id, updated)
                // refresh lenses
                project.service<CodeVisionHost>().invalidateProvider(CodeVisionHost.LensInvalidateSignal(editor, listOf(ID)))
            }
        }
        val del = HyperlinkLabel("Delete")
        del.addHyperlinkListener {
            popupRef?.cancel()
            svc.removeComment(key, comment.id)
            project.service<CodeVisionHost>().invalidateProvider(CodeVisionHost.LensInvalidateSignal(editor, listOf(ID)))
        }
        actionsPanel.add(edit)
        actionsPanel.add(JLabel("  ·  "))
        actionsPanel.add(del)
        panel.add(actionsPanel, BorderLayout.SOUTH)

        popupRef = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, area)
            .setRequestFocus(true)
            .setResizable(true)
            .setMinSize(Dimension(640, 360))
            .setCancelOnClickOutside(true)
            .setTitle("Hidden Refactoring")
            .createPopup()

        popupRef.showInBestPositionFor(editor)
    }

    override val name: String
        get() = "Hidden Refactoring: Comments"

    override val relativeOrderings: List<CodeVisionRelativeOrdering>
        get() = emptyList()

    override val defaultAnchor: CodeVisionAnchorKind
        get() = CodeVisionAnchorKind.Top

    override val id: String
        get() = ID

    private fun getHintText(svc: CommentsService, key: com.hiddenrefactoring.model.ElementKey): String? {
        val comments = svc.getComments(key)
        if (comments.isEmpty()) return null
        val raw = comments.first().text
        val firstLine = raw.substringBefore('\n')
        val first = elide(firstLine, 150)
        return "💬 $first"
    }

    private fun elide(s: String, max: Int): String {
        if (s.length <= max) return s
        if (max <= 1) return s.take(max)
        return s.take(max - 1) + "\u2026" // ellipsis
    }
}
