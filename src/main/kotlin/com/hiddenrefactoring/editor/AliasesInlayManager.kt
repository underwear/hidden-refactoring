package com.hiddenrefactoring.editor

import com.hiddenrefactoring.keys.AliasKeyFactory
import com.hiddenrefactoring.storage.AliasesService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.messages.MessageBusConnection
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpReference
import com.jetbrains.php.lang.psi.elements.Variable
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseEvent

class AliasesInlayManager : EditorFactoryListener {
    private val DISPOSABLE_KEY: Key<Disposable> = Key.create("HiddenRefactoring.AliasesInlaysDisposable")
    private val HIGHLIGHTERS_KEY: Key<MutableList<RangeHighlighter>> = Key.create("HiddenRefactoring.AliasesHighlighters")

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        val project = editor.project ?: return
        val vf: VirtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (vf.fileType != PhpFileType.INSTANCE) return

        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return

        val disposable = Disposer.newDisposable("HiddenRefactoring-Aliases-${'$'}{vf.path}")
        installListeners(project, editor, psiFile, disposable)
        editor.putUserData(DISPOSABLE_KEY, disposable)

        val dumb = DumbService.getInstance(project)
        if (dumb.isDumb) {
            dumb.runWhenSmart {
                if (!editor.isDisposed) {
                    refreshInlays(project, editor, psiFile)
                }
            }
        } else {
            refreshInlays(project, editor, psiFile)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val d = editor.getUserData(DISPOSABLE_KEY)
        if (d != null) {
            Disposer.dispose(d)
            editor.putUserData(DISPOSABLE_KEY, null)
        }
    }

