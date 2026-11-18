package vela.core.base.model

data class RoomQuery(
    val text: String = "",
    val publicOnly: Boolean = true,
    val blocked: Set<String> = emptySet(),
    val only: Set<String>? = null,
    val hidden: Set<String> = emptySet(),
) {
    fun matches(room: Room): Boolean {
        if (room.username.lowerRoot() in blocked) return false
        val onlyNames = only
        if (onlyNames != null && room.username.lowerRoot() !in onlyNames) return false
        if (publicOnly && room.show != ShowState.publicShow) return false
        if (hidden.isNotEmpty() && isHidden(room, hidden)) return false
        val needle = text.trimSwiftWhitespace().lowerRoot()
        if (needle.isEmpty()) return true
        if (needle in room.username.lowerRoot()) return true
        if (needle in room.displayName.lowerRoot()) return true
        if (needle in room.subject.lowerRoot()) return true
        return room.tags.any { needle in it.lowerRoot() }
    }

    companion object {
        internal fun isHidden(room: Room, keys: Set<String>): Boolean {
            if (room.tags.take(Prefs.maxTagsPerRoom).any { TasteKeys.tagKey(it) in keys }) return true
            val gender = room.gender ?: return false
            return TasteKeys.genderKey(gender) in keys
        }
    }
}
