import Foundation

/// 一天的真机诊断摘要（MetricKit 每日指标 + 当天的诊断 payload）。只在本机保存，不联网、不上传。
public struct DiagnosticDay: Codable, Equatable, Sendable {
    /// `YYYY-MM-DD`，取 payload 的 `timeStampEnd`。
    public var day: String
    public var cpuSeconds: Double?
    public var gpuSeconds: Double?
    /// App 在前台、后台运行的累计时长（MetricKit 不按前后台拆 CPU 时间）。
    public var foregroundSeconds: Double?
    public var backgroundSeconds: Double?
    public var peakMemoryBytes: Double?
    public var wifiDownBytes: Double?
    public var wifiUpBytes: Double?
    public var cellularDownBytes: Double?
    public var cellularUpBytes: Double?
    /// 平均像素亮度（apl，0 到 100），耗电参考。
    public var averageLuminance: Double?
    /// 异常退出（指标 payload 里各类异常退出计数之和）。
    public var abnormalExits: Int = 0
    public var hangs: Int = 0
    public var crashes: Int = 0
    public var cpuExceptions: Int = 0
    public var diskWriteExceptions: Int = 0
    /// 原始 JSON（截断到 `DiagnosticDigest.rawLimit`），分享时附上，便于离线分析。
    public var rawMetric: String?
    public var rawDiagnostic: String?

    public init(day: String) {
        self.day = day
    }
}

/// MetricKit payload（`jsonRepresentation()`）到每日摘要的纯函数，以及最近 7 天的合并与文本。
/// 键名按 MetricKit 文档；嵌套层级不同的版本也能找到（按键名递归查找）。值是带单位的字符串，如 `"120 sec"`、`"150,000 kB"`。
public enum DiagnosticDigest {
    /// 只留最近几天。
    public static let retained = 7
    /// 每份原始 JSON 最多保留的字节数（诊断里的调用栈可能很大）。
    public static let rawLimit = 256 * 1024

    // MARK: 解析

    public static func metricDay(from data: Data) -> DiagnosticDay? {
        guard let root = object(data), let dayKey = day(of: root) else { return nil }
        var result = DiagnosticDay(day: dayKey)
        result.cpuSeconds = findAny("cumulativeCPUTime", in: root).flatMap(seconds)
        result.gpuSeconds = findAny("cumulativeGPUTime", in: root).flatMap(seconds)
        result.foregroundSeconds = findAny("cumulativeForegroundTime", in: root).flatMap(seconds)
        result.backgroundSeconds = findAny("cumulativeBackgroundTime", in: root).flatMap(seconds)
        result.peakMemoryBytes = findAny("peakMemoryUsage", in: root).flatMap(bytes)
        result.wifiDownBytes = findAny("cumulativeWifiDownload", in: root).flatMap(bytes)
        result.wifiUpBytes = findAny("cumulativeWifiUpload", in: root).flatMap(bytes)
        result.cellularDownBytes = findAny("cumulativeCellularDownload", in: root).flatMap(bytes)
        result.cellularUpBytes = findAny("cumulativeCellularUpload", in: root).flatMap(bytes)
        if let luminance = findAny("averagePixelLuminance", in: root) {
            let text = (luminance as? [String: Any])?["averageValue"] ?? luminance
            result.averageLuminance = number(text)
        }
        if let exits = findAny("applicationExitMetrics", in: root) {
            result.abnormalExits = sumCounts(exits) { $0 != "cumulativeNormalAppExitCount" && $0.hasSuffix("ExitCount") }
        }
        result.rawMetric = raw(data)
        return result
    }

    public static func diagnosticDay(from data: Data) -> DiagnosticDay? {
        guard let root = object(data), let dayKey = day(of: root) else { return nil }
        var result = DiagnosticDay(day: dayKey)
        func count(_ key: String) -> Int { (findAny(key, in: root) as? [Any])?.count ?? 0 }
        result.crashes = count("crashDiagnostics")
        result.hangs = count("hangDiagnostics")
        result.cpuExceptions = count("cpuExceptionDiagnostics")
        result.diskWriteExceptions = count("diskWriteExceptionDiagnostics")
        result.rawDiagnostic = raw(data)
        return result
    }

