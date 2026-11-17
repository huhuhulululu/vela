// Vela 应用图标（A2 帆）：一条命令生成 macOS AppIcon 全部 10 个像素尺寸。
// 用法：swift design/icon/vela_icons.swift App/Assets.xcassets/AppIcon.appiconset
// 输出 icon_16.png / 32 / 64 / 128 / 256 / 512 / 1024（Contents.json 中 16@2x 与 32、32@2x 与 64 等复用同一像素文件）。
// 生成方式：128 及以上按目标尺寸用完整绘制直接渲染（不从 1024 缩小，细线保持锐利），512 及以上带颗粒纹理；
//          64 用完整绘制（见 sizes 注释）；32 与 16 是逐像素单独绘制的简化版。
// 坐标系：逻辑坐标原点在左上，y 向下；光源统一在左上。

import Foundation
import CoreGraphics
import CoreImage
import CoreText
import ImageIO
import UniformTypeIdentifiers

let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let ciCtx = CIContext(options: [.workingColorSpace: cs, .outputColorSpace: cs])

func C(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(colorSpace: cs, components: [CGFloat((hex >> 16) & 0xff) / 255, CGFloat((hex >> 8) & 0xff) / 255, CGFloat(hex & 0xff) / 255, a])!
}
func P(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

// MARK: 画布

final class Canvas {
    let S: Int, k: CGFloat, ctx: CGContext
    init(_ S: Int, logical: CGFloat) {
        ctx = CGContext(data: nil, width: S, height: S, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        self.S = S; k = CGFloat(S) / logical
        ctx.translateBy(x: 0, y: CGFloat(S)); ctx.scaleBy(x: k, y: -k)
        ctx.interpolationQuality = .high
    }
    var px: CGFloat { 1 / k }                       // 一个设备像素对应的逻辑长度
    func newLayer() -> Canvas { Canvas(S, logical: CGFloat(S) / k) }
    func image() -> CGImage { ctx.makeImage()! }
    func drawDevice(_ img: CGImage, alpha: CGFloat = 1, blend: CGBlendMode = .normal) {
        ctx.saveGState(); ctx.concatenate(ctx.ctm.inverted())
        ctx.setAlpha(alpha); ctx.setBlendMode(blend)
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: S, height: S)); ctx.restoreGState()
    }
    // 阴影偏移不受 CTM 影响，这里换算成逻辑单位、y 向下
    func shadow(_ dx: CGFloat, _ dy: CGFloat, _ blur: CGFloat, _ color: CGColor) {
        ctx.setShadow(offset: CGSize(width: dx * k, height: -dy * k), blur: blur * k, color: color)
    }
    // 在独立图层里绘制，再高斯模糊后合成
    func blurred(_ r: CGFloat, alpha: CGFloat = 1, blend: CGBlendMode = .normal, _ f: (Canvas) -> Void) {
        let L = newLayer(); f(L)
        let img = L.image(), sigma = max(r * k, 0.01)
        let ext = CGRect(x: 0, y: 0, width: img.width, height: img.height)
        let out = ciCtx.createCGImage(CIImage(cgImage: img).applyingGaussianBlur(sigma: sigma).cropped(to: ext), from: ext)!
        drawDevice(out, alpha: alpha, blend: blend)
    }
    func layer(alpha: CGFloat = 1, blend: CGBlendMode = .normal, _ f: (Canvas) -> Void) {
        let L = newLayer(); f(L); drawDevice(L.image(), alpha: alpha, blend: blend)
    }
}

