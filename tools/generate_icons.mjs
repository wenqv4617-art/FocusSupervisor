/**
 * ============================================================================
 *  FocusSupervisor —— 启动图标生成器
 *
 *  用法：
 *      npm install sharp
 *      node tools/generate_icons.mjs
 *
 *  输入：art/icon-source.webp （原始设计稿，2048×1906，带透明通道）
 *  输出：
 *      app/src/main/res/mipmap-<density>/ic_launcher.png             传统方形图标
 *      app/src/main/res/mipmap-<density>/ic_launcher_round.png       传统圆形图标
 *      app/src/main/res/mipmap-<density>/ic_launcher_foreground.png  自适应图标前景
 *      app/src/main/res/mipmap-<density>/ic_launcher_background.png  自适应图标背景
 *      art/icon-master-1024.png / art/icon-512.png                   母版与商店图标
 *
 *  ---------------------------------------------------------------------------
 *  为什么不能直接把原图缩一缩丢进 mipmap
 *  ---------------------------------------------------------------------------
 *  1) 原图不是正方形：设计稿是一块 2048×1906 的圆角矩形，宽高比 1.074。
 *     安卓启动图标必须正方形，直接缩放只会得到一个被拉扁或被留白包住的图标。
 *     这里选择**居中裁到 1906×1906**：不缩放、不形变，只切掉左右各 71px 的
 *     纯背景。代价是圆角由正圆变成椭圆（水平半径 344、垂直 415），在图标尺寸下
 *     看不出来；换来的是图案零形变 —— 中心那个圆仍然是正圆。
 *
 *  2) 自适应图标（API 26+）有安全区规则：108×108dp 的画布上，系统会用任意形状
 *     的遮罩去裁，保证可见的只有中心 66dp 直径的圆，实际遮罩通常在 72dp 以内。
 *     如果把这幅「自带圆角背景」的图当成整块背景铺满 108dp，遮罩会切掉四周
 *     各 16.7%，把时针顶端和叶片尖端一起切掉。所以这里把图案放到 **74dp**
 *     —— 刚好比最大遮罩（72dp）略大一点：圆形遮罩下等于「把原图裁成圆」，
 *     方形遮罩下图案自己的圆角与遮罩圆角几乎重合，两个方向看都是满幅图标。
 *
 *  3) 前景缩到 74dp 后，四周会露出 108-74=34dp 的空档。为了不让它看起来像
 *     「一张小卡片浮在色块上」，背景层用**图案自身的边缘色向外延展**得到，
 *     与前景边界处的颜色完全一致，接缝不可见。
 * ============================================================================
 */

