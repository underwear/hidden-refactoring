package com.hiddenrefactoring.codevision

import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider

class PhpCommentsCodeVisionGroupProvider : CodeVisionGroupSettingProvider {
    override val groupId: String = PhpCommentsCodeVisionProvider.ID
    override val groupName: String = "Hidden Refactoring: Comments"
    override val description: String = "Shows IDE-level comments inline for PHP classes, methods, and functions."
}
