package com.shigusdream.actions

/** Реестр зарегистрированных действий. Доставка ограничена этим набором. */
class ActionRegistry {
    private val actions = LinkedHashMap<String, ClientAction>()

    fun register(action: ClientAction) {
        require(!actions.containsKey(action.id)) { "Action ${action.id} уже зарегистрирован" }
        actions[action.id] = action
    }

    fun byId(id: String): ClientAction? = actions[id]

    fun all(): List<ClientAction> = actions.values.toList()

    fun ids(): List<String> = actions.keys.toList()
}
