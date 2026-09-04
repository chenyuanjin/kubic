package com.kaodian.server.api.support;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * 启动期断言:{@code file-size-threshold} 必须<b>严格大于</b> {@code max-file-size}。
 *
 * <h2>🔴 这个不等式就是「音频永不落磁盘」的全部装置</h2>
 *
 * 全工程唯一的 multipart 端点是 {@code POST /api/v1/records/{id}/audio}。
 * Spring 的规则是:一个 part 超过 {@code file-size-threshold} 才写成临时文件。
 * 所以只要 {@code threshold > max}, 任何<b>能通过 {@code max} 的请求体都在阈值以下</b>,
 * 全程留在内存 —— 而超过 {@code max} 的那些在写盘之前就被拒了。
 *
 * <h2>为什么要有这么一个类:今天没有任何东西守着这个不等式</h2>
 *
 * 下一个人为了支持更长的录音,把 {@code max-file-size} 从 {@code 6MB} 调到 {@code 10MB} ——
 * 音频<b>当场开始落磁盘临时文件</b>,而:
 *
 * <ul>
 *   <li>接口全部 {@code 200}</li>
 *   <li>没有一行日志提到它</li>
 *   <li>没有一条测试会红 —— 因为「不落盘」这件事此前只写在配置文件的注释里</li>
 * </ul>
 *
 * 结果是磁盘上出现了一份用户的录音,而<b>没有任何人会知道</b>。
 * 这正是「不与供应商暂存」那条红线在音频上的同构失败,而且是无声的那一种。
 *
 * <h2>为什么是拒绝启动而不是一条 WARN</h2>
 *
 * WARN 的意思是「放行,但记一笔」—— 而这里放行的后果是一份留在磁盘上的用户录音。
 * 形态照抄手机号密钥那道守卫:<b>有守卫、有出路、出路留痕</b>。
 * 这里的出路是「两个数一起调」,而不是「把这个类删掉」。
 */
@Component
public class MultipartMemoryGuard {

    /**
     * 🔴 读的是<b>配置键本身</b>,不是 Spring 绑好的那个 properties 对象。
     *
     * <p>默认值刻意抄的是 Spring 自己的默认值({@code threshold=0B} / {@code max=1MB}):
     * 有人把 {@code application.properties} 里那两行删掉时,这里拿到的就是容器真正会用的那两个数,
     * 而 {@code 0B > 1MB} 不成立 —— <b>启动当场失败</b>。
     * 若在这里填一对「看起来对」的默认值,删掉配置反而会静默通过,而容器已经开始落临时文件了。
     */
    private final DataSize threshold;
    private final DataSize max;

    public MultipartMemoryGuard(
            @Value("${spring.servlet.multipart.file-size-threshold:0B}") DataSize threshold,
            @Value("${spring.servlet.multipart.max-file-size:1MB}") DataSize max) {
        this.threshold = threshold;
        this.max = max;
    }

    @PostConstruct
    void thresholdMustExceedMaxFileSize() {
        if (threshold.compareTo(max) > 0) {
            return;
        }
        throw new IllegalStateException("""
                spring.servlet.multipart.file-size-threshold(%s)必须【严格大于】max-file-size(%s)。

                不成立时上传的音频会落到容器的临时目录 —— 而「服务端不留存音频」这条红线
                靠的就是这个不等式,不是靠任何一行 Java 代码。它失守时接口全部 200、
                日志一条没有、测试全绿,只有磁盘上多了一份用户的录音。

                要放宽录音时长,调的是 max-file-size 和 file-size-threshold 【两个】,
                外加 kaodian.audio.max-seconds —— 不是只调一个。"""
                .formatted(threshold, max));
    }
}