    // MARK: 合并与保留

    /// 同一天的新指标替换旧指标（保留当天的诊断计数）。
    public static func merge(_ days: [DiagnosticDay], metric: DiagnosticDay) -> [DiagnosticDay] {
        var next = days
        if let index = next.firstIndex(where: { $0.day == metric.day }) {
            var merged = metric
            let old = next[index]
            merged.hangs = old.hangs
            merged.crashes = old.crashes
            merged.cpuExceptions = old.cpuExceptions
            merged.diskWriteExceptions = old.diskWriteExceptions
            merged.rawDiagnostic = old.rawDiagnostic
            next[index] = merged
        } else {
            next.append(metric)
        }
        return trimmed(next)
    }

    /// 同一天的诊断计数累加（一天可能投递多份），原始 JSON 留最新一份。
    public static func merge(_ days: [DiagnosticDay], diagnostic: DiagnosticDay) -> [DiagnosticDay] {
        var next = days
        if let index = next.firstIndex(where: { $0.day == diagnostic.day }) {
            next[index].hangs += diagnostic.hangs
            next[index].crashes += diagnostic.crashes
            next[index].cpuExceptions += diagnostic.cpuExceptions
            next[index].diskWriteExceptions += diagnostic.diskWriteExceptions
            next[index].rawDiagnostic = diagnostic.rawDiagnostic
        } else {
            next.append(diagnostic)
        }
        return trimmed(next)
    }

    private static func trimmed(_ days: [DiagnosticDay]) -> [DiagnosticDay] {
        Array(days.sorted { $0.day > $1.day }.prefix(retained))
    }

    // MARK: 存储格式

    public static func encode(_ days: [DiagnosticDay]) throws -> Data {
        try JSONEncoder().encode(days)
    }

    /// 空数据当没有记录；坏文件抛错，由调用方决定丢弃。
    public static func decode(_ data: Data) throws -> [DiagnosticDay] {
        guard !data.isEmpty else { return [] }
        return try JSONDecoder().decode([DiagnosticDay].self, from: data)
    }

    // MARK: 文本

    /// 复制与分享用的摘要（不含原始 JSON）。
    public static func text(_ days: [DiagnosticDay]) -> String {
        guard !days.isEmpty else { return "还没有诊断数据（系统大约每天投递一次，模拟器上没有）" }
        return days.map(line).joined(separator: "\n")
    }

    public static func line(_ day: DiagnosticDay) -> String {
        var parts = [day.day]
        if let value = day.cpuSeconds { parts.append("CPU 时间 \(duration(value))") }
        if let value = day.gpuSeconds { parts.append("GPU 时间 \(duration(value))") }
        if let value = day.foregroundSeconds { parts.append("前台 \(duration(value))") }
        if let value = day.backgroundSeconds { parts.append("后台 \(duration(value))") }
        if let value = day.peakMemoryBytes { parts.append("峰值内存 \(megabytes(value))") }
        if day.wifiDownBytes != nil || day.wifiUpBytes != nil {
            parts.append("Wi-Fi 下行 \(megabytes(day.wifiDownBytes ?? 0)) 上行 \(megabytes(day.wifiUpBytes ?? 0))")
        }
        if day.cellularDownBytes != nil || day.cellularUpBytes != nil {
            parts.append("蜂窝 下行 \(megabytes(day.cellularDownBytes ?? 0)) 上行 \(megabytes(day.cellularUpBytes ?? 0))")
        }
        if let value = day.averageLuminance { parts.append("平均亮度 \(Int(value.rounded())) apl") }
        parts.append("卡顿 \(day.hangs)")
        parts.append("崩溃 \(day.crashes)")
        parts.append("异常退出 \(day.abnormalExits)")
        parts.append("CPU 异常 \(day.cpuExceptions)")
        parts.append("磁盘写入异常 \(day.diskWriteExceptions)")
        return parts.joined(separator: "，")
    }

