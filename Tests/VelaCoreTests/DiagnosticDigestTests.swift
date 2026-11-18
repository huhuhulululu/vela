import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
#else
@testable import VelaPhone
#endif

/// 真机诊断摘要：MetricKit 的每日指标与诊断 payload（`jsonRepresentation()` 的 JSON）解析成每日摘要，只留最近 7 天。
final class DiagnosticDigestTests: XCTestCase {
    private func json(_ object: Any) -> Data {
        try! JSONSerialization.data(withJSONObject: object)
    }

    private func metric(day: String, cpu: String = "120 sec") -> Data {
        json([
            "timeStampBegin": "\(day) 00:00:00",
            "timeStampEnd": "\(day) 23:59:00",
            "cpuMetrics": ["cumulativeCPUTime": cpu, "cumulativeCPUInstructions": "1000 kiloinstructions"],
            "gpuMetrics": ["cumulativeGPUTime": "1.5 min"],
            "memoryMetrics": ["peakMemoryUsage": "150,000 kB", "averageSuspendedMemory": ["averageValue": "30 MB"]],
            "applicationTimeMetrics": ["cumulativeForegroundTime": "3600 sec", "cumulativeBackgroundTime": "1 hr"],
            "networkTransferMetrics": [
                "cumulativeWifiDownload": "2 GB", "cumulativeWifiUpload": "500 kB",
                "cumulativeCellularDownload": "100 MB", "cumulativeCellularUpload": "1000 B"
            ],
            "displayMetrics": ["averagePixelLuminance": ["averageValue": "42 apl"]],
            "applicationExitMetrics": ["foregroundExitData": ["cumulativeAbnormalExitCount": 1]]
        ])
    }

    private func diagnostic(day: String, crashes: Int = 1, hangs: Int = 2, cpu: Int = 1, disk: Int = 0) -> Data {
        func items(_ n: Int) -> [[String: Any]] { Array(repeating: ["callStackTree": [:]], count: n) }
        return json([
            "timeStampBegin": "\(day) 08:00:00", "timeStampEnd": "\(day) 09:00:00",
            "crashDiagnostics": items(crashes), "hangDiagnostics": items(hangs),
            "cpuExceptionDiagnostics": items(cpu), "diskWriteExceptionDiagnostics": items(disk)
        ])
    }

    func testMeasurementStringsConvertToSecondsAndBytes() {
        XCTAssertEqual(DiagnosticDigest.seconds("120 sec"), 120)
        XCTAssertEqual(DiagnosticDigest.seconds("1.5 min"), 90)
        XCTAssertEqual(DiagnosticDigest.seconds("1 hr"), 3600)
        XCTAssertEqual(DiagnosticDigest.seconds("1,500 ms"), 1.5)
        XCTAssertEqual(DiagnosticDigest.bytes("150,000 kB"), 150_000_000)
        XCTAssertEqual(DiagnosticDigest.bytes("2 GB"), 2_000_000_000)
        XCTAssertEqual(DiagnosticDigest.bytes("1000 B"), 1000)
        XCTAssertNil(DiagnosticDigest.seconds("abc"))
        XCTAssertNil(DiagnosticDigest.bytes("12 parsecs"))
    }

    func testMetricPayloadBecomesADailySummary() throws {
        let day = try XCTUnwrap(DiagnosticDigest.metricDay(from: metric(day: "2026-11-17")))
        XCTAssertEqual(day.day, "2026-11-17")
        XCTAssertEqual(day.cpuSeconds, 120)
        XCTAssertEqual(day.gpuSeconds, 90)
        XCTAssertEqual(day.foregroundSeconds, 3600)
        XCTAssertEqual(day.backgroundSeconds, 3600)
        XCTAssertEqual(day.peakMemoryBytes, 150_000_000)
        XCTAssertEqual(day.wifiDownBytes, 2_000_000_000)
        XCTAssertEqual(day.wifiUpBytes, 500_000)
        XCTAssertEqual(day.cellularDownBytes, 100_000_000)
        XCTAssertEqual(day.cellularUpBytes, 1000)
        XCTAssertEqual(day.averageLuminance, 42)
        XCTAssertEqual(day.abnormalExits, 1)
        XCTAssertEqual(day.crashes, 0, "崩溃次数来自诊断 payload")
    }