import sharp from 'sharp'
import { mkdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const SRC = join(ROOT, 'art', 'icon-source.webp')
const RES = join(ROOT, 'app', 'src', 'main', 'res')
const ART = join(ROOT, 'art')

/** 各密度下 1dp 对应多少像素。 */
const DENSITIES = [
  { name: 'mdpi', scale: 1 },
  { name: 'hdpi', scale: 1.5 },
  { name: 'xhdpi', scale: 2 },
  { name: 'xxhdpi', scale: 3 },
  { name: 'xxxhdpi', scale: 4 },
]

/** 传统启动图标边长（dp）。 */
const LEGACY_DP = 48

/** 自适应图标画布边长（dp）。 */
const ADAPTIVE_DP = 108

/**
 * 图案在自适应画布上占的边长（dp）。
 * 74 的选择理由见文件头注释 2)：略大于系统最大遮罩 72dp。
 */
const ARTWORK_DP = 74

// ---------------------------------------------------------------------------
// 基础工具
// ---------------------------------------------------------------------------

/**
 * 把图案四周的半透明区域用「边缘色向外延展」补满。
 *
 * 为什么需要：前景缩到 74dp 后，背景层要在 108dp 上铺满，而图案只占中间一块；
 * 直接用带透明角的图去铺，四角会漏出透明。
 *
 * 算法（两趟，都是 O(n)）：
 *   第一趟 竖向：整行全透明的行，复制最近的有内容的那一行；
 *   第二趟 横向：每一行内部，把左右两侧的透明像素用该行最外侧的不透明像素填满。
 * 对圆角矩形来说，这恰好等价于「沿形状边界向外做颜色延展」，不会产生色带。
 *
 * @param buf RGBA raw buffer，就地修改
 * @param W 宽度
 * @param H 高度
 * @param pad 取边缘颜色时向内缩进的像素数，避开抗锯齿造成的半透明边
 */
function extendEdges(buf, W, H, pad = 3) {
  const rowHasContent = new Uint8Array(H)
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      if (buf[(y * W + x) * 4 + 3] > 200) {
        rowHasContent[y] = 1
        break
      }
    }
  }

  // 第一趟：竖向补齐空行（先向下传播，再向上补齐开头）
  let last = -1
  for (let y = 0; y < H; y++) {
    if (rowHasContent[y]) {
      last = y
    } else if (last >= 0) {
      buf.copyWithin(y * W * 4, last * W * 4, (last + 1) * W * 4)
      rowHasContent[y] = 1
    }
  }
  let first = -1
  for (let y = 0; y < H; y++) {
    if (rowHasContent[y]) {
      first = y
      break
    }
  }
  for (let y = first - 1; y >= 0; y--) {
    buf.copyWithin(y * W * 4, (y + 1) * W * 4, (y + 2) * W * 4)
  }

  // 第二趟：横向补齐每一行
  for (let y = 0; y < H; y++) {
    let minX = -1
    let maxX = -1
    for (let x = 0; x < W; x++) {
      if (buf[(y * W + x) * 4 + 3] > 200) {
        if (minX < 0) minX = x
        maxX = x
      }
    }
    if (minX < 0) continue

    const sx = Math.min(minX + pad, W - 1)
    const ex = Math.max(maxX - pad, 0)
    for (let x = 0; x < minX; x++) {
      const dst = (y * W + x) * 4
      const src = (y * W + sx) * 4
      buf[dst] = buf[src]
      buf[dst + 1] = buf[src + 1]
      buf[dst + 2] = buf[src + 2]
      buf[dst + 3] = 255
    }
    for (let x = maxX + 1; x < W; x++) {
      const dst = (y * W + x) * 4
      const src = (y * W + ex) * 4
      buf[dst] = buf[src]
      buf[dst + 1] = buf[src + 1]
      buf[dst + 2] = buf[src + 2]
      buf[dst + 3] = 255
    }
    // 顺手把行内可能残留的半透明像素补成不透明
    for (let x = minX; x <= maxX; x++) {
      const i = (y * W + x) * 4
      if (buf[i + 3] === 0) {
        const src = (y * sx) * 4
        buf[i] = buf[src]
        buf[i + 1] = buf[src + 1]
        buf[i + 2] = buf[src + 2]
        buf[i + 3] = 255
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

async function main() {
  const meta = await sharp(SRC).metadata()
  const side = Math.min(meta.width, meta.height)
  const left = Math.floor((meta.width - side) / 2)

  console.log(`源图 ${meta.width}×${meta.height} → 居中裁到 ${side}×${side}（左右各去 ${left}px）`)

  // 母版：裁成正方形，不做任何缩放。
  const masterRaw = await sharp(SRC)
    .ensureAlpha()
    .extract({ left, top: 0, width: side, height: side })
    .raw()
    .toBuffer()

  // 母版的「边缘延展」版本，用来做自适应背景。
  const filledRaw = Buffer.from(masterRaw)
  extendEdges(filledRaw, side, side)

  const master = sharp(masterRaw, { raw: { width: side, height: side, channels: 4 } })

  // ---- 交付到 art/ 的母版与商店图标 -------------------------------------
  await mkdir(ART, { recursive: true })
  await sharp(masterRaw, { raw: { width: side, height: side, channels: 4 } })
    .resize(1024, 1024, { kernel: 'lanczos3' })
    .png()
    .toFile(join(ART, 'icon-master-1024.png'))
  await sharp(masterRaw, { raw: { width: side, height: side, channels: 4 } })
    .resize(512, 512, { kernel: 'lanczos3' })
    .png()
    .toFile(join(ART, 'icon-512.png'))

  for (const { name, scale } of DENSITIES) {
    const dir = join(RES, `mipmap-${name}`)
    await mkdir(dir, { recursive: true })

    // ---- 1) 传统方形图标：母版直接缩到目标尺寸 --------------------------
    const legacySize = Math.round(LEGACY_DP * scale)
    await sharp(masterRaw, { raw: { width: side, height: side, channels: 4 } })
      .resize(legacySize, legacySize, { kernel: 'lanczos3' })
      .png()
      .toFile(join(dir, 'ic_launcher.png'))

    // ---- 2) 传统圆形图标：在方形图标上乘一个抗锯齿的圆形遮罩 ------------
    const squareRaw = await sharp(masterRaw, { raw: { width: side, height: side, channels: 4 } })
      .resize(legacySize, legacySize, { kernel: 'lanczos3' })
      .raw()
      .toBuffer()
    applyCircleMask(squareRaw, legacySize)
    await sharp(squareRaw, { raw: { width: legacySize, height: legacySize, channels: 4 } })
      .png()
      .toFile(join(dir, 'ic_launcher_round.png'))

    // ---- 3) 自适应图标：前景 / 背景 -------------------------------------
    const canvasSize = Math.round(ADAPTIVE_DP * scale)
    const artworkSize = Math.round(ARTWORK_DP * scale)
    const offset = Math.round((canvasSize - artworkSize) / 2)

    // 前景：图案居中放到 74dp，四周留透明。
    await sharp(masterRaw, { raw: { width: side, height: side, channels: 4 } })
      .resize(artworkSize, artworkSize, { kernel: 'lanczos3' })
      .extend({
        top: offset,
        bottom: canvasSize - artworkSize - offset,
        left: offset,
        right: canvasSize - artworkSize - offset,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      })
      .png()
      .toFile(join(dir, 'ic_launcher_foreground.png'))

    // 背景：把「边缘延展版」图案铺满整张画布。
    // 先按同样的位置放好图案，再把四周的透明区域继续向外延展补齐，
    // 于是背景在与前景相接的那一圈上颜色完全连续。
    const bg = await sharp(filledRaw, { raw: { width: side, height: side, channels: 4 } })
      .resize(artworkSize, artworkSize, { kernel: 'lanczos3' })
      .extend({
        top: offset,
        bottom: canvasSize - artworkSize - offset,
        left: offset,
        right: canvasSize - artworkSize - offset,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      })
      .raw()
      .toBuffer()
    extendEdges(bg, canvasSize, canvasSize)
    await sharp(bg, { raw: { width: canvasSize, height: canvasSize, channels: 4 } })
      // 重模糊：把「横竖两趟延展」在四角交汇处留下的斜向硬边，以及图案
      // 边缘色被拉直后形成的横向平带，一起化成平滑的渐变场。
      // 半径取画布的 1/16 —— 足够抹平色带，又不会把边界处的颜色搬得太远。
      .blur(Math.max(1.5, canvasSize / 16))
      .png()
      .toFile(join(dir, 'ic_launcher_background.png'))

    console.log(`  mipmap-${name}: 传统 ${legacySize}px / 自适应 ${canvasSize}px（图案 ${artworkSize}px）`)
  }

  console.log('图标生成完成。')
}

/**
 * 就地乘一个圆形遮罩（直径 = 边长），边缘做 1.5px 的抗锯齿过渡。
 *
 * 用 smoothstep 而不是硬阈值：硬边的圆在低密度下会出现明显的台阶。
 */
function applyCircleMask(buf, size) {
  const c = (size - 1) / 2
  const r = size / 2
  const feather = Math.max(1, size / 48)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const dx = x - c
      const dy = y - c
      const d = Math.sqrt(dx * dx + dy * dy)
      let a = (r - d) / feather
      a = a <= 0 ? 0 : a >= 1 ? 1 : a * a * (3 - 2 * a) // smoothstep
      const i = (y * size + x) * 4 + 3
      buf[i] = Math.round(buf[i] * a)
    }
  }
}

await main()
