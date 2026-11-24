package vela.core.base.lane

/**
 * 点下去的那一路、那一间、那一次播放。异步完成时核对，对不上就放弃。
 * 单窗上恒为主路。
 */
data class RoomTarget(
    var laneID: LaneID,
    var username: String,
    var generation: Int,
) {
    /** 用户名不分大小写；路或代次不同都算对不上。 */
    fun matches(laneID: LaneID, username: String, generation: Int): Boolean =
        this.laneID == laneID &&
            this.username.lowercase() == username.lowercase() &&
            this.generation == generation

    companion object {
        fun primary(username: String, generation: Int): RoomTarget =
            RoomTarget(LaneID.PRIMARY, username, generation)
    }
}
