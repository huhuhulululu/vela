import Foundation

/// 并发执行一组请求，每个请求各自带超时：失败、超时或被取消的项返回 nil，其余照常返回；
/// 结果顺序与输入一致。整体耗时不超过 `timeout`（再加取消收尾），不会被最慢的一个拖住。
@available(macOS 10.15, *)
public enum TimedFanOut {
    public static func run<Input: Sendable, Output: Sendable>(
        _ inputs: [Input], timeout: TimeInterval,
        work: @escaping @Sendable (Input) async throws -> Output
    ) async -> [Output?] {
        await withTaskGroup(of: (Int, Output?).self) { group in
            for (index, input) in inputs.enumerated() {
                group.addTask { (index, await withTimeout(timeout) { try? await work(input) }) }
            }
            var results = [Output?](repeating: nil, count: inputs.count)
            for await (index, value) in group { results[index] = value }
            return results
        }
    }

    private static func withTimeout<T: Sendable>(_ seconds: TimeInterval, _ body: @escaping @Sendable () async -> T?) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask { await body() }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(max(seconds, 0) * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}
