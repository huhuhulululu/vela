import SwiftUI

struct PagerView: View {
    @ObservedObject var model: StageModel

    var body: some View {
        GeometryReader { geo in
            Group {
                if let current = model.feed.current {
                    page(current, active: true)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .clipped()
            .onAppear { model.notePointHeight(geo.size.height) }
            .onChange(of: geo.size.height) { _, newValue in
                model.notePointHeight(newValue)
            }
        }
    }

    private func page(_ room: Room, active: Bool) -> some View {
        RoomCanvas(room: room, active: active, model: model, playback: model.playback)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}