package com.hiddenrefactoring.keys

import com.hiddenrefactoring.model.ElementKey
import com.hiddenrefactoring.model.ElementType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

object KeyFactory {

    fun fromContext(project: Project, editor: Editor?, psiFile: PsiFile): ElementKey? {
        val file: VirtualFile? = psiFile.virtualFile
        val caretElement: PsiElement? = editor?.caretModel?.currentCaret?.let {
            PsiUtilCore.getElementAtOffset(psiFile, it.offset)
        }
        // Try method, function, class from nearest ancestor
        val method = caretElement?.let { PsiTreeUtil.getParentOfType(it, Method::class.java, false) }
        if (method != null) return fromMethod(method)
        val function = caretElement?.let { PsiTreeUtil.getParentOfType(it, Function::class.java, false) }
        if (function != null) return fromFunction(function)
        val phpClass = caretElement?.let { PsiTreeUtil.getParentOfType(it, PhpClass::class.java, false) }
        if (phpClass != null) return fromClass(phpClass)
        // Fallback to file
        if (file != null) return ElementKey.forFile(file)
        return null
    }

    fun fromClass(phpClass: PhpClass): ElementKey? {
        val fqn = phpClass.fqn ?: return null
        return ElementKey(ElementType.CLASS, fqn)
    }

    fun fromMethod(method: Method): ElementKey? {
        val containingClass = method.containingClass ?: return null
        val classFqn = containingClass.fqn ?: return null
        return ElementKey(ElementType.METHOD, "${classFqn}::${method.name}")
    }

    fun fromFunction(function: Function): ElementKey? {
        val fqn = function.fqn ?: return null
        return ElementKey(ElementType.FUNCTION, fqn)
    }

    private fun normalizeMethodSignature(method: Method): String {
        val params = method.parameters.map { it.type?.toString() ?: "mixed" }
        return params.joinToString(prefix = "(", postfix = ")", separator = ",")
    }

    private fun normalizeFunctionSignature(function: Function): String {
        val params = function.parameters.map { it.type?.toString() ?: "mixed" }
        return params.joinToString(prefix = "(", postfix = ")", separator = ",")
    }

    // Resolve examples for future use (not used yet)
    fun resolveClass(project: Project, fqn: String): PhpClass? {
        val index = PhpIndex.getInstance(project)
        return index.getAnyByFQN(fqn).filterIsInstance<PhpClass>().firstOrNull()
    }

    fun resolve(project: Project, key: ElementKey): PsiElement? {
        return when (key.type) {
            ElementType.FILE -> {
                val vf = VirtualFileManager.getInstance().findFileByUrl(key.key) ?: return null
                PsiManager.getInstance(project).findFile(vf)
            }
            ElementType.CLASS -> resolveClass(project, key.key)
            ElementType.METHOD -> {
                // format: ClassFQN::methodName(signature)
                val raw = key.key
                val sep = raw.indexOf("::")
                if (sep <= 0) return null
                val classFqn = raw.substring(0, sep)
                val rest = raw.substring(sep + 2)
                val methodName = rest.substringBefore("(")
                val phpClass = resolveClass(project, classFqn) ?: return null
                phpClass.methods.firstOrNull { it.name == methodName }
            }
            ElementType.FUNCTION -> {
                // format: fqn(signature) -> strip signature
                val fqn = key.key.substringBefore("(")
                val index = PhpIndex.getInstance(project)
                index.getFunctionsByFQN(fqn).firstOrNull()
            }
            else -> null
        }
    }
}
