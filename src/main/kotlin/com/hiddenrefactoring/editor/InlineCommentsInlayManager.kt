package com.hiddenrefactoring.editor

import com.hiddenrefactoring.keys.KeyFactory
import com.hiddenrefactoring.storage.CommentsService
import com.hiddenrefactoring.model.Comment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.awt.event.MouseEvent
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JLabel

class InlineCommentsInlayManager : EditorFactoryListener {

    companion object {
        private val PROJECT_SUBSCRIBED_KEY: Key<Boolean> = Key.create("HiddenRefactoring.CommentsBusSubscribed")
        @Volatile private var INSTANCE: InlineCommentsInlayManager? = null

        fun refreshAllEditors(project: Project) {
            val factory = EditorFactory.getInstance()
            val fileDocMgr = FileDocumentManager.getInstance()
            for (e in factory.allEditors) {
                val ex = e as? EditorEx ?: continue
                if (ex.project != project) continue
                val vf = fileDocMgr.getFile(ex.document) ?: continue
                if (vf.fileType != PhpFileType.INSTANCE) continue
                val psiFile = PsiManager.getInstance(project).findFile(vf) ?: continue
                INSTANCE?.refreshInlay(project, ex, psiFile)
            }
        }
    }

    init { INSTANCE = this }

    private val DISPOSABLE_KEY: Key<Disposable> = Key.create("HiddenRefactoring.InlaysDisposable")

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        val project = editor.project ?: return
        val vf: VirtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (vf.fileType != PhpFileType.INSTANCE) return

        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return

        val disposable = Disposer.newDisposable("HiddenRefactoring-Inlays-${'$'}{vf.path}")
        installListeners(project, editor, psiFile, disposable)
        editor.putUserData(DISPOSABLE_KEY, disposable)

        // Ensure a single per-project comments subscription exists to refresh all editors reactively
        if (project.getUserData(PROJECT_SUBSCRIBED_KEY) != true) {
            project.putUserData(PROJECT_SUBSCRIBED_KEY, true)
            val conn = project.messageBus.connect(project)
            conn.subscribe(CommentsService.TOPIC, object : CommentsService.Listener {
                override fun changed(key: com.hiddenrefactoring.model.ElementKey) {
                    DumbService.getInstance(project).smartInvokeLater {
                        refreshAllEditors(project)
                    }
                }
            })
        }

