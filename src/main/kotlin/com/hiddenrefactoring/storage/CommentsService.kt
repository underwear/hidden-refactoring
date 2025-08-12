package com.hiddenrefactoring.storage

import com.hiddenrefactoring.model.Comment
import com.hiddenrefactoring.model.ElementKey
import com.hiddenrefactoring.model.ElementType
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.time.Instant
import com.intellij.util.messages.Topic

@Service(Service.Level.PROJECT)
@State(name = "HiddenRefactoringComments", storages = [Storage("hidden-refactoring-comments.xml")])
class CommentsService(private val project: Project) : PersistentStateComponent<CommentsService.StateBean> {
    interface Listener {
        fun changed(key: ElementKey)
    }
    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("HiddenRefactoringCommentsChanged", Listener::class.java)
    }

    data class StateBean(
        var map: MutableMap<String, MutableList<Comment>> = mutableMapOf()
    )

    private var state = StateBean()

    override fun getState(): StateBean = state

    override fun loadState(state: StateBean) {
        this.state = state
        // Purge legacy data created by a previous serialization bug where all keys serialized to a constant literal
        val badKey = "${'$'}{key.type}:${'$'}{key.key}"
        state.map.remove(badKey)

        // Migrate METHOD/FUNCTION keys that used to include parameter signatures to signature-less form
        if (state.map.isNotEmpty()) {
            val migrated = mutableMapOf<String, MutableList<Comment>>()
            for ((k, v) in state.map) {
                val idx = k.indexOf(':')
                if (idx <= 0) {
                    migrated[k] = v
                    continue
                }
                val typeStr = k.substring(0, idx)
                val keyStr = k.substring(idx + 1)
                val newKeyStr = when (typeStr) {
                    ElementType.METHOD.name, ElementType.FUNCTION.name -> keyStr.substringBefore("(")
                    else -> keyStr
                }
                val newSerialized = "$typeStr:$newKeyStr"
                migrated[newSerialized] = v
            }
            state.map = migrated
        }
    }

    fun addComment(key: ElementKey, text: String): Comment {
        val comment = Comment(text = text)
        val k = serialize(key)
        val list = state.map.getOrPut(k) { mutableListOf() }
        list.clear() // enforce single comment per element
        list.add(comment)
        project.messageBus.syncPublisher(TOPIC).changed(key)
        return comment
    }

    fun getComments(key: ElementKey): List<Comment> = state.map[serialize(key)]?.toList() ?: emptyList()

    fun removeComment(key: ElementKey, id: String): Boolean {
        val list = state.map[serialize(key)] ?: return false
        val removed = list.removeIf { it.id == id }
        if (list.isEmpty()) state.map.remove(serialize(key))
        if (removed) project.messageBus.syncPublisher(TOPIC).changed(key)
        return removed
    }

    fun updateComment(key: ElementKey, id: String, newText: String): Boolean {
        val list = state.map[serialize(key)] ?: return false
        val c = list.firstOrNull { it.id == id } ?: return false
        c.text = newText
        c.updatedAt = Instant.now().toEpochMilli()
        project.messageBus.syncPublisher(TOPIC).changed(key)
        return true
    }

    private fun serialize(key: ElementKey): String = "${key.type}:${key.key}"

    private fun deserialize(serialized: String): ElementKey? {
        val idx = serialized.indexOf(':')
        if (idx <= 0) return null
        val typeStr = serialized.substring(0, idx)
        val keyStr = serialized.substring(idx + 1)
        return try {
            val type = ElementType.valueOf(typeStr)
            ElementKey(type, keyStr)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun getAllKeys(): List<ElementKey> = state.map.keys.mapNotNull { deserialize(it) }

    fun hasComments(key: ElementKey): Boolean = state.map.containsKey(serialize(key))
}
