/**
 * 把原图送去识别一次 —— `POST /api/records/{id}/image`(docs/10 §6.2 / §八)。
 *
 * <h2>🔴 这条路上原图字节出现的次数:一次</h2>
 *
 * 本机缓存里的 Blob → {@link toBase64} 编一次 → 塞进请求体 → 请求发出去。
 * 之后那段 base64 <b>没有任何人再引用它</b>:不存全局、不进 react-query 的缓存、
 * 不写进任何状态。docs/10 §8.1 的五条禁令里有三条落在客户端这一侧 ——
 * 不上传到本项目服务端以外的任何地方、不生成外链、<b>不打进 console 的任何级别</b>。
 * 最后那条由 `tests/noRawBytesInConsole.test.ts` 钉住,不是靠这段注释。
 *
 * <h2>🔴 `R-85`:必须先有一条挂好考点的记录,才能传图</h2>
 *
 * `CreateRecordRequest.nodeCode` 是 `@NotBlank`,`CaptureService.capture` 对空 nodeCode 直接拒。
 * 于是「先拍照、让模型帮我挑考点」这条路<b>服务端今天没有入口</b> ——
 * `CaptureService.captureFromPhoto` 的 `Mounting.RECOGNIZED`(模型挑的考点直接成为主标签)
 * 至今没有 HTTP 端点。
 * <p>
 * 所以本文件<b>没有绕法</b>:{@link useRecognizePhotos} 要一个已经存在的 `recordId`,
 * 界面上的次序就是「先挑考点 → 记下 → 图跟着这条记录走」。
 * 两条出路(给 `POST /records` 开一条「待识别记录」/ 让 `/image` 能改挂主标签)
 * 都会改变别的不变式,<b>要人来选</b>,不是前端自己发明一个。见 docs/08 §四 `R-85`。
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { postJson } from './client'
import { DASHBOARD_KEY } from './queries'
import type { RawImageBytes } from '../lib/rawImageCache'
import type { SuggestTagResponse } from './types'

/* ========================================================================== */
/* 上限 —— 手抄件,对着 server 侧 dto/PhotoRecognitionRequest.java             */
/* ========================================================================== */

/**
 * 🔴 下面三个数是<b>手抄件</b>,和 `EDIT_REJECTIONS` 同一类已知重复源。
 *
 * <p>抄一遍的收益是:超限的那张图在<b>还没被编码成 base64</b> 之前就被挡下来,
 * 用户当场知道是哪一张、超了多少。让服务端去拒的话,用户先等一次几 MB 的上传,
 * 再拿到一句 400 —— 而 base64 会让那次白等的上传再涨 4/3。
 * <p>
 * 抄一遍的代价是:服务端改了这里不会有任何编译错误。所以判据写在常量名边上,
 * 改动时对着 `PhotoRecognitionRequest.MAX_PHOTOS` / `MAX_PHOTO_BYTES` / `MAX_TOTAL_BYTES` 一起看。
 */

/** 单次最多几张 —— 连拍合并成<b>一条</b>记录,不是 6 条(docs/10 §6.2 / `1.1.2.3`)。 */
export const MAX_PHOTOS = 6
/** 单张 4 MiB。它拦的不是「图太大」,是「有人拿这个入口传一个别的东西」。 */
export const MAX_PHOTO_BYTES = 4 * 1024 * 1024
/** 六张加起来 12 MiB。这也是服务端这次请求的内存预算 —— 那些字节只在内存里过一次。 */
export const MAX_TOTAL_BYTES = 12 * 1024 * 1024

/** 只收这三种。服务端会自己从字节里认一遍,这里挡的是「明显不是图的东西」。 */
export const ACCEPTED_IMAGE_MIME = ['image/jpeg', 'image/png', 'image/webp'] as const

/* ========================================================================== */
/* 编码                                                                        */
/* ========================================================================== */

/** 一次 `btoa` 喂多少字节。 */
const CHUNK = 0x8000

