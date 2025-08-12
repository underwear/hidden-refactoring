package com.hiddenrefactoring.editor

import com.hiddenrefactoring.keys.KeyFactory
import com.hiddenrefactoring.storage.CommentsService
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

class CommentLinePainter : EditorLinePainter() {
    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): MutableCollection<LineExtensionInfo>? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (lineNumber < 0 || lineNumber >= document.lineCount) return null
        val psiFile: PsiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val lineStart = document.getLineStartOffset(lineNumber)
        val elementAt = psiFile.findElementAt(lineStart) ?: return null

        // resolve nearest PHP element
        val candidate: PsiElement? =
            PsiTreeUtil.getParentOfType(elementAt, Method::class.java, false)
                ?: PsiTreeUtil.getParentOfType(elementAt, Function::class.java, false)
                ?: PsiTreeUtil.getParentOfType(elementAt, PhpClass::class.java, false)
        candidate ?: return null

        val nameOffset = when (candidate) {
            is Method -> candidate.nameIdentifier?.textOffset
            is Function -> candidate.nameIdentifier?.textOffset
            is PhpClass -> candidate.nameIdentifier?.textOffset
            else -> null
        } ?: return null

        // show only on the declaration line (where the name sits)
        val declLine = document.getLineNumber(nameOffset)
        if (declLine != lineNumber) return null

        val key = when (candidate) {
            is Method -> KeyFactory.fromMethod(candidate)
            is Function -> KeyFactory.fromFunction(candidate)
            is PhpClass -> KeyFactory.fromClass(candidate)
            else -> null
        } ?: return null

        val svc = project.service<CommentsService>()
        if (!svc.hasComments(key)) return null
        val comments = svc.getComments(key)
        if (comments.isEmpty()) return null

        val scheme = EditorColorsManager.getInstance().globalScheme
        val attrs = scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)
        val text = "  💬 ${comments.size}"
        return mutableListOf(LineExtensionInfo(text, attrs))
    }
}