    private static func duration(_ seconds: Double) -> String {
        seconds < 600 ? "\(Int(seconds.rounded())) 秒" : "\(Int((seconds / 60).rounded())) 分"
    }

    private static func megabytes(_ bytes: Double) -> String {
        "\(Int((bytes / 1_000_000).rounded())) MB"
    }

    // MARK: 单位

    /// `"120 sec"`、`"1.5 min"`、`"1 hr"`、`"1,500 ms"` → 秒。
    public static func seconds(_ text: Any) -> Double? {
        guard let (value, unit) = measurement(text) else { return nil }
        switch unit {
        case "ms": return value / 1000
        case "s", "sec", "secs", "second", "seconds": return value
        case "min", "mins", "minute", "minutes": return value * 60
        case "h", "hr", "hrs", "hour", "hours": return value * 3600
        default: return nil
        }
    }

    /// `"150,000 kB"`、`"2 GB"`、`"1000 B"` → 字节（十进制单位，与 MetricKit 的 `UnitInformationStorage` 一致）。
    public static func bytes(_ text: Any) -> Double? {
        guard let (value, unit) = measurement(text) else { return nil }
        switch unit.lowercased() {
        case "b", "bytes", "byte": return value
        case "kb": return value * 1e3
        case "mb": return value * 1e6
        case "gb": return value * 1e9
        case "tb": return value * 1e12
        default: return nil
        }
    }

    private static func measurement(_ text: Any) -> (Double, String)? {
        guard let string = text as? String else { return nil }
        let parts = string.replacingOccurrences(of: ",", with: "").split(separator: " ", maxSplits: 1)
        guard parts.count == 2, let value = Double(parts[0]), value.isFinite else { return nil }
        return (value, String(parts[1]).trimmingCharacters(in: .whitespaces))
    }

    private static func number(_ text: Any) -> Double? {
        if let value = text as? Double { return value }
        if let value = text as? Int { return Double(value) }
        guard let string = text as? String else { return nil }
        return Double(string.replacingOccurrences(of: ",", with: "").split(separator: " ").first ?? "")
    }

    // MARK: JSON

    private static func object(_ data: Data) -> [String: Any]? {
        guard !data.isEmpty else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func day(of root: [String: Any]) -> String? {
        guard let stamp = (root["timeStampEnd"] ?? root["timeStampBegin"]) as? String, stamp.count >= 10 else { return nil }
        let day = String(stamp.prefix(10))
        let digits = day.split(separator: "-")
        guard digits.count == 3, digits.allSatisfy({ Int($0) != nil }) else { return nil }
        return day
    }

    /// 按键名递归找第一个值（不同系统版本的嵌套层级可能不同）。
    private static func findAny(_ key: String, in node: Any) -> Any? {
        if let dict = node as? [String: Any] {
            if let hit = dict[key] { return hit }
            for value in dict.values {
                if let hit = findAny(key, in: value) { return hit }
            }
        } else if let list = node as? [Any] {
            for value in list {
                if let hit = findAny(key, in: value) { return hit }
            }
        }
        return nil
    }

    private static func sumCounts(_ node: Any, where include: (String) -> Bool) -> Int {
        var total = 0
        if let dict = node as? [String: Any] {
            for (key, value) in dict {
                if include(key), let count = value as? Int { total += count } else { total += sumCounts(value, where: include) }
            }
        } else if let list = node as? [Any] {
            for value in list { total += sumCounts(value, where: include) }
        }
        return total
    }

    /// 截到 `rawLimit` 字节；截断点落在多字节字符中间时往前退，最多退 3 字节。
    private static func raw(_ data: Data) -> String? {
        for back in 0...3 {
            if let text = String(data: data.prefix(max(rawLimit - back, 0)), encoding: .utf8) { return text }
        }
        return nil
    }
}
