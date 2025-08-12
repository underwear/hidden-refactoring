package com.hiddenrefactoring.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class AddCommentDialog(project: Project, initial: String = "") : DialogWrapper(project) {
    private val textArea = JBTextArea(initial, 8, 60)

    init {
        title = "Hidden Refactoring: Add Comment"
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    fun getText(): String = textArea.text

    companion object {
        fun show(project: Project, initial: String = ""): String? {
            val d = AddCommentDialog(project, initial)
            return if (d.showAndGet()) d.getText() else null
        }
    }
}
