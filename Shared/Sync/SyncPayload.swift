#if canImport(Compression)
import Compression
import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 载荷 = 1 字节格式标记 + 正文（docs/plans/sync.md 第 8 节，写死）。
/// `0x00` 正文为明文 JSON；`0x01` 正文为 raw DEFLATE（Apple `COMPRESSION_ZLIB`）。压缩只是优化。
enum SyncPayload {
    static let plain: UInt8 = 0x00
    static let deflated: UInt8 = 0x01

    /// 压缩一次；结果比原文短才用 `0x01`。
    static func encode(_ json: [UInt8]) -> [UInt8] {
        if let packed = deflate(json), packed.count < json.count { return [deflated] + packed }
        return [plain] + json
    }

    /// 解出明文；超过 `limit`、截断、末块之后有剩余输入、未知标记一律返回 nil。
    static func decode(_ payload: [UInt8], limit: Int = SyncLimits.plaintextBytes) -> [UInt8]? {
        guard let flag = payload.first else { return nil }
        let body = Array(payload.dropFirst())
        switch flag {
        case plain: return body.count <= limit ? body : nil
        case deflated: return inflate(body, limit: limit)
        default: return nil
        }
    }

    static func deflate(_ input: [UInt8]) -> [UInt8]? {
        guard !input.isEmpty else { return nil }
        // 只在比原文短时采用，所以目标缓冲区取原文长度即可；放不下返回 0，按「不压缩」处理。
        var out = [UInt8](repeating: 0, count: input.count)
        let written = input.withUnsafeBufferPointer { src in
            out.withUnsafeMutableBufferPointer { dst in
                compression_encode_buffer(dst.baseAddress!, dst.count, src.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0 else { return nil }
        return Array(out.prefix(written))
    }

    /// 流式解压：输出计数超过 `limit` 立即中止；流必须以末块正常结束且恰好用完输入。
    /// `Compression` 遇到末块之后的尾随数据不报错，所以先只喂到倒数第二个字节：此时若已结束，说明有尾随数据；
    /// 再喂最后一个字节并收尾，必须恰在此时结束且输入用完。
    static func inflate(_ input: [UInt8], limit: Int) -> [UInt8]? {
        guard !input.isEmpty else { return nil }
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { return nil }
        defer { compression_stream_destroy(stream) }
        var out: [UInt8] = []
        return input.withUnsafeBufferPointer { src -> [UInt8]? in
            let head = src.count - 1
            if head > 0 {
                stream.pointee.src_ptr = src.baseAddress!
                stream.pointee.src_size = head
                switch drain(stream, finalize: false, into: &out, limit: limit) {
                case .some(true), .none: return nil
                case .some(false): break
                }
            }
            stream.pointee.src_ptr = src.baseAddress! + head
            stream.pointee.src_size = 1
            guard drain(stream, finalize: true, into: &out, limit: limit) == true, stream.pointee.src_size == 0 else { return nil }
            return out
        }
    }

    /// 处理当前输入直到用完或流结束。返回 true 表示流已结束，false 表示输入用完仍未结束，nil 表示出错或超限。
    private static func drain(_ stream: UnsafeMutablePointer<compression_stream>, finalize: Bool, into out: inout [UInt8], limit: Int) -> Bool? {
        let chunk = 64 * 1024
        var buffer = [UInt8](repeating: 0, count: chunk)
        let flags = finalize ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
        while true {
            let status: compression_status = buffer.withUnsafeMutableBufferPointer { dst in
                stream.pointee.dst_ptr = dst.baseAddress!
                stream.pointee.dst_size = chunk
                return compression_stream_process(stream, flags)
            }
            let produced = chunk - stream.pointee.dst_size
            if produced > 0 {
                guard out.count + produced <= limit else { return nil }
                out += buffer.prefix(produced)
            }
            switch status {
            case COMPRESSION_STATUS_END: return true
            case COMPRESSION_STATUS_OK:
                // 输出缓冲区写满说明还有待取的输出，继续；否则输入已用完。
                if produced == chunk { continue }
                if stream.pointee.src_size == 0 { return finalize ? nil : false }
                if produced == 0 { return nil }
            default: return nil
            }
        }
    }
}
#endif
