package dev.toastmod.ancs.presentation

class ANCSApplication {
    fun parseAppAttribute(offset: Int, attributes: ByteArray): Int {
        val aid = AppAttributeID.fromByte(attributes[offset])
        var new_offset: Int = offset+1
        when(aid) {
            AppAttributeID.DisplayName -> {}
            else -> {}
        }
        return new_offset
    }
}