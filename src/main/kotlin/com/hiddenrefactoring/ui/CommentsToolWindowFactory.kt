package com.hiddenrefactoring.ui

import com.hiddenrefactoring.model.ElementKey
import com.hiddenrefactoring.model.ElementType
import com.hiddenrefactoring.storage.CommentsService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.Messages
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.SearchTextField
import com.intellij.ui.content.ContentFactory
// import removed: KeyFactory no longer used
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.intellij.util.ui.JBUI
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.JButton
import com.intellij.ui.DocumentAdapter
import javax.swing.event.DocumentEvent
import com.hiddenrefactoring.ui.AddCommentDialog
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.icons.AllIcons

class CommentsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CommentsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class CommentsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val keyList = JBList<ElementKey>()
    private val search = SearchTextField()
    private val keyLabel = JBLabel("Select an item")
    private val timestampLabel = JBLabel("")
    private val detailsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        margin = JBUI.insets(6, 8, 6, 8)
    }
    private val editBtn = JButton("Edit")
    private val deleteBtn = JButton("Delete")
    private var currentKey: ElementKey? = null

    init {
        val splitter = JBSplitter(false, 0.3f)
        splitter.setHonorComponentsMinimumSize(false)
        keyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        keyList.addListSelectionListener {
            val selected = keyList.selectedValue ?: return@addListSelectionListener
            updateDetails(selected)
        }
        keyList.cellRenderer = object : ColoredListCellRenderer<ElementKey>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out ElementKey>,
                value: ElementKey?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                icon = when (value.type) {
                    ElementType.CLASS -> AllIcons.Nodes.Class
                    ElementType.METHOD -> AllIcons.Nodes.Method
                    ElementType.FUNCTION -> AllIcons.Nodes.Function
                    ElementType.FILE -> AllIcons.FileTypes.Text
                    else -> null
                }
                append(displayTextFor(value), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                toolTipText = fullTextFor(value)
            }
        }

        // Left: search + list
        val left = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(keyList), BorderLayout.CENTER)
            border = JBUI.Borders.empty(6)
        }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilterAndRefresh()
            }
        })

        // Right: header (key + ts) + details + actions
        val header = JPanel(BorderLayout()).apply {
            add(keyLabel, BorderLayout.WEST)
            add(timestampLabel, BorderLayout.EAST)
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        val rightCenter = JBScrollPane(detailsArea).apply {
            border = JBUI.Borders.empty()
            minimumSize = Dimension(200, 200)
        }
        val actionsPanel = JPanel().apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
            add(editBtn)
            add(deleteBtn)
        }
        val right = JPanel(BorderLayout(10, 10)).apply {
            border = JBUI.Borders.empty(12, 16)
            add(header, BorderLayout.NORTH)
            add(rightCenter, BorderLayout.CENTER)
            add(actionsPanel, BorderLayout.SOUTH)
        }

        splitter.firstComponent = left
        splitter.secondComponent = right
        setContent(splitter)
        setToolbar(createToolbar().component)
        wireActions()
        subscribeToChanges()
        refresh()
    }

    private fun wireActions() {
        editBtn.addActionListener {
            val key = currentKey ?: return@addActionListener
            val svc = project.service<CommentsService>()
            val existing = svc.getComments(key).firstOrNull()
            val initial = existing?.text ?: ""
            val newText = AddCommentDialog.show(project, initial) ?: return@addActionListener
            if (newText.isBlank()) return@addActionListener
            if (existing != null) svc.updateComment(key, existing.id, newText) else svc.addComment(key, newText)
            notifyInfo("Comment saved.")
            invalidateLenses()
            updateAfterChange(key)
        }
        deleteBtn.addActionListener {
            val key = currentKey ?: return@addActionListener
            val svc = project.service<CommentsService>()
            val existing = svc.getComments(key).firstOrNull() ?: return@addActionListener
            val confirm = Messages.showYesNoDialog(this@CommentsPanel, "Delete comment for selected element?", "Confirm Delete", null)
            if (confirm != Messages.YES) return@addActionListener
            svc.removeComment(key, existing.id)
            notifyInfo("Comment deleted.")
            invalidateLenses()
            updateAfterChange(key)
        }
    }

    private fun refresh() {
        val svc = project.service<CommentsService>()
        val keys = svc.getAllKeys().sortedBy { displayTextFor(it).lowercase() }
        keyList.setListData(keys.toTypedArray())
        if (keys.isEmpty()) {
            currentKey = null
            keyLabel.text = "No items"
            timestampLabel.text = ""
            detailsArea.text = ""
        }
        updateButtonsState()
    }

    private fun applyFilterAndRefresh() {
        val svc = project.service<CommentsService>()
        val filter = search.text.trim()
        val all = svc.getAllKeys()
        val filtered = if (filter.isBlank()) all else all.filter { k ->
            val c = svc.getComments(k).firstOrNull()?.text ?: ""
            displayTextFor(k).contains(filter, ignoreCase = true) ||
            k.key.contains(filter, ignoreCase = true) ||
            c.contains(filter, ignoreCase = true)
        }
        val keys = filtered.sortedBy { displayTextFor(it).lowercase() }
        keyList.setListData(keys.toTypedArray())
        // Keep selection if possible
        currentKey?.let { if (keys.contains(it)) selectKey(it) }
    }

    private fun updateDetails(key: ElementKey) {
        currentKey = key
        val svc = project.service<CommentsService>()
        val comment = svc.getComments(key).firstOrNull()
        keyLabel.text = shortDisplayTextFor(key)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        timestampLabel.text = comment?.let { formatter.format(Instant.ofEpochMilli(it.createdAt)) } ?: ""
        detailsArea.text = comment?.text ?: ""
        updateButtonsState()
    }

    private fun displayTextFor(key: ElementKey): String = when (key.type) {
        ElementType.METHOD -> {
            val raw = key.key
            val cls = raw.substringBefore("::").substringAfterLast("\\")
            val method = raw.substringAfter("::").substringBefore("(")
            "$cls::$method"
        }
        ElementType.FUNCTION -> key.key.substringBefore("(").substringAfterLast("\\")
        ElementType.CLASS -> key.key.substringAfterLast("\\")
        ElementType.FILE -> key.key.substringAfterLast('/')
        else -> key.key
    }

    private fun fullTextFor(key: ElementKey): String = when (key.type) {
        ElementType.FILE -> key.key
        else -> key.key.substringBefore("(")
    }

    private fun shortDisplayTextFor(key: ElementKey, maxLen: Int = 60): String {
        val s = displayTextFor(key)
        return if (s.length <= maxLen) s else s.substring(0, maxLen - 1) + "\u2026"
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh") {
            override fun actionPerformed(e: AnActionEvent) {
                val sel = keyList.selectedValue
                refresh()
                sel?.let { selectKey(it) }
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("HiddenRefactoringCommentsToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar
    }

    private fun updateAfterChange(key: ElementKey) {
        refresh()
        selectKey(key)
        updateDetails(key)
    }

    private fun selectKey(key: ElementKey) {
        val model = keyList.model
        for (i in 0 until model.size) {
            if (model.getElementAt(i) == key) {
                keyList.selectedIndex = i
                break
            }
        }
    }

    private fun updateButtonsState() {
        val hasSelection = currentKey != null
        val svc = project.service<CommentsService>()
        val hasComment = currentKey?.let { svc.getComments(it).isNotEmpty() } ?: false
        editBtn.isEnabled = hasSelection
        deleteBtn.isEnabled = hasSelection && hasComment
    }

    private fun notifyInfo(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hidden Refactoring")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun invalidateLenses() {
        project.service<CodeVisionHost>().invalidateProvider(CodeVisionHost.LensInvalidateSignal(null, listOf(com.hiddenrefactoring.codevision.PhpCommentsCodeVisionProvider.ID)))
    }

    private fun subscribeToChanges() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(CommentsService.TOPIC, object : CommentsService.Listener {
            override fun changed(key: ElementKey) {
                ApplicationManager.getApplication().invokeLater {
                    // Reapply filter and refresh UI; keep focus and selection on changed key
                    val prev = currentKey
                    applyFilterAndRefresh()
                    val toSelect = prev ?: key
                    selectKey(toSelect)
                    updateDetails(toSelect)
                }
            }
        })
    }

    override fun dispose() {
        // Message bus connection is disposed automatically because we connected with `this` as parent disposable
    }
}