/**
 * Blob → 纯 base64(<b>不带 `data:` 前缀</b>)。
 *
 * <h2>为什么不用 `FileReader.readAsDataURL`</h2>
 *
 * 那个 API 出来的是 `data:image/png;base64,xxxx`,得再切一刀去掉前缀 ——
 * 而切之前那个完整的 data URL <b>就是一条能直接贴进浏览器地址栏、能塞进 `<img src>` 的东西</b>。
 * docs/10 §8.1 禁令 4 是「不做任何形式的图片分享/外链」,一个 data URL 离那件事只差一次复制。
 * 这里从头到尾没有产生过那样一个字符串。
 *
 * <h2>为什么要分块</h2>
 *
 * `String.fromCharCode(...bytes)` 在几 MB 的数组上会直接把调用栈撑爆
 * (参数个数超过引擎上限),而它<b>不会稳定复现</b>:小图能过,大图崩。
 */
export async function toBase64(blob: RawImageBytes): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let binary = ''
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}

/* ========================================================================== */
/* 请求                                                                        */
/* ========================================================================== */

/**
 * 🔴 请求体<b>只有 `photos` 一个字段</b>。
 *
 * 服务端那个 record 上挂着 `@JsonAnySetter`,多带一个字段就是 `UNKNOWN_FIELD` 400 ——
 * 那道锁是拦 `{"photos":[...],"tag":"我自己起的考点"}` 的(`R-07` 的第二道锁),
 * 前端不该去撞它。尤其不要顺手带一个 `filename` 或 `takenAt`:
 * <b>docs/10 §8.2 那张表里,服务端关于图片能知道的全部信息是一个枚举值。</b>
 */
interface PhotoRecognitionRequest {
  photos: string[]
}

/**
 * 把这一批原图送去识别。
 *
 * <h2>识别失败<b>不是</b>记录失败</h2>
 *
 * 调用这个 mutation 的时候,记录已经在库里了(见文件头 `R-85`)。
 * 所以它失败时界面要说的是「这一笔已经记下了,只是这次没认出考点」——
 * docs/13 §1.5:<b>降级方向是「少功能」,不是「少记录」</b>。
 * 把它显示成「没记下来」会让用户去重记一遍,于是库里多出一条重复记录,
 * 而覆盖率是按考点算的,重复记录正好污染「几次」那一列。
 *
 * <h2>成功之后 invalidate 一次</h2>
 *
 * 识别挂上标签会改变那个考点的五态与覆盖率,四个 GET 得重拉。
 * 和骨架层的写一样<b>不做乐观更新</b>:一次假的成功比十次诚实的失败贵得多。
 */
export function useRecognizePhotos() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (v: { recordId: string; photos: RawImageBytes[] }) => {
      const body: PhotoRecognitionRequest = { photos: await Promise.all(v.photos.map(toBase64)) }
      return postJson<SuggestTagResponse>(`/records/${encodeURIComponent(v.recordId)}/image`, body)
      // body 到此为止:没有 return 之外的引用,没有闭包把它捞出去,也没有一行日志。
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: DASHBOARD_KEY })
    },
    // 🔴 不重试。重试一次就是把同一批原图再送一遍出去 ——
    //    每多送一次,那些字节就多在网络上出现一次,而这条链路的全部纪律是「过一次即弃」。
    retry: false,
  })
}

/**
 * 这一批图能不能送 —— 能送返回 `null`,不能送返回一句给用户看的中文。
 *
 * <p>三条判据逐条对着上面那三个手抄的上限。放在这里而不是组件里,
 * 是为了让「规则只写在一处」:组件只负责把这句话摆到界面上。
 */
export function rejectionFor(photos: readonly { byteSize: number; label: string }[]): string | null {
  if (photos.length === 0) return null
  if (photos.length > MAX_PHOTOS) {
    return `一次最多送 ${MAX_PHOTOS} 张 —— 连拍是合并成一条记录,不是 ${photos.length} 条。`
  }
  const tooBig = photos.find((p) => p.byteSize > MAX_PHOTO_BYTES)
  if (tooBig !== undefined) {
    return `「${tooBig.label}」有 ${mib(tooBig.byteSize)},单张上限 ${mib(MAX_PHOTO_BYTES)}。`
  }
  const total = photos.reduce((sum, p) => sum + p.byteSize, 0)
  if (total > MAX_TOTAL_BYTES) {
    return `这几张加起来 ${mib(total)},一次上限 ${mib(MAX_TOTAL_BYTES)}。`
  }
  return null
}

export function mib(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