        refreshInlay(project, editor, psiFile)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val d = editor.getUserData(DISPOSABLE_KEY)
        if (d != null) {
            Disposer.dispose(d)
            editor.putUserData(DISPOSABLE_KEY, null)
        }
    }

    private fun installListeners(project: Project, editor: EditorEx, psiFile: PsiFile, disposable: Disposable) {
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                refreshInlay(project, editor, psiFile)
            }
        }, disposable)

        // Click handler on our inlays opens popups/edit
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                val inlay = e.inlay ?: return
                val r = inlay.renderer
                if (r is CommentBadgeRenderer) {
                    r.onClick()
                } else if (r is CommentListRenderer) {
                    val changed = (r as CommentListRenderer).onMouseClick(inlay, e.mouseEvent)
                    if (changed) {
                        refreshInlay(project, editor, psiFile)
                    }
                } else if (r is EmojiCommentRenderer) {
                    (r as EmojiCommentRenderer).onClick(inlay)
                }
            }
        }, disposable)

        // Hover tooltip for emoji inlays
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val comp = editor.contentComponent
                val r = e.inlay?.renderer
                if (r is EmojiCommentRenderer) {
                    comp.toolTipText = r.tooltip()
                } else if (comp.toolTipText != null) {
                    comp.toolTipText = null
                }
            }
        }, disposable)

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // schedule on EDT; simple debounce via coalescing of events by the EDT queue
                ApplicationManager.getApplication().invokeLater({
                    if (!editor.isDisposed) refreshInlay(project, editor, psiFile)
                })
            }
        }, disposable)

        // Reactive refresh on comment changes anywhere in the project
        val connection = project.messageBus.connect(disposable)
        connection.subscribe(CommentsService.TOPIC, object : CommentsService.Listener {
            override fun changed(key: com.hiddenrefactoring.model.ElementKey) {
                // Ensure call-site resolution runs after indexing if needed
                DumbService.getInstance(project).smartInvokeLater({
                    if (!editor.isDisposed) refreshInlay(project, editor, psiFile)
                })
            }
        })
    }

    private fun refreshInlay(project: Project, editor: Editor, psiFile: PsiFile) {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater({
                if (!editor.isDisposed) refreshInlay(project, editor, psiFile)
            })
            return
        }
        // Clear all our previous inlays, then draw for every element in file that has comments
        clearOurInlays(editor)

        val svc = project.service<CommentsService>()
        val doc = editor.document

        fun handle(target: Any) {
            val nameIdentifier = when (target) {
                is Method -> target.nameIdentifier
                is Function -> target.nameIdentifier
                is PhpClass -> target.nameIdentifier
                else -> null
            } ?: return

            val key = when (target) {
                is Method -> KeyFactory.fromMethod(target)
                is Function -> KeyFactory.fromFunction(target)
                is PhpClass -> KeyFactory.fromClass(target)
                else -> null
            } ?: return

            if (!svc.hasComments(key)) return
            val comments = svc.getComments(key)
            if (comments.isEmpty()) return

            val offset = nameIdentifier.textRange.startOffset
            // Disabled: block comment list UI under declarations (use Code Vision popup instead)
            // val listRenderer = CommentListRenderer(project, key, comments)
            // editor.inlayModel.addBlockElement(offset, true, true, 0, listRenderer)
        }

        val elements = mutableListOf<Any>()
        ApplicationManager.getApplication().runReadAction {
            PsiTreeUtil.findChildrenOfType(psiFile, PhpClass::class.java).forEach { c -> elements.add(c) }
            PsiTreeUtil.findChildrenOfType(psiFile, Method::class.java).forEach { m -> elements.add(m) }
            PsiTreeUtil.findChildrenOfType(psiFile, Function::class.java).forEach { f -> elements.add(f) }
        }
        elements.forEach { handle(it) }

        // Add inline call-site emoji before function/method name when comment exists
        val dumb = DumbService.getInstance(project)
        if (!dumb.isDumb) {
            // Functions
            PsiTreeUtil.findChildrenOfType(psiFile, FunctionReference::class.java).forEach { ref ->
                val resolved = ApplicationManager.getApplication().runReadAction<Function?> { ref.resolve() as? Function } ?: return@forEach
                val key = KeyFactory.fromFunction(resolved) ?: return@forEach
                if (!svc.hasComments(key)) return@forEach
                val tr = ref.textRange
                val seq = doc.charsSequence
                val fnName = ref.name ?: return@forEach
                val nameRange = findNameRange(seq, tr, fnName)
                val nameStart = nameRange?.startOffset ?: tr.startOffset
                // Disabled: inline emoji comment marker at call sites (use Code Vision only)
                // editor.inlayModel.addInlineElement(nameStart, false, EmojiCommentRenderer(project, key))
            }
            // Methods
            PsiTreeUtil.findChildrenOfType(psiFile, MethodReference::class.java).forEach { ref ->
                val resolved = ApplicationManager.getApplication().runReadAction<Method?> { ref.resolve() as? Method } ?: return@forEach
                val key = KeyFactory.fromMethod(resolved) ?: return@forEach
                if (!svc.hasComments(key)) return@forEach
                val tr = ref.textRange
                val seq = doc.charsSequence
                val methodName = ref.name ?: return@forEach
                val nameRange = findMethodNameRange(seq, tr, methodName)
                val nameStart = nameRange?.startOffset ?: tr.startOffset
                // Disabled: inline emoji comment marker at call sites (use Code Vision only)
                // editor.inlayModel.addInlineElement(nameStart, false, EmojiCommentRenderer(project, key))
            }
        }
    }

    private fun findNameRange(seq: CharSequence, full: TextRange, name: String): TextRange? {
        var idx = seq.indexOf(name, full.startOffset)
        var fbStart: Int? = null
        var fbEnd: Int? = null
        while (idx >= 0 && idx < full.endOffset) {
            val after = idx + name.length
            var j = after
            while (j < full.endOffset && Character.isWhitespace(seq[j])) j++
            if (j < full.endOffset && seq[j] == '(') {
                return TextRange(idx, after)
            } else if (fbStart == null) {
                fbStart = idx
                fbEnd = after
            }
            idx = seq.indexOf(name, idx + 1)
        }
        return if (fbStart != null && fbEnd != null) TextRange(fbStart!!, fbEnd!!) else null
    }

    private fun findMethodNameRange(seq: CharSequence, full: TextRange, name: String): TextRange? {
        var idx = seq.indexOf(name, full.startOffset)
        var fbStart: Int? = null
        var fbEnd: Int? = null
        while (idx >= 0 && idx < full.endOffset) {
            val after = idx + name.length
            var k = idx - 1
            while (k >= full.startOffset && Character.isWhitespace(seq[k])) k--
            val precededByArrow = k >= full.startOffset - 1 && k - 1 >= full.startOffset && seq[k] == '>' && seq[k - 1] == '-'
            val precededByScope = k >= full.startOffset - 1 && k - 1 >= full.startOffset && seq[k] == ':' && seq[k - 1] == ':'
            if (precededByArrow || precededByScope) {
                return TextRange(idx, after)
            } else if (fbStart == null) {
                fbStart = idx
                fbEnd = after
            }
            idx = seq.indexOf(name, idx + 1)
        }
        return if (fbStart != null && fbEnd != null) TextRange(fbStart!!, fbEnd!!) else null
    }

    private class EmojiCommentRenderer(
        private val project: Project,
        private val key: com.hiddenrefactoring.model.ElementKey
    ) : com.intellij.openapi.editor.EditorCustomElementRenderer {
        private val text = "💬"
        private val paddingLeft = JBUI.scale(6)
        private val paddingRight = JBUI.scale(6)
        private val paddingV = JBUI.scale(1)
        private val arc = JBUI.scale(8).toFloat()
        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val font = getFont(inlay)
            val fm = inlay.editor.contentComponent.getFontMetrics(font)
            val pillWidth = paddingLeft + fm.stringWidth(text) + paddingRight
            val spaceRight = fm.charWidth(' ')
            return pillWidth + spaceRight
        }
        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val font = getFont(inlay)
            val fm = inlay.editor.contentComponent.getFontMetrics(font)
            return fm.height
        }
        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val g2 = g as Graphics2D
            val font = getFont(inlay)
            g2.font = font
            val fm = g2.fontMetrics
            val textBaseline = targetRegion.y + ((targetRegion.height - fm.height) / 2) + fm.ascent

            // Background pill similar to hint/code vision (without the trailing space area)
            val pillY = textBaseline - fm.ascent - paddingV
            val pillH = fm.height + paddingV * 2
            val pillX = targetRegion.x
            val pillW = paddingLeft + fm.stringWidth(text) + paddingRight
            val bg = JBColor(Color(110, 110, 110, 28), Color(110, 110, 110, 40))
            val border = JBColor(Color(110, 110, 110, 60), Color(110, 110, 110, 90))
            g2.color = bg
            val rr = RoundRectangle2D.Float(pillX.toFloat(), pillY.toFloat(), pillW.toFloat(), pillH.toFloat(), arc, arc)
            g2.fill(rr)
            g2.color = border
            g2.draw(rr)

            // Text
            g2.color = Color(0x66, 0x66, 0x66)
            g2.drawString(text, targetRegion.x + paddingLeft, textBaseline)
        }
        private fun getFont(inlay: Inlay<*>): Font {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val base = scheme.getFont(EditorFontType.PLAIN)
            return base
        }
        fun tooltip(): String? {
            val svc = project.service<CommentsService>()
            val c = svc.getComments(key).firstOrNull() ?: return null
            return c.text
        }
        fun onClick(inlay: Inlay<*>) {
            val svc = project.service<CommentsService>()
            val comment = svc.getComments(key).firstOrNull() ?: return
            // Build popup similar to Code Vision
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
            val created = formatter.format(java.time.Instant.ofEpochMilli(comment.createdAt))
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
                }
            }
            val del = HyperlinkLabel("Delete")
            del.addHyperlinkListener {
                popupRef?.cancel()
                svc.removeComment(key, comment.id)
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
            popupRef.showInBestPositionFor(inlay.editor)
        }
    }

    private fun clearOurInlays(editor: Editor) {
        val docLen = editor.document.textLength
        val inline = editor.inlayModel.getInlineElementsInRange(0, docLen)
        inline.filter { it.renderer is CommentBadgeRenderer || it.renderer is CommentListRenderer || it.renderer is EmojiCommentRenderer }
            .forEach { it.dispose() }
        val blocks = editor.inlayModel.getBlockElementsInRange(0, docLen)
        blocks.filter { it.renderer is CommentListRenderer }.forEach { it.dispose() }
    }

    private class CommentBadgeRenderer(
        private val project: Project,
        private val comments: List<String>
    ) : com.intellij.openapi.editor.EditorCustomElementRenderer {
        private val text: String get() = "💬 ${comments.size}"
        private val paddingH = 6
        private val paddingV = 2

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val font = getFont(inlay)
            val metrics = inlay.editor.contentComponent.getFontMetrics(font)
            return metrics.stringWidth(text) + paddingH * 2
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val font = getFont(inlay)
            val metrics = inlay.editor.contentComponent.getFontMetrics(font)
            return metrics.height + paddingV * 2
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val g2 = g as Graphics2D
            val font = getFont(inlay)
            g2.font = font
            val fm = g2.fontMetrics

            // background chip
            g2.color = Color(0xE7, 0xF3, 0xFF)
            val arc = 8f
            val rr = RoundRectangle2D.Float(
                targetRegion.x.toFloat(),
                targetRegion.y.toFloat(),
                targetRegion.width.toFloat(),
                targetRegion.height.toFloat(),
                arc, arc
            )
            g2.fill(rr)

            // text
            g2.color = Color(0x1B, 0x5E, 0x20)
            val baseline = targetRegion.y + ((targetRegion.height - fm.height) / 2) + fm.ascent
            g2.drawString(text, targetRegion.x + paddingH, baseline)
        }

        private fun getFont(inlay: Inlay<*>): Font {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val base = scheme.getFont(EditorFontType.PLAIN)
            return base.deriveFont(base.size2D * 0.95f)
        }

        fun onClick() {
            val body = comments.joinToString("\n\n") { it }
            Messages.showMessageDialog(project, body, "Hidden Refactoring: Comments (${comments.size})", Messages.getInformationIcon())
        }
    }

    private class CommentListRenderer(
        private val project: Project,
        private val key: com.hiddenrefactoring.model.ElementKey,
        private val comments: List<Comment>
    ) : com.intellij.openapi.editor.EditorCustomElementRenderer {
        private val paddingH = 8
        private val paddingV = 4
        private val rowGap = 2
        private val buttonGap = 12
        private val bg = Color(0xF7, 0xF9, 0xFC)
        private val fg = Color(0x22, 0x22, 0x22)
        private val btnColor = Color(0x33, 0x66, 0x99)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            // Not used for block inlays; return minimal width to satisfy interface
            val font = getFont(inlay)
            val metrics = inlay.editor.contentComponent.getFontMetrics(font)
            return metrics.stringWidth("mm")
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val font = getFont(inlay)
            val metrics = inlay.editor.contentComponent.getFontMetrics(font)
            val rowHeight = metrics.height + paddingV * 2
            return comments.size * rowHeight + (comments.size - 1).coerceAtLeast(0) * rowGap
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val g2 = g as Graphics2D
            val font = getFont(inlay)
            g2.font = font
            val fm = g2.fontMetrics
            val rowHeight = fm.height + paddingV * 2

            var y = targetRegion.y
            for ((index, c) in comments.withIndex()) {
                val rowRect = Rectangle(targetRegion.x, y, targetRegion.width, rowHeight)

                // background
                g2.color = bg
                val rr = RoundRectangle2D.Float(
                    rowRect.x.toFloat(), rowRect.y.toFloat(),
                    rowRect.width.toFloat(), rowRect.height.toFloat(),
                    8f, 8f
                )
                g2.fill(rr)

                // text
                g2.color = fg
                val baseY = rowRect.y + ((rowRect.height - fm.height) / 2) + fm.ascent
                val actionsText = "✎  🗑"
                val actionsWidth = fm.stringWidth(actionsText) + paddingH
                val maxTextWidth = rowRect.width - (paddingH * 2) - actionsWidth - buttonGap
                val shown = elide(c.text.replace("\n", " "), fm, maxTextWidth)
                g2.drawString(shown, rowRect.x + paddingH, baseY)

                // actions on the right
                g2.color = btnColor
                val actionsX = rowRect.x + rowRect.width - paddingH - fm.stringWidth(actionsText)
                g2.drawString(actionsText, actionsX, baseY)

                y += rowHeight + rowGap
            }
        }

        private fun getFont(inlay: Inlay<*>): Font {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val base = scheme.getFont(EditorFontType.PLAIN)
            return base.deriveFont(base.size2D * 0.95f)
        }

        private fun elide(s: String, fm: FontMetrics, maxWidth: Int): String {
            if (fm.stringWidth(s) <= maxWidth) return s
            var lo = 0
            var hi = s.length
            var best = ""
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val candidate = if (mid < s.length) s.substring(0, mid) + "…" else s
                val w = fm.stringWidth(candidate)
                if (w <= maxWidth) {
                    best = candidate
                    lo = mid + 1
                } else hi = mid - 1
            }
            return best
        }

        fun onMouseClick(inlay: Inlay<*>, me: MouseEvent): Boolean {
            val bounds = inlay.bounds ?: return false
            val font = getFont(inlay)
            val fm = inlay.editor.contentComponent.getFontMetrics(font)
            val rowHeight = fm.height + paddingV * 2
            val relX = me.x - bounds.x
            val relY = me.y - bounds.y
            val row = relY / (rowHeight + rowGap)
            if (row < 0 || row >= comments.size) return false

            // compute action hit areas for the row
            val actionsText = "✎  🗑"
            val actionsWidth = fm.stringWidth(actionsText)
            val actionsX = bounds.width - paddingH - actionsWidth
            val baseYInRow = relY - row * (rowHeight + rowGap)
            if (baseYInRow < 0 || baseYInRow > rowHeight) return false
            val clickXInActions = relX - actionsX
            if (clickXInActions < 0) return false

            // split actions area roughly: "✎  🗑"
            val editWidth = fm.stringWidth("✎  ")
            val deleteWidth = fm.stringWidth("🗑")
            val clickedEdit = clickXInActions <= editWidth
            val clickedDelete = !clickedEdit && clickXInActions <= (editWidth + deleteWidth + 2)

            val svc = project.service<CommentsService>()
            val c = comments[row]
            var changed = false
            if (clickedEdit) {
                val newText = Messages.showInputDialog(project, "Edit comment", "Hidden Refactoring", null, c.text, null)
                if (newText != null && newText != c.text) {
                    changed = svc.updateComment(key, c.id, newText)
                }
            } else if (clickedDelete) {
                val ok = Messages.showYesNoDialog(project, "Delete this comment?", "Hidden Refactoring", null)
                if (ok == Messages.YES) {
                    changed = svc.removeComment(key, c.id)
                }
            }
            return changed
        }
    }
}
