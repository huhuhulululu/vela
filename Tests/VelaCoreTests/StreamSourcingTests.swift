import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import VelaCore

private struct Canned: HTTPClient {
    var result: HTTPResult?
    var error: Error?

    func send(_ request: URLRequest) async throws -> HTTPResult {
        if let error { throw error }
        return result ?? HTTPResult(status: 500, data: Data())
    }
}

private func context(_ status: String, source: String? = nil) -> Data {
    let field = source.map { #","hls_source":"\#($0)""# } ?? ""
    return Data(#"{"room_status":"\#(status)","broadcaster_username":"a"\#(field)}"#.utf8)
}

private let source = "https://edge2.example/v1/edge/streams/origin.a.1/llhls.m3u8?token=t"
private let edgeURL = URL(string: "https://edge9.example/v1/edge/streams/origin.a.1/llhls.m3u8?token=u")!

final class StreamSourcingTests: XCTestCase {
    // MARK: 解码

    func testProbeDecodesStatusAndSource() {
        XCTAssertEqual(ChatContext.probe(status: 200, data: context("public", source: source)),
                       .room(show: .publicShow, source: URL(string: source)))
        XCTAssertEqual(ChatContext.probe(status: 200, data: context("public", source: "")), .room(show: .publicShow, source: nil))
        XCTAssertEqual(ChatContext.probe(status: 200, data: context("public")), .room(show: .publicShow, source: nil))
        XCTAssertEqual(ChatContext.probe(status: 200, data: context("public", source: "http://plain.example/a.m3u8")),
                       .room(show: .publicShow, source: nil), "只接受 https")
        XCTAssertEqual(ChatContext.probe(status: 200, data: context("away")), .room(show: .away, source: nil))
    }

    func testProbeFailureKinds() {
        XCTAssertEqual(ChatContext.probe(status: 403, data: Data("<title>Just a moment...</title>".utf8)), .challenge)
        XCTAssertEqual(ChatContext.probe(status: 200, data: Data("<title>Verify your age</title>".utf8)), .challenge)
        XCTAssertEqual(ChatContext.probe(status: 200, data: Data("<html>busy</html>".utf8)), .notJSON)
        XCTAssertEqual(ChatContext.probe(status: 502, data: Data()), .notJSON)
        XCTAssertEqual(ChatContext.probe(status: 404, data: Data(#"{"detail":"x"}"#.utf8)), .failed)
        XCTAssertEqual(ChatContext.probe(status: 200, data: Data("[1]".utf8)), .failed)
    }

    // MARK: 选择

    func testPublicWithSourceUsesContextDirectly() {
        let step = StreamSourcing.first(.room(show: .publicShow, source: URL(string: source)))
        guard case .use(let ticket) = step else { return XCTFail("expected use") }
        XCTAssertTrue(ticket.isPlayable)
        XCTAssertEqual(ticket.url?.absoluteString, source)
    }

    func testKnownNonPublicStatesDecideWithoutAjax() {
        let cases: [(ShowState, StreamFailure)] = [
            (.away, .away), (.privateShow, .privateShow), (.group, .group), (.offline, .offline),
            (.hidden, .hidden), (.password, .password)
        ]
        for (show, failure) in cases {
            guard case .use(let ticket) = StreamSourcing.first(.room(show: show, source: URL(string: source))) else {
                return XCTFail("\(show) 应直接定论")
            }
            XCTAssertNil(ticket.url, "非公开时即使带了地址也不播")
            XCTAssertEqual(ticket.failure, failure)
        }
    }

    func testFallsBackToAjax() {
        let probes: [ContextProbe] = [
            .room(show: .publicShow, source: nil), .room(show: .unknown, source: URL(string: source)),
            .challenge, .notJSON, .backedOff, .failed
        ]
        for probe in probes {
            XCTAssertEqual(StreamSourcing.first(probe), .fallback, "\(probe)")
        }
    }

    func testSettleKeepsPlayableAjax() {
        let ajax = StreamTicket(success: true, url: edgeURL, status: .publicShow)
        let settled = StreamSourcing.settle(probe: .room(show: .publicShow, source: nil), fallback: ajax)
        XCTAssertEqual(settled, ajax)
        XCTAssertNil(settled.failure)
    }

    func testSettleEmptyOnBothIsGeo() {
        let emptyPublic = StreamTicket(success: true, url: nil, status: .publicShow)
        XCTAssertEqual(StreamSourcing.settle(probe: .room(show: .publicShow, source: nil), fallback: emptyPublic).failure, .geo)
        // ajax 状态说不清（会被当成离线）时，以 chatvideocontext 的「公开」为准，归为地区受限。
        let vague = StreamTicket(success: false, url: nil, status: .unknown)
        XCTAssertEqual(vague.failure, .offline)
        XCTAssertEqual(StreamSourcing.settle(probe: .room(show: .publicShow, source: nil), fallback: vague).failure, .geo)
    }

    func testSettleDefersToAjaxStateChange() {
        let nowAway = StreamTicket(success: true, url: nil, status: .away)
        XCTAssertEqual(StreamSourcing.settle(probe: .room(show: .publicShow, source: nil), fallback: nowAway).failure, .away)
        // chatvideocontext 没给定论（失败、质询、非 JSON）时完全按 ajax。
        let emptyPublic = StreamTicket(success: true, url: nil, status: .publicShow)
        let vague = StreamTicket(success: false, url: nil, status: .unknown)
        for probe in [ContextProbe.failed, .challenge, .notJSON, .backedOff] {
            XCTAssertEqual(StreamSourcing.settle(probe: probe, fallback: vague), vague)
            XCTAssertEqual(StreamSourcing.settle(probe: probe, fallback: emptyPublic).failure, .geo)
        }
    }

    func testOriginLabels() {
        XCTAssertEqual(StreamOrigin.chatContext.rawValue, "chatvideocontext")
        XCTAssertEqual(StreamOrigin.edge.rawValue, "ajax")
        XCTAssertEqual(StreamOrigin.sidecar.rawValue, "sidecar")
        let sourced = SourcedTicket(ticket: StreamTicket(success: true, url: nil, status: .away), origin: .chatContext)
        XCTAssertEqual(sourced.failure, .away)
    }

    // MARK: 请求

    func testProbeRequestRecordsBackoffOnNonJSON() async throws {
        let box = EndpointBackoffBox()
        let busy = Canned(result: HTTPResult(status: 200, data: Data("<html>busy</html>".utf8)))
        let first = try await ChatContext.probe(username: "a", client: busy, backoff: box)
        XCTAssertEqual(first, .notJSON)
        XCTAssertFalse(box.allows(.chatContext))
        XCTAssertTrue(box.allows(.streamURL), "退回 ajax 不受 chatvideocontext 的退避影响")
        let second = try await ChatContext.probe(username: "a", client: busy, backoff: box)
        XCTAssertEqual(second, .backedOff)
        XCTAssertEqual(StreamSourcing.first(second), .fallback)
    }

    func testProbeRequestChallengeDoesNotBackOff() async throws {
        let box = EndpointBackoffBox()
        let page = Canned(result: HTTPResult(status: 403, data: Data("<title>Just a moment...</title>".utf8)))
        let probe = try await ChatContext.probe(username: "a", client: page, backoff: box)
        XCTAssertEqual(probe, .challenge)
        XCTAssertTrue(box.allows(.chatContext))
    }

    func testProbeRequestNetworkErrorFallsBackButCancellationThrows() async throws {
        let box = EndpointBackoffBox()
        let down = Canned(error: URLError(.timedOut))
        let probe = try await ChatContext.probe(username: "a", client: down, backoff: box)
        XCTAssertEqual(probe, .failed)
        XCTAssertTrue(box.allows(.chatContext), "网络故障不退避")
        do {
            _ = try await ChatContext.probe(username: "a", client: Canned(error: URLError(.cancelled)), backoff: box)
            XCTFail("取消不当网络故障")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .cancelled)
        }
        do {
            _ = try await ChatContext.probe(username: "a", client: Canned(error: CancellationError()), backoff: box)
            XCTFail("取消不当网络故障")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
    }

    func testProbeRequestTargetsChatContext() async throws {
        final class Spy: HTTPClient, @unchecked Sendable {
            var urls: [URL] = []
            func send(_ request: URLRequest) async throws -> HTTPResult {
                if let url = request.url { urls.append(url) }
                return HTTPResult(status: 200, data: context("public", source: source))
            }
        }
        let spy = Spy()
        let probe = try await ChatContext.probe(username: "a", client: spy, backoff: nil)
        XCTAssertEqual(probe, .room(show: .publicShow, source: URL(string: source)))
        XCTAssertEqual(spy.urls, [ChatContext.url(username: "a")])
    }
}
