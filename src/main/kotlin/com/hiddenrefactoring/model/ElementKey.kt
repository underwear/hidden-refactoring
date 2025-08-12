package com.hiddenrefactoring.model

import com.intellij.openapi.vfs.VirtualFile

/**
 * Stable key for attaching comments to an element.
 * - For CLASS: fully qualified name (e.g., \Namespace\Class)
 * - For METHOD: class FQN + ::methodName + normalized signature (e.g., (int,string))
 * - For FUNCTION: function FQN + signature
 * - For FILE: VirtualFile URL
 */
data class ElementKey(
    val type: ElementType,
    val key: String,
) {
    companion object {
        fun forFile(file: VirtualFile): ElementKey = ElementKey(ElementType.FILE, file.url)
    }
}
