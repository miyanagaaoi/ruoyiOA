/**
 * 预存签名的图片处理（PRD 8.4）。
 *
 * 这里只解决两个问题，都是**服务端做不了/不该做**的（图片还没上传）：
 *   1. 上传件的准入校验：只收 PNG / JPG，单张 ≤2MB；
 *   2. 白底自动转透明：纸质签名拍/扫出来是白底，直接存进去贴到打印件上
 *      会出现一个白色方块，压住表格线。转透明后才是"签名"而不是"一张图"。
 *
 * 为什么用 canvas 自己算，而不是引第三方库：只需要"近白 → alpha"这一条规则，
 * 引库会让这条规则变成一个黑盒，阈值和渐变都调不动。
 */

/** 上传件体积上限（PRD 8.4：PNG、JPG ≤2MB） */
export const SIGN_IMAGE_MAX_BYTES = 2 * 1024 * 1024
/** 归一化后的最大宽度：超过就等比缩小，避免 dataURL 过大拖慢上传 */
export const SIGN_IMAGE_MAX_WIDTH = 1200

/** 近白判定：三通道都 ≥ 此值 → 完全透明 */
const WHITE_HARD = 238
/** 过渡带下沿：三通道都 ≤ 此值 → 完全保留；两者之间按比例给 alpha（抗锯齿边缘不出现白边） */
const WHITE_SOFT = 196

const ALLOWED_TYPES = ['image/png', 'image/jpeg']

/**
 * 上传件的准入校验。
 *
 * @param {File} file
 * @returns {{ok: boolean, message: string}} 不合格时 message 直接可展示给用户
 */
export function validateSignImageFile(file) {
  if (!file) {
    return { ok: false, message: '没有选择文件。' }
  }
  if (ALLOWED_TYPES.indexOf(file.type) < 0) {
    return { ok: false, message: '只支持 PNG 或 JPG 图片，当前是「' + (file.type || '未知格式') + '」。' }
  }
  if (file.size > SIGN_IMAGE_MAX_BYTES) {
    const mb = (file.size / 1024 / 1024).toFixed(1)
    return { ok: false, message: '图片不能超过 2MB，当前 ' + mb + 'MB。' }
  }
  if (file.size === 0) {
    return { ok: false, message: '这个文件是空的（0 字节），请重新选择。' }
  }
  return { ok: true, message: '' }
}

/** File → dataURL */
export function readFileAsDataURL(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(new Error('读取图片失败，请重试或换一张图。'))
    reader.readAsDataURL(file)
  })
}

/** dataURL → HTMLImageElement（加载失败时给出可展示的提示） */
export function loadImage(dataURL) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('这张图片无法解析（可能已损坏或不是真正的图片）。'))
    img.src = dataURL
  })
}

/**
 * 白底 → 透明底，并等比缩到最大宽度以内。
 *
 * 规则：像素三通道都 ≥ WHITE_HARD 视为纸面 → alpha=0；
 *       都 ≤ WHITE_SOFT 视为笔迹 → 原样保留；
 *       中间按线性比例，保证笔画的抗锯齿边缘不会残留一圈白边。
 *
 * @param {string} dataURL 原始图片
 * @returns {Promise<{dataURL: string, width: number, height: number}>} 输出恒为 PNG
 */
export function whitenToTransparent(dataURL) {
  return loadImage(dataURL).then(img => {
    const naturalW = img.naturalWidth || img.width
    const naturalH = img.naturalHeight || img.height
    if (!naturalW || !naturalH) {
      throw new Error('这张图片的尺寸读不出来，请换一张。')
    }
    const scale = naturalW > SIGN_IMAGE_MAX_WIDTH ? SIGN_IMAGE_MAX_WIDTH / naturalW : 1
    const w = Math.max(1, Math.round(naturalW * scale))
    const h = Math.max(1, Math.round(naturalH * scale))

    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    // 底先铺白：原图若是带透明区的 PNG，透明处不许留下黑边
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, w, h)
    ctx.drawImage(img, 0, 0, w, h)

    let pixels
    try {
      pixels = ctx.getImageData(0, 0, w, h)
    } catch (e) {
      // 理论上不会发生（dataURL 不会污染画布），真发生就原样返回，不要让用户卡住
      return { dataURL: dataURL, width: w, height: h }
    }

    const data = pixels.data
    const span = WHITE_HARD - WHITE_SOFT
    for (let i = 0; i < data.length; i += 4) {
      const r = data[i]
      const g = data[i + 1]
      const b = data[i + 2]
      const min = Math.min(r, g, b)
      if (min >= WHITE_HARD) {
        data[i + 3] = 0
      } else if (min > WHITE_SOFT) {
        // 过渡带：越接近白越透明
        const keep = (WHITE_HARD - min) / span
        data[i + 3] = Math.round(data[i + 3] * keep)
      }
    }
    ctx.putImageData(pixels, 0, 0)

    return { dataURL: canvas.toDataURL('image/png'), width: w, height: h }
  })
}

/**
 * 上传件 → 可直接上传的透明底 PNG dataURL。校验、读取、去白底串成一步。
 *
 * @param {File} file
 * @returns {Promise<string>} dataURL
 */
export function prepareSignImageFile(file) {
  const check = validateSignImageFile(file)
  if (!check.ok) {
    return Promise.reject(new Error(check.message))
  }
  return readFileAsDataURL(file).then(whitenToTransparent).then(res => res.dataURL)
}