func grad(_ stops: [(CGFloat, CGColor)]) -> CGGradient {
    CGGradient(colorsSpace: cs, colors: stops.map { $0.1 } as CFArray, locations: stops.map { $0.0 })!
}
func lin(_ ctx: CGContext, _ stops: [(CGFloat, CGColor)], _ a: CGPoint, _ b: CGPoint) {
    ctx.drawLinearGradient(grad(stops), start: a, end: b, options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
}
func rad(_ ctx: CGContext, _ stops: [(CGFloat, CGColor)], _ c: CGPoint, _ r: CGFloat, sy: CGFloat = 1) {
    ctx.saveGState(); ctx.translateBy(x: c.x, y: c.y); ctx.scaleBy(x: 1, y: sy)
    ctx.drawRadialGradient(grad(stops), startCenter: .zero, startRadius: 0, endCenter: .zero, endRadius: r, options: [.drawsBeforeStartLocation])
    ctx.restoreGState()
}

// 连续曲率圆角：iOS 7 起的 continuous corner 贝塞尔近似（直边真正平直，转角曲率连续），半径约为边长 22.5%
func squircle(_ rect: CGRect, radius: CGFloat? = nil) -> CGPath {
    let r = radius ?? rect.width * 0.225
    // 右上角一段，以 (0,0) 为角点，x 向左为正、y 向下为正
    let seg: [(CGFloat, CGFloat)] = [(1.52866471, 0), (1.08849323, 0), (0.86840689, 0), (0.66993427, 0.06549600),
                                     (0.37892862, 0.17138004), (0.17138004, 0.37892862), (0.06549600, 0.66993427),
                                     (0, 0.86840689), (0, 1.08849323), (0, 1.52866471)]
    let corners: [(CGPoint, CGFloat, CGFloat, Bool)] = [   // 角点、x 方向、y 方向、是否倒序
        (P(rect.maxX, rect.minY), -1, 1, false), (P(rect.maxX, rect.maxY), -1, -1, true),
        (P(rect.minX, rect.maxY), 1, -1, false), (P(rect.minX, rect.minY), 1, 1, true)]
    let p = CGMutablePath()
    for (i, (c, sx, sy, sw)) in corners.enumerated() {
        let pts = (sw ? Array(seg.reversed()) : seg).map { (a, b) -> CGPoint in
            P(c.x + sx * a * r, c.y + sy * b * r) }
        if i == 0 { p.move(to: pts[0]) } else { p.addLine(to: pts[0]) }
        for j in stride(from: 1, to: 10, by: 3) { p.addCurve(to: pts[j + 2], control1: pts[j], control2: pts[j + 1]) }
    }
    p.closeSubpath(); return p
}

func bez(_ p0: CGPoint, _ c1: CGPoint, _ c2: CGPoint, _ p3: CGPoint, _ t: CGFloat) -> CGPoint {
    let u = 1 - t
    return P(u*u*u*p0.x + 3*u*u*t*c1.x + 3*u*t*t*c2.x + t*t*t*p3.x,
             u*u*u*p0.y + 3*u*u*t*c1.y + 3*u*t*t*c2.y + t*t*t*p3.y)
}

// 确定性随机数（背景星点、纹理）
struct LCG { var s: UInt64
    mutating func next() -> CGFloat { s = s &* 6364136223846793005 &+ 1442695040888963407; return CGFloat((s >> 33) & 0xffffff) / CGFloat(0xffffff) }
}

func noiseImage(_ S: Int, seed: UInt64) -> CGImage {
    var g = LCG(s: seed); var bytes = [UInt8](repeating: 0, count: S * S * 4)
    for i in 0..<(S * S) { let v = UInt8(g.next() * 255); bytes[i*4] = v; bytes[i*4+1] = v; bytes[i*4+2] = v; bytes[i*4+3] = 255 }
    let ctx = CGContext(data: &bytes, width: S, height: S, bitsPerComponent: 8, bytesPerRow: S * 4, space: cs,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    return ctx.makeImage()!
}

// MARK: 底板（所有方案共用）

struct BG { var top: UInt32; var mid: UInt32; var bottom: UInt32 }

func tileRect(_ cv: Canvas) -> CGRect {
    switch cv.S {   // 小尺寸主形略放大并对齐整像素
    case 16: return CGRect(x: 1, y: 1, width: 14, height: 14)
    case 32: return CGRect(x: 2, y: 2, width: 28, height: 28)
    default: return CGRect(x: 100, y: 100, width: 824, height: 824)
    }
}

func tileBase(_ cv: Canvas, _ bg: BG) {
    let ctx = cv.ctx, r = tileRect(cv), path = squircle(r), w = r.width
    // 落在桌面上的投影：一层大而柔、一层贴边
    ctx.saveGState(); cv.shadow(0, max(w * 0.012, cv.px * 0.6), max(w * 0.032, cv.px * 1.2), C(0, 0.42))
    ctx.addPath(path); ctx.setFillColor(C(bg.bottom)); ctx.fillPath(); ctx.restoreGState()
    ctx.saveGState(); cv.shadow(0, max(w * 0.002, cv.px * 0.3), max(w * 0.004, cv.px * 0.5), C(0, 0.35))
    ctx.addPath(path); ctx.setFillColor(C(bg.bottom)); ctx.fillPath(); ctx.restoreGState()
    ctx.saveGState(); ctx.addPath(path); ctx.clip()
    lin(ctx, [(0, C(bg.top)), (0.45, C(bg.mid)), (1, C(bg.bottom))], P(r.midX, r.minY), P(r.midX, r.maxY))
    ctx.restoreGState()
}

func tileFinish(_ cv: Canvas, grain: CGFloat = 0.035) {
    let ctx = cv.ctx, r = tileRect(cv), path = squircle(r), w = r.width
    ctx.saveGState(); ctx.addPath(path); ctx.clip()
    if cv.S >= 512 && grain > 0 {   // 极轻颗粒，只在大尺寸出现
        let n = noiseImage(cv.S, seed: 7)
        cv.drawDevice(n, alpha: grain, blend: .overlay)
    }
    // 顶部微高光（玻璃面反射）
    lin(ctx, [(0, C(0xFFFFFF, 0.075)), (0.38, C(0xFFFFFF, 0.0))], P(r.midX, r.minY), P(r.midX, r.maxY))
    // 底部内阴影
    cv.blurred(w * 0.03) { L in
        L.ctx.addPath(path); L.ctx.addRect(r.insetBy(dx: -w, dy: -w)); L.ctx.setFillColor(C(0, 0.55))
        L.ctx.saveGState(); L.ctx.translateBy(x: 0, y: -w * 0.02); L.ctx.fillPath(using: .evenOdd); L.ctx.restoreGState()
    }
    // 内描边：上亮下暗
    let rim = max(w * 0.0065, cv.px * 2)
    ctx.addPath(path); ctx.setLineWidth(rim); ctx.replacePathWithStrokedPath(); ctx.clip()
    lin(ctx, [(0, C(0xFFFFFF, cv.S <= 32 ? 0.20 : 0.30)), (0.35, C(0xFFFFFF, 0.06)), (0.75, C(0xFFFFFF, 0.03)), (1, C(0xFFFFFF, 0.10))],
        P(r.midX, r.minY), P(r.midX, r.maxY))
    ctx.restoreGState()
}

// MARK: A2 帆

let goldStops: [(CGFloat, CGColor)] = [
    (0.00, C(0xFFF2DF)), (0.18, C(0xF6DAB3)), (0.42, C(0xE8B885)),
    (0.68, C(0xD39E66)), (0.88, C(0xB07B45)), (1.00, C(0x94643A))]

// 帆：左侧贴桅杆的帆前缘，右端帆角略低于中线；整体是一面吃满风的帆，轮廓自然接近向右的播放形
let sailHead = P(402, 252), sailC1 = P(566, 330), sailC2 = P(708, 456), sailClew = P(746, 580)
func sailPath(dx: CGFloat = 0, dy: CGFloat = 0) -> CGPath {
    let p = CGMutablePath()
    var t = CGAffineTransform(translationX: dx, y: dy)
    p.move(to: P(384, 276), transform: t)
    p.addQuadCurve(to: sailHead, control: P(384, 240), transform: t)
    p.addCurve(to: sailClew, control1: sailC1, control2: sailC2, transform: t)
    p.addQuadCurve(to: P(728, 610), control: P(762, 600), transform: t)
    p.addCurve(to: P(404, 716), control1: P(624, 662), control2: P(486, 706), transform: t)
    p.addQuadCurve(to: P(384, 694), control: P(384, 720), transform: t)
    p.closeSubpath(); t = .identity; return p
}

func drawMast(_ ctx: CGContext, alpha: CGFloat = 1) {
    let m = CGPath(roundedRect: CGRect(x: 374, y: 214, width: 12, height: 532), cornerWidth: 6, cornerHeight: 6, transform: nil)
    ctx.saveGState(); ctx.setAlpha(alpha); ctx.addPath(m); ctx.clip()
    lin(ctx, [(0, C(0xFFF4E4)), (0.35, C(0xE9BE8C)), (0.7, C(0xA5713F)), (1, C(0x6E4A28))], P(374, 0), P(386, 0))
    lin(ctx, [(0, C(0, 0)), (0.85, C(0, 0)), (1, C(0, 0.35))], P(0, 214), P(0, 746))
    ctx.restoreGState()
}

func drawA2(_ cv: Canvas) {
    let ctx = cv.ctx, fine = cv.S >= 512
    tileBase(cv, BG(top: 0x26262E, mid: 0x141418, bottom: 0x0A0A0C))
    let r = tileRect(cv)
    ctx.saveGState(); ctx.addPath(squircle(r)); ctx.clip()
    // 环境光：帆后方一团暖光，左上一团冷白
    rad(ctx, [(0, C(0xE8B885, 0.16)), (0.5, C(0xE8B885, 0.05)), (1, C(0xE8B885, 0))], P(560, 470), 440)
    rad(ctx, [(0, C(0xFFFFFF, 0.06)), (1, C(0xFFFFFF, 0))], P(220, 170), 460)

    ctx.translateBy(x: -44, y: 0)   // 帆 + 桅杆的视觉重心对齐图标中心
    let W: CGFloat = 760   // 水线
    // 水面：极淡的一条反光线
    ctx.saveGState(); ctx.addRect(CGRect(x: 250, y: W - (fine ? 1.2 : 2.5), width: 610, height: fine ? 2.4 : 5)); ctx.clip()
    lin(ctx, [(0, C(0xE8B885, 0)), (0.4, C(0xF3D3A8, 0.45)), (0.6, C(0xF3D3A8, 0.45)), (1, C(0xE8B885, 0))], P(250, 0), P(860, 0))
    ctx.restoreGState()
    // 倒影：压扁、渐隐、带细波纹
    cv.blurred(fine ? 5 : 7, alpha: fine ? 0.22 : 0.2) { L in
        let c = L.ctx; c.translateBy(x: -44, y: 0)
        c.saveGState(); c.translateBy(x: 0, y: W * 1.42 + 8); c.scaleBy(x: 1, y: -0.42)
        c.addPath(sailPath()); c.clip()
        lin(c, goldStops, P(384, 250), P(740, 700)); c.restoreGState()
        c.saveGState(); c.translateBy(x: 0, y: W * 1.42 + 8); c.scaleBy(x: 1, y: -0.42); drawMast(c); c.restoreGState()
        c.setBlendMode(.destinationIn)
        lin(c, [(0, C(0, 1)), (1, C(0, 0))], P(0, W + 6), P(0, W + 140))
        if fine {
            c.setBlendMode(.destinationOut); c.setStrokeColor(C(0, 1)); c.setLineCap(.round)
            for (i, y) in [W + 22, W + 40, W + 62, W + 90].enumerated() {
                c.setLineWidth(1.6 + CGFloat(i) * 0.8); c.move(to: P(300 + CGFloat(i) * 46, y)); c.addLine(to: P(680 - CGFloat(i) * 30, y)); c.strokePath()
            }
        }
    }

    let sail = sailPath()
    // 投影：帆落在背景上（光源左上 → 影子向右下）
    ctx.saveGState(); cv.shadow(16, 26, 44, C(0, 0.6)); ctx.addPath(sail); ctx.setFillColor(C(0x3A2814)); ctx.fillPath(); ctx.restoreGState()
    ctx.saveGState(); cv.shadow(3, 5, 8, C(0, 0.35)); ctx.addPath(sail); ctx.setFillColor(C(0x3A2814)); ctx.fillPath(); ctx.restoreGState()

    // 帆面
    ctx.saveGState(); ctx.addPath(sail); ctx.clip()
    lin(ctx, goldStops, P(390, 250), P(720, 720))
    // 帆腹：受光的鼓起部分
    rad(ctx, [(0, C(0xFFF8EC, 0.55)), (0.45, C(0xFFF3E0, 0.18)), (1, C(0xFFF3E0, 0))], P(500, 420), 230, sy: 0.8)
    cv.blurred(fine ? 20 : 24, alpha: 0.85, blend: .screen) { L in
        L.ctx.translateBy(x: -44, y: 0)
        let c = L.ctx; c.addPath(sail); c.clip()
        let q = CGMutablePath(); q.move(to: P(400, 300)); q.addCurve(to: P(640, 600), control1: P(520, 360), control2: P(620, 470))
        c.addPath(q); c.setStrokeColor(C(0xFFE9C8, 0.75)); c.setLineWidth(40); c.setLineCap(.round); c.strokePath()
    }
    // 贴桅杆处的折入阴影
    lin(ctx, [(0, C(0x4A2F15, 0.42)), (1, C(0x4A2F15, 0))], P(384, 0), P(432, 0))
    // 帆脚下沿的背光
    cv.blurred(28, alpha: 0.55, blend: .multiply) { L in
        L.ctx.translateBy(x: -44, y: 0)
        L.ctx.setFillColor(C(0x8A5A2E)); L.ctx.addPath(sailPath(dx: -20, dy: 70)); L.ctx.addRect(CGRect(x: 0, y: 0, width: 1024, height: 1024))
        L.ctx.fillPath(using: .evenOdd)
    }
    // 缝线：垂直于帆后缘的横裁拼缝，极细
    for t in [0.3, 0.52, 0.74] as [CGFloat] {
        let a = bez(sailHead, sailC1, sailC2, sailClew, t)
        let d = P(-0.62, 0.78), n = P(0.78, 0.62)
        let b = P(a.x + d.x * 620, a.y + d.y * 620)
        let mid = P((a.x + b.x) / 2 + n.x * 22, (a.y + b.y) / 2 + n.y * 22)
        let seam = CGMutablePath(); seam.move(to: a); seam.addQuadCurve(to: b, control: mid)
        ctx.addPath(seam); ctx.setStrokeColor(C(0x6B4524, fine ? 0.26 : 0.18)); ctx.setLineWidth(fine ? 2.4 : 6); ctx.strokePath()
        if fine {
            ctx.saveGState(); ctx.translateBy(x: 1.8, y: 1.6); ctx.addPath(seam)
            ctx.setStrokeColor(C(0xFFF4E2, 0.32)); ctx.setLineWidth(1.4); ctx.strokePath(); ctx.restoreGState()
        }
    }
    // 边缘：左上受光高光，右下背光暗边
    ctx.saveGState(); ctx.addPath(sail); ctx.setLineWidth(fine ? 9 : 18); ctx.replacePathWithStrokedPath(); ctx.clip()
    lin(ctx, [(0, C(0xFFFFFF, 0.95)), (0.4, C(0xFFF3E0, 0.4)), (0.7, C(0xFFF3E0, 0))], P(384, 240), P(700, 660))
    lin(ctx, [(0, C(0x3E2610, 0)), (0.55, C(0x3E2610, 0)), (1, C(0x3E2610, 0.45))], P(384, 240), P(740, 700))
    ctx.restoreGState()
    ctx.restoreGState()

    // 桅杆与顶端小球
    ctx.saveGState(); cv.shadow(8, 12, 16, C(0, 0.45)); ctx.addRect(CGRect(x: 374, y: 220, width: 12, height: 520)); ctx.setFillColor(C(0x6E4A28)); ctx.fillPath(); ctx.restoreGState()
    drawMast(ctx)
    ctx.saveGState(); ctx.addEllipse(in: CGRect(x: 370, y: 202, width: 20, height: 20)); ctx.clip()
    rad(ctx, [(0, C(0xFFFFFF)), (0.4, C(0xF6D9B2)), (1, C(0x9A6A3C))], P(375, 207), 22); ctx.restoreGState()

    ctx.restoreGState()
    tileFinish(cv)
}

func drawA2Small(_ cv: Canvas) {
    let ctx = cv.ctx, s16 = cv.S == 16
    tileBase(cv, BG(top: 0x2C2C34, mid: 0x18181C, bottom: 0x0C0C0E))
    let r = tileRect(cv)
    ctx.saveGState(); ctx.addPath(squircle(r)); ctx.clip()
    let p = CGMutablePath()
    // 帆前缘落在整像素线上，保证竖边锐利
    if s16 {
        p.move(to: P(5, 3)); p.addCurve(to: P(12.6, 9.6), control1: P(8.4, 4.4), control2: P(11.4, 6.8))
        p.addCurve(to: P(5, 12.4), control1: P(10.4, 11.4), control2: P(7.6, 12.2)); p.closeSubpath()
    } else {
        p.move(to: P(11, 6)); p.addCurve(to: P(24.6, 18.6), control1: P(17.2, 8.6), control2: P(23, 13.4))
        p.addQuadCurve(to: P(23.6, 19.8), control: P(25, 19.5))
        p.addCurve(to: P(11, 24.4), control1: P(19.8, 22.4), control2: P(14.6, 24.2)); p.closeSubpath()
    }
    ctx.saveGState(); cv.shadow(0.6, 0.9, 1.2, C(0, 0.55)); ctx.addPath(p); ctx.setFillColor(C(0x5A3C1F)); ctx.fillPath(); ctx.restoreGState()
    ctx.saveGState(); ctx.addPath(p); ctx.clip()
    let b = p.boundingBox
    lin(ctx, [(0, C(0xFFEFD8)), (0.45, C(0xEDC08C)), (1, C(0xB07C47))], P(b.minX, b.minY), P(b.maxX, b.maxY))
    if !s16 {   // 32：保留帆腹高光与 1px 亮边
        rad(ctx, [(0, C(0xFFFFFF, 0.35)), (1, C(0xFFFFFF, 0))], P(15, 11), 8)
        ctx.addPath(p); ctx.setLineWidth(1.6); ctx.replacePathWithStrokedPath(); ctx.clip()
        lin(ctx, [(0, C(0xFFFFFF, 0.8)), (0.5, C(0xFFFFFF, 0))], P(11, 6), P(20, 20))
    }
    ctx.restoreGState()
    // 32：1px 桅杆；16：去掉
    if !s16 {
        ctx.setFillColor(C(0xF6DAB3)); ctx.fill(CGRect(x: 10, y: 5, width: 1, height: 21))
        ctx.setFillColor(C(0xF3D3A8, 0.35)); ctx.fill(CGRect(x: 8, y: 26, width: 17, height: 1))
    }
    ctx.restoreGState()
    tileFinish(cv)
}


// MARK: 输出

func savePNG(_ img: CGImage, _ path: String) {
    let d = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(d, img, nil); CGImageDestinationFinalize(d)
}

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "."
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
for s in [1024, 512, 256, 128, 64] { let cv = Canvas(s, logical: 1024); drawA2(cv); savePNG(cv.image(), "\(out)/icon_\(s).png") }
for s in [32, 16] { let cv = Canvas(s, logical: CGFloat(s)); drawA2Small(cv); savePNG(cv.image(), "\(out)/icon_\(s).png") }
print("done")