    // Resolve a PHP-specific TextAttributesKey by trying known PHP highlighter classes/fields.
    // Uses reflection so we don't need a compile-time dependency on these internals.
    private fun phpTextAttributesKey(vararg fieldNames: String): TextAttributesKey? {
        val classes = arrayOf(
            "com.jetbrains.php.lang.highlighter.PhpHighlightingData",
            "com.jetbrains.php.lang.highlighter.PhpHighlightingColors",
            "com.jetbrains.php.lang.highlighter.PhpHighlighterColors"
        )
        for (cn in classes) {
            try {
                val cls = Class.forName(cn)
                for (fn in fieldNames) {
                    try {
                        val f = cls.getField(fn)
                        val v = f.get(null)
                        if (v is TextAttributesKey) return v
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun installListeners(project: Project, editor: EditorEx, psiFile: PsiFile, disposable: Disposable) {
        // Click handler on alias inlays opens edit dialog
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                val inlay = e.inlay ?: return
                val r = inlay.renderer
                if (r is AliasRenderer) {
                    r.onClick()
                }
            }
        }, disposable)

        // Refresh on caret/document changes
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                refreshInlays(project, editor, psiFile)
            }
        }, disposable)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                ApplicationManager.getApplication().invokeLater({
                    if (!editor.isDisposed) refreshInlays(project, editor, psiFile)
                })
            }
        }, disposable)

        // Refresh on alias changes via message bus
        val connection: MessageBusConnection = project.messageBus.connect(disposable)
        connection.subscribe(AliasesService.TOPIC, object : AliasesService.Listener {
            override fun changed(key: com.hiddenrefactoring.model.AliasKey) {
                ApplicationManager.getApplication().invokeLater({
                    if (!editor.isDisposed) refreshInlays(project, editor, psiFile)
                })
            }
        })
    }

    private fun refreshInlays(project: Project, editor: Editor, psiFile: PsiFile) {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater({
                if (!editor.isDisposed) refreshInlays(project, editor, psiFile)
            })
            return
        }
        clearAliasInlays(editor)
        clearAliasStyling(editor)
        val svc = project.service<AliasesService>()

        val dumbService = DumbService.getInstance(project)
        app.runReadAction {
            // Per-refresh set of inserted alias inlays to prevent duplicates within the same cycle
            val addedAliasEntries = mutableSetOf<Pair<Int, com.hiddenrefactoring.model.AliasKey>>()
            // Internal implementation used by wrappers below
            fun internalAddIfAliasExists(
                offset: Int,
                tokenKey: TextAttributesKey,
                originalRange: TextRange?,
                buildKey: () -> com.hiddenrefactoring.model.AliasKey?,
                buildFallbackKey: (() -> com.hiddenrefactoring.model.AliasKey?)? = null,
                prefixDollar: Boolean = true
            ) {
                val primary = buildKey() ?: return
                var keyUsed = primary
                var alias = svc.getAlias(primary)
                if (alias == null && buildFallbackKey != null) {
                    val fallback = buildFallbackKey()
                    if (fallback != null) {
                        val alt = svc.getAlias(fallback)
                        if (alt != null) {
                            alias = alt
                            keyUsed = fallback
                        }
                    }
                }
                if (alias == null) return
                // Per-refresh de-duplication by (offset, key)
                run {
                    val entry = Pair(offset, keyUsed)
                    if (!addedAliasEntries.add(entry)) return
                }
                // Deduplicate: if an alias inlay already exists at this offset, skip adding another
                run {
                    val existing = editor.inlayModel.getInlineElementsInRange(offset, offset + 1)
                        .any { it.renderer is AliasRenderer }
                    if (existing) return
                }
                // Resolve alias color: try actual token at originalRange, fallback to scheme tokenKey
                val aliasColor: Color = run {
                    if (originalRange != null) {
                        val ex = editor as EditorEx
                        val it = ex.highlighter.createIterator(originalRange.startOffset)
                        // Ensure iterator covers the start offset
                        while (!it.atEnd() && it.end <= originalRange.startOffset) it.advance()
                        // Scan tokens within name range to get the actual identifier color
                        while (!it.atEnd() && it.start < originalRange.endOffset) {
                            val c = it.textAttributes?.foregroundColor
                            if (c != null) return@run c
                            it.advance()
                        }
                    }
                    // Prefer the specific tokenKey color first
                    val tk = editor.colorsScheme.getAttributes(tokenKey)?.foregroundColor
                    if (tk != null) tk else {
                        // Then try generic IDENTIFIER color as a better fallback for names
                        val idAttrs = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.IDENTIFIER)
                        idAttrs?.foregroundColor ?: run {
                            val globalAttrs = EditorColorsManager.getInstance().globalScheme.getAttributes(tokenKey)
                            globalAttrs?.foregroundColor
                                ?: editor.colorsScheme.defaultForeground
                                ?: Color(0x77, 0x77, 0x77)
                        }
                    }
                }
                // Alias inlay
                editor.inlayModel.addInlineElement(offset, true, AliasRenderer(project, keyUsed, alias, aliasColor, prefixDollar))
                // Original styling (gray + strikeout)
                if (originalRange != null) {
                    val mm = (editor as EditorEx).markupModel
                    val attrs = TextAttributes().apply {
                        foregroundColor = JBColor.GRAY
                        effectColor = JBColor.GRAY
                        effectType = EffectType.STRIKEOUT
                    }
                    val rh = mm.addRangeHighlighter(
                        originalRange.startOffset,
                        originalRange.endOffset,
                        HighlighterLayer.ERROR + 1, // render above syntax colors
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    val bucket = editor.getUserData(HIGHLIGHTERS_KEY) ?: mutableListOf<RangeHighlighter>().also {
                        editor.putUserData(HIGHLIGHTERS_KEY, it)
                    }
                    bucket.add(rh)
                }
            }

            // Wrapper used by existing call sites (trailing lambda is buildKey)
            fun addIfAliasExists(
                offset: Int,
                tokenKey: TextAttributesKey,
                originalRange: TextRange?,
                buildKey: () -> com.hiddenrefactoring.model.AliasKey?
            ) {
                internalAddIfAliasExists(offset, tokenKey, originalRange, buildKey, null, true)
            }

            // Wrapper that supports a fallback key supplier
            fun addIfAliasExistsWithFallback(
                offset: Int,
                tokenKey: TextAttributesKey,
                originalRange: TextRange?,
                buildFallbackKey: () -> com.hiddenrefactoring.model.AliasKey?,
                buildKey: () -> com.hiddenrefactoring.model.AliasKey?
            ) {
                internalAddIfAliasExists(offset, tokenKey, originalRange, buildKey, buildFallbackKey, true)
            }

            // Wrapper for named-argument label aliases (render without $ for parameters)
            fun addIfAliasExistsNoDollar(
                offset: Int,
                tokenKey: TextAttributesKey,
                originalRange: TextRange?,
                buildKey: () -> com.hiddenrefactoring.model.AliasKey?
            ) {
                internalAddIfAliasExists(offset, tokenKey, originalRange, buildKey, null, false)
            }

            // Variables
            PsiTreeUtil.findChildrenOfType(psiFile, Variable::class.java).forEach { variable ->
                val range = variable.textRange
                val end = range.endOffset
                // If this variable is the value part of a named argument (label: value),
                // suppress the variable alias to avoid duplication next to the label alias.
                run {
                    val doc = editor.document
                    val seq = doc.charsSequence
                    val start = range.startOffset
                    var i = start - 1
                    while (i >= 0 && Character.isWhitespace(seq[i])) i--
                    if (i >= 0 && seq[i] == ':') {
                        var k = i - 1
                        while (k >= 0 && Character.isWhitespace(seq[k])) k--
                        // Walk back over identifier characters to find a potential label
                        var labelEnd = k + 1
                        while (k >= 0) {
                            val ch = seq[k]
                            if (ch == '_' || Character.isLetterOrDigit(ch.code)) { k-- } else break
                        }
                        val labelStart = k + 1
                        if (labelStart < labelEnd) {
                            // Ensure we're inside a call expression (function or method)
                            val call = PsiTreeUtil.getParentOfType(variable, FunctionReference::class.java, true)
                                ?: PsiTreeUtil.getParentOfType(variable, MethodReference::class.java, true)
                            if (call != null) {
                                val ctr = call.textRange
                                if (ctr.startOffset <= labelStart && end <= ctr.endOffset) {
                                    // Likely a named argument value; skip variable alias
                                    return@forEach
                                }
                            }
                        }
                    }
                }
                addIfAliasExistsWithFallback(
                    end,
                    DefaultLanguageHighlighterColors.LOCAL_VARIABLE,
                    range,
                    {
                        // Fallback: if this variable refers to a parameter, use the parameter key
                        val varName = variable.name?.removePrefix("$") ?: return@addIfAliasExistsWithFallback null
                        val method = PsiTreeUtil.getParentOfType(variable, Method::class.java, true)
                        if (method != null) {
                            val param = PsiTreeUtil.findChildrenOfType(method, Parameter::class.java)
                                .firstOrNull { it.name == varName }
                            if (param != null) return@addIfAliasExistsWithFallback AliasKeyFactory.fromParameter(param)
                        }
                        val function = PsiTreeUtil.getParentOfType(variable, Function::class.java, true)
                        if (function != null) {
                            val param = PsiTreeUtil.findChildrenOfType(function, Parameter::class.java)
                                .firstOrNull { it.name == varName }
                            if (param != null) return@addIfAliasExistsWithFallback AliasKeyFactory.fromParameter(param)
                        }
                        null
                    },
                    { AliasKeyFactory.fromVariable(variable) }
                )
            }
            // Parameters
            PsiTreeUtil.findChildrenOfType(psiFile, Parameter::class.java).forEach { parameter ->
                val nameId = parameter.nameIdentifier
                val nameRange = nameId?.textRange ?: parameter.textRange
                val end = nameRange.endOffset
                addIfAliasExists(end, DefaultLanguageHighlighterColors.PARAMETER, nameRange) {
                    AliasKeyFactory.fromParameter(parameter)
                }
            }
            // Methods (declarations)
            PsiTreeUtil.findChildrenOfType(psiFile, Method::class.java).forEach { m ->
                val nameId = m.nameIdentifier ?: return@forEach
                val nameRange = nameId.textRange
                val end = nameRange.endOffset
                val methodKey = phpTextAttributesKey("METHOD_NAME", "METHOD", "PHP_METHOD", "PHP_METHOD_NAME")
                    ?: DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
                addIfAliasExists(end, methodKey, nameRange) {
                    AliasKeyFactory.fromMethod(m)
                }
            }
            // Functions (declarations)
            PsiTreeUtil.findChildrenOfType(psiFile, Function::class.java).forEach { f ->
                val nameId = f.nameIdentifier ?: return@forEach
                val nameRange = nameId.textRange
                val end = nameRange.endOffset
                val functionKey = phpTextAttributesKey("FUNCTION_NAME", "FUNCTION", "PHP_FUNCTION", "PHP_FUNCTION_NAME")
                    ?: DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
                addIfAliasExists(end, functionKey, nameRange) {
                    AliasKeyFactory.fromFunction(f)
                }
            }
            // Classes (declarations) – keep original intact, only color alias like class name
            PsiTreeUtil.findChildrenOfType(psiFile, PhpClass::class.java).forEach { c ->
                val nameId = c.nameIdentifier ?: return@forEach
                val end = nameId.textRange.endOffset
                addIfAliasExists(end, DefaultLanguageHighlighterColors.CLASS_NAME, null) {
                    AliasKeyFactory.fromClass(c)
                }
            }

            // Skip index-dependent resolution in dumb mode
            if (!dumbService.isDumb) {
                // Function calls (usages) – place alias right after function name (before parentheses)
                PsiTreeUtil.findChildrenOfType(psiFile, FunctionReference::class.java).forEach { ref ->
                    val doc = editor.document
                    val tr = ref.textRange
                    val seq = doc.charsSequence
                    val fnName = ref.name
                    var nameStart: Int? = null
                    var nameEnd: Int? = null
                    if (fnName != null) {
                        val start = tr.startOffset
                        val endLimit = tr.endOffset
                        var idx = seq.indexOf(fnName, start)
                        var fallbackStart: Int? = null
                        var fallbackEnd: Int? = null
                        while (idx >= 0 && idx < endLimit) {
                            val after = idx + fnName.length
                            var j = after
                            while (j < endLimit && Character.isWhitespace(seq[j])) j++
                            if (j < endLimit && seq[j] == '(') {
                                nameStart = idx
                                nameEnd = after
                                break
                            } else if (fallbackStart == null) {
                                fallbackStart = idx
                                fallbackEnd = after
                            }
                            idx = seq.indexOf(fnName, idx + 1)
                        }
                        if (nameStart == null && fallbackStart != null) {
                            nameStart = fallbackStart
                            nameEnd = fallbackEnd
                        }
                    }
                    val endOffset = nameEnd ?: tr.endOffset
                    val originalRange = if (nameStart != null && nameEnd != null) TextRange(nameStart!!, nameEnd!!) else null
                    val resolved = ref.resolve() as? Function ?: return@forEach
                    val callKey = phpTextAttributesKey("FUNCTION", "PHP_FUNCTION", "FUNCTION_CALL")
                        ?: DefaultLanguageHighlighterColors.FUNCTION_CALL
                    addIfAliasExists(endOffset, callKey, originalRange) {
                        AliasKeyFactory.fromFunction(resolved)
                    }
                    // Named arguments (PHP 8): place alias after argument label if parameter has an alias
                    run {
                        val doc = editor.document
                        val seq = doc.charsSequence
                        val startSearch = (nameEnd ?: tr.startOffset)
                        val open = seq.indexOf('(', startSearch)
                        if (open >= 0 && open < tr.endOffset) {
                            var depth = 1
                            var j = open + 1
                            var close = tr.endOffset
                            while (j < tr.endOffset) {
                                val ch = seq[j]
                                if (ch == '(') depth++
                                else if (ch == ')') {
                                    depth--
                                    if (depth == 0) { close = j; break }
                                }
                                j++
                            }
                            // map of parameters by name for quick lookup
                            val params = PsiTreeUtil.findChildrenOfType(resolved, Parameter::class.java)
                            var i = open + 1
                            var nested = 0
                            while (i < close) {
                                val ch = seq[i]
                                // Track nested parentheses to only handle top-level arguments
                                if (ch == '(') { nested++; i++; continue }
                                if (ch == ')') { if (nested > 0) nested--; i++; continue }
                                if (nested > 0) { i++; continue }
                                // skip whitespace and commas
                                while (i < close && (Character.isWhitespace(seq[i]) || seq[i] == ',')) i++
                                if (i >= close) break
                                val c = seq[i]
                                if (c == '_' || Character.isLetter(c.code)) {
                                    val nameStart = i
                                    var k = i + 1
                                    while (k < close) {
                                        val cc = seq[k]
                                        if (cc == '_' || Character.isLetterOrDigit(cc.code)) k++ else break
                                    }
                                    val nameEnd = k
                                    // skip whitespace
                                    var m = k
                                    while (m < close && Character.isWhitespace(seq[m])) m++
                                    if (m < close && seq[m] == ':') {
                                        val label = seq.subSequence(nameStart, nameEnd).toString()
                                        val param = params.firstOrNull { it.name == label }
                                        if (param != null) {
                                            val labelRange = TextRange(nameStart, nameEnd)
                                            addIfAliasExistsNoDollar(labelRange.endOffset, DefaultLanguageHighlighterColors.PARAMETER, labelRange) {
                                                AliasKeyFactory.fromParameter(param)
                                            }
                                        }
                                        i = m + 1
                                        continue
                                    }
                                    i = k
                                } else {
                                    i++
                                }
                            }
                        }
                    }
                }

                // Method calls (usages) – place alias right after method name (before parentheses)
                PsiTreeUtil.findChildrenOfType(psiFile, MethodReference::class.java).forEach { ref ->
                    // Try to locate method name within the reference text and place alias right after it
                    val doc = editor.document
                    val tr = ref.textRange
                    val seq = doc.charsSequence
                    val methodName = ref.name
                    var nameStartInDoc: Int? = null
                    var nameEndInDoc: Int? = null
                    if (methodName != null) {
                        val start = tr.startOffset
                        val endLimit = tr.endOffset
                        var idx = seq.indexOf(methodName, start)
                        var fallbackStart: Int? = null
                        var fallbackEnd: Int? = null
                        while (idx >= 0 && idx < endLimit) {
                            val after = idx + methodName.length
                            // Prefer occurrence whose previous non-whitespace forms -> or ::
                            var k = idx - 1
                            while (k >= start && Character.isWhitespace(seq[k])) k--
                            val precededByArrow = k >= start - 1 && k - 1 >= start && seq[k] == '>' && seq[k - 1] == '-'
                            val precededByScope = k >= start - 1 && k - 1 >= start && seq[k] == ':' && seq[k - 1] == ':'
                            if (precededByArrow || precededByScope) {
                                nameStartInDoc = idx
                                nameEndInDoc = after
                                break
                            } else if (fallbackStart == null) {
                                fallbackStart = idx
                                fallbackEnd = after
                            }
                            idx = seq.indexOf(methodName, idx + 1)
                        }
                        if (nameStartInDoc == null && fallbackStart != null) {
                            nameStartInDoc = fallbackStart
                            nameEndInDoc = fallbackEnd
                        }
                    }
                    val end = nameEndInDoc ?: ref.textRange.endOffset
                    val callKey = phpTextAttributesKey("METHOD", "PHP_METHOD", "METHOD_CALL")
                        ?: DefaultLanguageHighlighterColors.FUNCTION_CALL
                    val resolved = ref.resolve() as? Method
                    if (resolved != null) {
                        val originalRange = if (nameStartInDoc != null && nameEndInDoc != null) TextRange(nameStartInDoc!!, nameEndInDoc!!) else null
                        addIfAliasExists(end, callKey, originalRange) {
                            AliasKeyFactory.fromMethod(resolved)
                        }
                    } else {
                        // Fallback: infer class from surrounding context when resolution fails
                        val methodNameFallback = ref.name ?: return@forEach
                        val qualifierClass = ((ref.classReference as? PhpReference)?.resolve() as? PhpClass)
                        val targetClass = qualifierClass ?: PsiTreeUtil.getParentOfType(ref, PhpClass::class.java, true)
                        if (targetClass != null) {
                            val originalRange = if (nameStartInDoc != null && nameEndInDoc != null) TextRange(nameStartInDoc!!, nameEndInDoc!!) else null
                            addIfAliasExists(end, callKey, originalRange) {
                                AliasKeyFactory.fromClassAndMethodName(targetClass, methodNameFallback)
                            }
                        }
                    }
                    // Named arguments (PHP 8): place alias after argument label if parameter has an alias
                    run {
                        val doc = editor.document
                        val seq = doc.charsSequence
                        val startSearch = (nameEndInDoc ?: tr.startOffset)
                        val open = seq.indexOf('(', startSearch)
                        if (open >= 0 && open < tr.endOffset) {
                            var depth = 1
                            var j = open + 1
                            var close = tr.endOffset
                            while (j < tr.endOffset) {
                                val ch = seq[j]
                                if (ch == '(') depth++
                                else if (ch == ')') {
                                    depth--
                                    if (depth == 0) { close = j; break }
                                }
                                j++
                            }
                            // resolve method to get parameters
                            val target = (ref.resolve() as? Method)
                            if (target != null) {
                                val params = PsiTreeUtil.findChildrenOfType(target, Parameter::class.java)
                                var i = open + 1
                                var nested = 0
                                while (i < close) {
                                    val ch = seq[i]
                                    // Track nested parentheses to only handle top-level arguments
                                    if (ch == '(') { nested++; i++; continue }
                                    if (ch == ')') { if (nested > 0) nested--; i++; continue }
                                    if (nested > 0) { i++; continue }
                                    while (i < close && (Character.isWhitespace(seq[i]) || seq[i] == ',')) i++
                                    if (i >= close) break
                                    val c = seq[i]
                                    if (c == '_' || Character.isLetter(c.code)) {
                                        val nameStart = i
                                        var k = i + 1
                                        while (k < close) {
                                            val cc = seq[k]
                                            if (cc == '_' || Character.isLetterOrDigit(cc.code)) k++ else break
                                        }
                                        val nameEnd = k
                                        var m = k
                                        while (m < close && Character.isWhitespace(seq[m])) m++
                                        if (m < close && seq[m] == ':') {
                                            val label = seq.subSequence(nameStart, nameEnd).toString()
                                            val param = params.firstOrNull { it.name == label }
                                            if (param != null) {
                                                val labelRange = TextRange(nameStart, nameEnd)
                                                addIfAliasExistsNoDollar(labelRange.endOffset, DefaultLanguageHighlighterColors.PARAMETER, labelRange) {
                                                    AliasKeyFactory.fromParameter(param)
                                                }
                                            }
                                            i = m + 1
                                            continue
                                        }
                                        i = k
                                    } else {
                                        i++
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // We are in dumb mode: schedule a refresh for when indices are ready to show call-site aliases
                dumbService.runWhenSmart {
                    if (!editor.isDisposed) {
                        app.invokeLater({ refreshInlays(project, editor, psiFile) })
                    }
                }
            }
        }
    }

    private fun clearAliasInlays(editor: Editor) {
        val docLen = editor.document.textLength
        val inline = editor.inlayModel.getInlineElementsInRange(0, docLen)
        inline.filter { it.renderer is AliasRenderer }.forEach { it.dispose() }
    }

    private fun clearAliasStyling(editor: Editor) {
        val list = editor.getUserData(HIGHLIGHTERS_KEY)
        if (list != null) {
            list.forEach { it.dispose() }
            list.clear()
        }
    }

    private class AliasRenderer(
        private val project: Project,
        private val key: com.hiddenrefactoring.model.AliasKey,
        private val alias: String,
        private val aliasColor: Color,
        private val prefixDollar: Boolean
    ) : com.intellij.openapi.editor.EditorCustomElementRenderer {
        private val padding = 2
        private val text: String
            get() = " " + when (key.type) {
                com.hiddenrefactoring.model.ElementType.VARIABLE -> "$" + alias
                com.hiddenrefactoring.model.ElementType.PARAMETER -> if (prefixDollar) "$" + alias else alias
                com.hiddenrefactoring.model.ElementType.METHOD,
                com.hiddenrefactoring.model.ElementType.FUNCTION -> alias
                else -> "≈${alias}"
            }

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val font = getFont()
            val metrics = inlay.editor.contentComponent.getFontMetrics(font)
            return metrics.stringWidth(text) + padding * 2
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val font = getFont()
            val metrics = inlay.editor.contentComponent.getFontMetrics(font)
            return metrics.height
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: com.intellij.openapi.editor.markup.TextAttributes) {
            val g2 = g as Graphics2D
            val font = getFont()
            g2.font = font

            g2.color = aliasColor

            val fm = g2.fontMetrics
            val baseline = targetRegion.y + ((targetRegion.height - fm.height) / 2) + fm.ascent
            g2.drawString(text, targetRegion.x + padding, baseline)
        }

        private fun getFont(): Font {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val base = scheme.getFont(EditorFontType.PLAIN)
            // Slightly smaller and italic to indicate alias
            return base.deriveFont(Font.ITALIC, base.size2D * 0.95f)
        }

        fun onClick() {
            val svc = project.service<AliasesService>()
            val current = svc.getAlias(key) ?: ""
            val input = Messages.showInputDialog(project, "Alias (empty to remove)", if (current.isEmpty()) "Set Alias" else "Edit Alias", null, current, null)
            if (input == null) return
            if (input.isBlank()) svc.removeAlias(key) else svc.setAlias(key, input.trim())
        }
    }
}
