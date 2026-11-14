// 从仓库根目录的 icon.png 裁出图案（去掉四周透明和白边），放进 1024×1024 透明画布中间，
// 图案占 824 像素（macOS 图标网格的大小），给 build-app.sh 生成 AppIcon.icns 用。
// 用法：swift make-icon.swift <输入.png> <输出.png>
import AppKit

let args = CommandLine.arguments
guard args.count == 3,
      let source = NSImage(contentsOfFile: args[1]),
      let tiff = source.tiffRepresentation,
      let bitmap = NSBitmapImageRep(data: tiff) else {
    FileHandle.standardError.write("读不了输入图片\n".data(using: .utf8)!)
    exit(1)
}

// 找图案的范围：不透明、也不是接近白色的像素
var minX = bitmap.pixelsWide, minY = bitmap.pixelsHigh, maxX = -1, maxY = -1
for y in 0..<bitmap.pixelsHigh {
    for x in 0..<bitmap.pixelsWide {
        guard let c = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.sRGB) else { continue }
        let nearWhite = c.redComponent > 0.96 && c.greenComponent > 0.96 && c.blueComponent > 0.96
        if c.alphaComponent > 0.05 && !nearWhite {
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
    }
}
guard maxX >= minX, maxY >= minY, let cg = bitmap.cgImage,
      let cropped = cg.cropping(to: CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)) else {
    FileHandle.standardError.write("图片里没有找到图案\n".data(using: .utf8)!)
    exit(1)
}

let canvas = 1024, content = 824.0
let scale = content / Double(max(cropped.width, cropped.height))
let w = Double(cropped.width) * scale, h = Double(cropped.height) * scale
guard let ctx = CGContext(data: nil, width: canvas, height: canvas, bitsPerComponent: 8, bytesPerRow: 0,
                          space: CGColorSpace(name: CGColorSpace.sRGB)!,
                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { exit(1) }
ctx.interpolationQuality = .high
ctx.draw(cropped, in: CGRect(x: (Double(canvas) - w) / 2, y: (Double(canvas) - h) / 2, width: w, height: h))

guard let out = ctx.makeImage(),
      let png = NSBitmapImageRep(cgImage: out).representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: args[2]))
