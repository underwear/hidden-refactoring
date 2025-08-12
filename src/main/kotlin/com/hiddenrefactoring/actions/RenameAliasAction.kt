package com.hiddenrefactoring.actions

import com.hiddenrefactoring.keys.AliasKeyFactory
import com.hiddenrefactoring.storage.AliasesService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class RenameAliasAction : AnAction("Hidden Refactoring: Set Alias") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val key = AliasKeyFactory.fromContext(project, editor, psiFile)
        if (key == null) {
            notify(project, "Place caret on a variable/parameter/method/function/class name to set an alias.")
            return
        }
        val svc = project.service<AliasesService>()
        val existing = svc.getAlias(key) ?: ""
        val title = if (existing.isEmpty()) "Set Alias" else "Edit Alias"
        val input = Messages.showInputDialog(project, "Alias (empty to remove)", title, null, existing, null)
        if (input == null) return // cancel
        if (input.isBlank()) {
            svc.removeAlias(key)
            notify(project, "Alias removed")
        } else {
            svc.setAlias(key, input.trim())
            notify(project, "Alias set: ${'$'}{input.trim()}")
        }
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Hidden Refactoring")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
