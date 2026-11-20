package vela.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * 每路一个进程内数据源。播放器只看见 `vela://lane/<id>/…`，
 * 续期换掉的真实子列表地址不会出现在 [getUri] 里。
 * 读分片时 [getUri] 返回真实绝对地址（P1：不用分片合成地址）。
 */
class LaneDataSource(
    private val hub: LaneHub,
    private val ownerId: String?,
) : BaseDataSource(true) {
    private var body: ByteArray = ByteArray(0)
    private var position: Int = 0
    private var opened: Boolean = false
    private var reportedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val loaded = hub.load(dataSpec.uri, ownerId)
        body = loaded.bytes
        reportedUri = loaded.reported
        val start = dataSpec.position.toInt().coerceAtLeast(0)
        if (start > body.size) {
            throw httpFailure(416, dataSpec.uri)
        }
        position = start
        opened = true
        transferStarted(dataSpec)
        val remaining = (body.size - position).toLong()
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining) else remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= body.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, body.size - position)
        body.copyInto(buffer, offset, position, position + count)
        position += count
        bytesTransferred(count)
        return count
    }

    /** 列表是合成地址；分片是真实地址。没打开时是 null。 */
    override fun getUri(): Uri? = if (opened) reportedUri else null

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        body = ByteArray(0)
        position = 0
    }
}

class LaneDataSourceFactory(
    private val hub: LaneHub,
    private val ownerId: String,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = LaneDataSource(hub, ownerId)
}
