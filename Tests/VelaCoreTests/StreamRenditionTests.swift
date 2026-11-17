import XCTest
@testable import VelaCore

final class StreamRenditionTests: XCTestCase {
    private let master = URL(string: "https://edge.example/live/llhls.m3u8?token=abc")!

    /// 上游真实形态：音频是独立音轨，视频子列表不含声音。
    private let masterText = """
    #EXTM3U
    #EXT-X-VERSION:6
    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio_aac_96",NAME="Audio_200_4",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="chunklist_4_audio_x_llhls.m3u8"
    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio_aac_128",NAME="Audio_200_5",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="chunklist_5_audio_x_llhls.m3u8"
    #EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2",FRAME-RATE=30.000,AUDIO="audio_aac_96"
    chunklist_0_video_x_llhls.m3u8?session=s1
    #EXT-X-STREAM-INF:BANDWIDTH=5128000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",FRAME-RATE=30.000,AUDIO="audio_aac_128"
    chunklist_3_video_x_llhls.m3u8?session=s1
    """

    func testSelectsVideoAndItsAudioGroup() throws {
        let low = try XCTUnwrap(StreamRendition.select(in: masterText, master: master, maxBandwidth: 900_000))
        XCTAssertEqual(low.video.absoluteString, "https://edge.example/live/chunklist_0_video_x_llhls.m3u8?session=s1")
        XCTAssertEqual(low.audio?.absoluteString, "https://edge.example/live/chunklist_4_audio_x_llhls.m3u8")
        let top = try XCTUnwrap(StreamRendition.select(in: masterText, master: master, maxBandwidth: .infinity))
        XCTAssertEqual(top.audio?.absoluteString, "https://edge.example/live/chunklist_5_audio_x_llhls.m3u8")
        // 档位上限低于所有档时取最低档。
        XCTAssertEqual(StreamRendition.select(in: masterText, master: master, maxBandwidth: 100)?.video, low.video)
    }

    func testLocalMasterCarriesAudioGroupAndLocalAddresses() throws {
        let top = try XCTUnwrap(StreamRendition.select(in: masterText, master: master, maxBandwidth: .infinity))
        let text = try XCTUnwrap(top.localMaster(base: "http://127.0.0.1:9/abc"))
        XCTAssertTrue(text.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio_aac_128\""))
        XCTAssertTrue(text.contains("URI=\"http://127.0.0.1:9/abc/audio.m3u8\""))
        XCTAssertTrue(text.contains("AUDIO=\"audio_aac_128\""))
        XCTAssertTrue(text.contains("RESOLUTION=1920x1080"))
        XCTAssertTrue(text.contains("CODECS=\"avc1.640028,mp4a.40.2\""))
        XCTAssertTrue(text.contains("FRAME-RATE=30.000"))
        XCTAssertTrue(text.contains("\nhttp://127.0.0.1:9/abc/video.m3u8\n"))
        // 只含选中的一档，上游地址不外露。
        XCTAssertEqual(text.components(separatedBy: "#EXT-X-STREAM-INF").count, 2)
        XCTAssertFalse(text.contains("edge.example"))
        XCTAssertFalse(text.contains("audio_aac_96"))
    }

    func testMuxedSourceNeedsNoLocalMaster() throws {
        let muxed = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2"
        low.m3u8
        """
        let rendition = try XCTUnwrap(StreamRendition.select(in: muxed, master: master, maxBandwidth: .infinity))
        XCTAssertNil(rendition.audio)
        XCTAssertNil(rendition.localMaster(base: "http://127.0.0.1:9/abc"))
    }

    func testMissingAudioGroupDropsAudioReference() throws {
        let broken = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=896000,CODECS="avc1.42c01e",AUDIO="gone"
        low.m3u8
        """
        let rendition = try XCTUnwrap(StreamRendition.select(in: broken, master: master, maxBandwidth: .infinity))
        XCTAssertNil(rendition.audio)
        XCTAssertNil(HLSAttribute.value("AUDIO", in: rendition.videoAttributes))
    }

    func testAttributeParserKeepsQuotedCommas() {
        let attributes = HLSAttribute.parse("A=1,CODECS=\"x,y\",B=\"z\"")
        XCTAssertEqual(attributes.map(\.key), ["A", "CODECS", "B"])
        XCTAssertEqual(HLSAttribute.value("CODECS", in: attributes), "x,y")
    }
}
