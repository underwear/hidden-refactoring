package com.hiddenrefactoring.keys

import com.hiddenrefactoring.model.AliasKey
import com.hiddenrefactoring.model.ElementType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.Variable

object AliasKeyFactory {

    fun fromContext(project: Project, editor: Editor?, psiFile: PsiFile): AliasKey? {
        val caretElement: PsiElement? = editor?.caretModel?.currentCaret?.let {
            PsiUtilCore.getElementAtOffset(psiFile, it.offset)
        }
        if (caretElement != null) {
            // Prefer variable or parameter under caret
            PsiTreeUtil.getParentOfType(caretElement, Variable::class.java, false)?.let {
                return fromVariable(it)
            }
            PsiTreeUtil.getParentOfType(caretElement, Parameter::class.java, false)?.let {
                return fromParameter(it)
            }
            PsiTreeUtil.getParentOfType(caretElement, Method::class.java, false)?.let {
                return fromMethod(it)
            }
            PsiTreeUtil.getParentOfType(caretElement, Function::class.java, false)?.let {
                return fromFunction(it)
            }
            PsiTreeUtil.getParentOfType(caretElement, PhpClass::class.java, false)?.let {
                return fromClass(it)
            }
        }
        return null
    }

    fun fromVariable(variable: Variable): AliasKey? {
        val name = variable.name?.removePrefix("$") ?: return null
        val method = PsiTreeUtil.getParentOfType(variable, Method::class.java, true)
        if (method != null) {
            val ctx = serializeContext(KeyFactory.fromMethod(method)) ?: return null
            return AliasKey(ElementType.VARIABLE, ctx, name)
        }
        val function = PsiTreeUtil.getParentOfType(variable, Function::class.java, true)
        if (function != null) {
            val ctx = serializeContext(KeyFactory.fromFunction(function)) ?: return null
            return AliasKey(ElementType.VARIABLE, ctx, name)
        }
        return null
    }

    fun fromParameter(parameter: Parameter): AliasKey? {
        val name = parameter.name ?: return null
        val method = PsiTreeUtil.getParentOfType(parameter, Method::class.java, true)
        if (method != null) {
            val ctx = serializeContext(KeyFactory.fromMethod(method)) ?: return null
            return AliasKey(ElementType.PARAMETER, ctx, name)
        }
        val function = PsiTreeUtil.getParentOfType(parameter, Function::class.java, true)
        if (function != null) {
            val ctx = serializeContext(KeyFactory.fromFunction(function)) ?: return null
            return AliasKey(ElementType.PARAMETER, ctx, name)
        }
        return null
    }

    fun fromMethod(method: Method): AliasKey? {
        val classFqn = method.containingClass?.fqn ?: return null
        return AliasKey(ElementType.METHOD, classFqn, method.name)
    }

    fun fromClassAndMethodName(phpClass: PhpClass, methodName: String): AliasKey? {
        val classFqn = phpClass.fqn ?: return null
        return AliasKey(ElementType.METHOD, classFqn, methodName)
    }

    fun fromFunction(function: Function): AliasKey? {
        val fqn = function.fqn ?: return null
        return AliasKey(ElementType.FUNCTION, "", fqn)
    }

    fun fromClass(phpClass: PhpClass): AliasKey? {
        val fqn = phpClass.fqn ?: return null
        return AliasKey(ElementType.CLASS, "", fqn)
    }

    private fun serializeContext(elementKey: com.hiddenrefactoring.model.ElementKey?): String? {
        if (elementKey == null) return null
        return "${elementKey.type}:${elementKey.key}"
    }
}