    func testDiagnosticPayloadCountsAccumulateIntoTheSameDay() throws {
        var days = DiagnosticDigest.merge([], metric: try XCTUnwrap(DiagnosticDigest.metricDay(from: metric(day: "2026-11-17"))))
        days = DiagnosticDigest.merge(days, diagnostic: try XCTUnwrap(DiagnosticDigest.diagnosticDay(from: diagnostic(day: "2026-11-17"))))
        days = DiagnosticDigest.merge(days, diagnostic: try XCTUnwrap(DiagnosticDigest.diagnosticDay(from: diagnostic(day: "2026-11-17", crashes: 0, hangs: 1, cpu: 0, disk: 3))))
        XCTAssertEqual(days.count, 1)
        XCTAssertEqual(days[0].crashes, 1)
        XCTAssertEqual(days[0].hangs, 3)
        XCTAssertEqual(days[0].cpuExceptions, 1)
        XCTAssertEqual(days[0].diskWriteExceptions, 3)
        XCTAssertEqual(days[0].cpuSeconds, 120, "诊断不覆盖指标")
        let again = DiagnosticDigest.merge(days, metric: try XCTUnwrap(DiagnosticDigest.metricDay(from: metric(day: "2026-11-17", cpu: "200 sec"))))
        XCTAssertEqual(again[0].cpuSeconds, 200, "同一天的新指标替换旧指标")
        XCTAssertEqual(again[0].hangs, 3, "替换指标时保留诊断计数")
    }

    func testKeepsOnlyTheSevenMostRecentDaysNewestFirst() throws {
        var days: [DiagnosticDay] = []
        for n in [3, 1, 9, 2, 8, 4, 7, 5, 6] {
            let day = String(format: "2026-11-%02d", n)
            days = DiagnosticDigest.merge(days, metric: try XCTUnwrap(DiagnosticDigest.metricDay(from: metric(day: day))))
        }
        XCTAssertEqual(DiagnosticDigest.retained, 7)
        XCTAssertEqual(days.map(\.day), (3...9).reversed().map { String(format: "2026-11-%02d", $0) })
    }

    func testMalformedPayloadsAreIgnored() {
        XCTAssertNil(DiagnosticDigest.metricDay(from: Data("not json".utf8)))
        XCTAssertNil(DiagnosticDigest.metricDay(from: json(["cpuMetrics": ["cumulativeCPUTime": "1 sec"]])), "没有日期的不收")
        XCTAssertNil(DiagnosticDigest.diagnosticDay(from: Data()))
    }

    func testRawPayloadIsKeptButCapped() throws {
        let day = try XCTUnwrap(DiagnosticDigest.metricDay(from: metric(day: "2026-11-17")))
        XCTAssertNotNil(day.rawMetric)
        let huge = json(["timeStampEnd": "2026-11-17 23:00:00", "crashDiagnostics": [["blob": String(repeating: "x", count: 600_000)]]])
        let diag = try XCTUnwrap(DiagnosticDigest.diagnosticDay(from: huge))
        XCTAssertLessThanOrEqual(diag.rawDiagnostic?.utf8.count ?? 0, DiagnosticDigest.rawLimit)
        XCTAssertEqual(diag.crashes, 1)
    }

    func testStoreRoundTripsAndRendersText() throws {
        var days = DiagnosticDigest.merge([], metric: try XCTUnwrap(DiagnosticDigest.metricDay(from: metric(day: "2026-11-17"))))
        days = DiagnosticDigest.merge(days, diagnostic: try XCTUnwrap(DiagnosticDigest.diagnosticDay(from: diagnostic(day: "2026-11-17"))))
        let data = try DiagnosticDigest.encode(days)
        XCTAssertEqual(try DiagnosticDigest.decode(data), days)
        XCTAssertEqual(try DiagnosticDigest.decode(Data()), [], "空文件当没有数据")
        let text = DiagnosticDigest.text(days)
        for needle in ["2026-11-17", "CPU 时间 120 秒", "前台 60 分", "后台 60 分", "峰值内存 150 MB", "Wi-Fi 下行 2000 MB",
                       "蜂窝 下行 100 MB", "卡顿 2", "崩溃 1", "CPU 异常 1", "磁盘写入异常 0"] {
            XCTAssertTrue(text.contains(needle), "缺少「\(needle)」：\(text)")
        }
        XCTAssertEqual(DiagnosticDigest.text([]), "还没有诊断数据（系统大约每天投递一次，模拟器上没有）")
    }
}
