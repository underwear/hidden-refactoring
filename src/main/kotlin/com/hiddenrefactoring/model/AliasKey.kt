package com.hiddenrefactoring.model

/**
 * Context-sensitive key for aliasing displayed names.
 * - type: what kind of element (CLASS, METHOD, FUNCTION, VARIABLE, PARAMETER, ...)
 * - context: disambiguation scope, e.g. method/function ElementKey for locals, class FQN for methods
 * - name: original PSI name (without decorations like $)
 */
data class AliasKey(
    val type: ElementType,
    val context: String,
    val name: String,
) {
    override fun toString(): String = "${type}:${context}:${name}"
}
