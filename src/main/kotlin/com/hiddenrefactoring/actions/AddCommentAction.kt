package com.hiddenrefactoring.actions

import com.hiddenrefactoring.keys.KeyFactory
import com.hiddenrefactoring.codevision.PhpCommentsCodeVisionProvider
import com.hiddenrefactoring.storage.CommentsService
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ui.Messages
import com.hiddenrefactoring.ui.AddCommentDialog

class AddCommentAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val key = KeyFactory.fromContext(project, editor, psiFile)
        if (key == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Hidden Refactoring")
                .createNotification("No suitable element found under caret.", NotificationType.WARNING)
                .notify(project)
            return
        }
        val existing = project.service<CommentsService>().getComments(key)
        val initial = existing.firstOrNull()?.text ?: ""
        val text = AddCommentDialog.show(project, initial) ?: return
        if (text.isBlank()) return

        val svc = project.service<CommentsService>()
        if (existing.isNotEmpty()) {
            // Enforce single comment per element: update first
            svc.updateComment(key, existing.first().id, text)
        } else {
            svc.addComment(key, text)
        }
        // Proactively refresh Code Vision hints for this editor/provider
        val host = project.service<CodeVisionHost>()
        val editorForSignal = editor // may be null; null would refresh all, avoid that
        if (editorForSignal != null) {
            host.invalidateProvider(CodeVisionHost.LensInvalidateSignal(editorForSignal, listOf(PhpCommentsCodeVisionProvider.ID)))
        } else {
            host.invalidateProvider(CodeVisionHost.LensInvalidateSignal(null, listOf(PhpCommentsCodeVisionProvider.ID)))
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hidden Refactoring")
            .createNotification("Comment saved.", NotificationType.INFORMATION)
            .notify(project)
    }
}
