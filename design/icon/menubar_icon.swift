// Vela 菜单栏图标（A2 帆的单色剪影）：生成 18x18pt 单页矢量 PDF，作为 template 图像随菜单栏深浅色反转。
// 用法：swift design/icon/menubar_icon.swift App/Assets.xcassets/MenuBarIcon.imageset/menubar.pdf
// 同源：帆的贝塞尔轮廓与 vela_icons.swift 的 sailPath() 逐点一致（1024 逻辑坐标，y 向下），
//       只去掉底板、渐变与光影；桅杆在菜单栏尺寸下加粗到约 1pt（原 12 单位在 18pt 内不可见）。
import Foundation
import CoreGraphics

func P(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

let sailHead = P(402, 252), sailC1 = P(566, 330), sailC2 = P(708, 456), sailClew = P(746, 580)
let sail = CGMutablePath()
sail.move(to: P(384, 276))
sail.addQuadCurve(to: sailHead, control: P(384, 240))
sail.addCurve(to: sailClew, control1: sailC1, control2: sailC2)
sail.addQuadCurve(to: P(728, 610), control: P(762, 600))
sail.addCurve(to: P(404, 716), control1: P(624, 662), control2: P(486, 706))
sail.addQuadCurve(to: P(384, 694), control: P(384, 720))
sail.closeSubpath()
// 桅杆：加粗到 34 单位（约 1pt），顶端圆头；与帆之间留 ~1pt 空隙，保证 18pt 下帆与桅杆仍是两个形状
let mast = CGPath(roundedRect: CGRect(x: 346, y: 206, width: 34, height: 540), cornerWidth: 17, cornerHeight: 17, transform: nil)
let gapX: CGFloat = 412   // 帆只保留 x >= gapX 的部分

// 剪影包围盒（1024 坐标）：x 346..762，y 206..746；高 540 缩放到 16pt，居中放进 18x18pt
let box = CGRect(x: 346, y: 206, width: 416, height: 540)
let side: CGFloat = 18, k: CGFloat = 16 / box.height
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "menubar.pdf"
var media = CGRect(x: 0, y: 0, width: side, height: side)
let ctx = CGContext(URL(fileURLWithPath: out) as CFURL, mediaBox: &media, nil)!
ctx.beginPDFPage(nil)
// PDF 原点在左下，设计坐标 y 向下：翻转后居中
ctx.translateBy(x: (side - box.width * k) / 2, y: (side + box.height * k) / 2)
ctx.scaleBy(x: k, y: -k)
ctx.translateBy(x: -box.minX, y: -box.minY)
ctx.setFillColor(CGColor(gray: 0, alpha: 1))
ctx.addPath(mast); ctx.fillPath()
ctx.clip(to: CGRect(x: gapX, y: 0, width: 1024, height: 1024))
ctx.addPath(sail); ctx.fillPath()
ctx.endPDFPage(); ctx.closePDF()
print("done")
