package com.hiddenrefactoring.actions

import com.hiddenrefactoring.keys.KeyFactory
import com.hiddenrefactoring.storage.CommentsService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ViewCommentsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val key = KeyFactory.fromContext(project, editor, psiFile)
        if (key == null) {
            Messages.showInfoMessage(project, "No suitable element found under caret.", "Hidden Refactoring")
            return
        }
        val svc = project.service<CommentsService>()
        val comments = svc.getComments(key)
        if (comments.isEmpty()) {
            Messages.showInfoMessage(project, "No comments for this element.", "Hidden Refactoring")
            return
        }
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
        val text = comments.joinToString("\n\n") { c ->
            val ts = formatter.format(Instant.ofEpochMilli(c.createdAt))
            "[$ts] ${c.text}"
        }
        Messages.showMessageDialog(project, text, "Hidden Refactoring: Comments", Messages.getInformationIcon())
    }
}
