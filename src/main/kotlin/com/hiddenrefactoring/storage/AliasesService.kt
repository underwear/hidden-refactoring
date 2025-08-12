package com.hiddenrefactoring.storage

import com.hiddenrefactoring.model.AliasKey
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

@Service(Service.Level.PROJECT)
@State(name = "HiddenRefactoringAliases", storages = [Storage("hidden-refactoring-aliases.xml")])
class AliasesService(private val project: Project) : PersistentStateComponent<AliasesService.StateBean> {
    interface Listener {
        fun changed(key: AliasKey)
    }
    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("HiddenRefactoringAliasesChanged", Listener::class.java)
    }

    data class StateBean(
        var map: MutableMap<String, String> = mutableMapOf()
    )

    private var state = StateBean()

    override fun getState(): StateBean = state

    override fun loadState(state: StateBean) {
        this.state = state
        // Purge entries created with a buggy context serialization (literal placeholders like "${elementKey.type}")
        val toRemove = state.map.keys.filter { it.contains("\${'$'}{elementKey") }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach { state.map.remove(it) }
        }
    }

    private fun k(key: AliasKey): String = key.toString()

    fun getAlias(key: AliasKey): String? = state.map[k(key)]

    fun setAlias(key: AliasKey, value: String) {
        if (value.isBlank()) {
            removeAlias(key)
            return
        }
        state.map[k(key)] = value
        project.messageBus.syncPublisher(TOPIC).changed(key)
    }

    fun removeAlias(key: AliasKey) {
        val removed = state.map.remove(k(key)) != null
        if (removed) project.messageBus.syncPublisher(TOPIC).changed(key)
    }

    fun hasAlias(key: AliasKey): Boolean = state.map.containsKey(k(key))
}
