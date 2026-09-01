package com.kaodian.server.redline;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>不留存音频 —— 而这一条的破口不在 Java 代码里,在一个容器默认值上。</b>
 *
 * <h2>为什么这条必须单独有个测试</h2>
 *
 * docs/technical/INDEX.md §5.2「不建的表」逐字写着:「<b>任何音频表 —— {@code 1.1.1.5}:ASR 失败提示重录,
 * 不留存音频</b>」。{@code RecognitionController#transcribe} 那段代码确实一个字节都没往外写。
 * <p>
 * 但 {@code POST /records/{id}/audio} 是 <b>multipart</b>(契约就是这么写的),而
 * servlet 容器处理 multipart 的默认行为是:<b>把每个 part 先写成一个临时文件</b>。
 * Spring Boot 的 {@code spring.servlet.multipart.file-size-threshold} 默认值是 <b>{@code 0}</b>,
 * 含义正是「<b>一律落盘</b>」。
 * <p>
 * 也就是说:<b>不改一行 Java 代码、不违反任何一条签名约束,每一段上传的音频都已经落在磁盘上了。</b>
 * 它不会报错,不会出现在任何 review 里,{@code ImageRetentionTest} 那两条源码扫描
 * <b>也一个字都扫不到</b> —— 因为写盘的那行代码在 Tomcat 里,不在这个仓库里。
 * <p>
 * docs/technical/INDEX.md §8.1 禁令 5 说的是同一类破口(「应用层守住了,反代可能背着你落盘」),
 * docs/technical/后端系统设计与组件接入.md §七 把这种叫「<b>配置层</b>」——比类型层弱,但只要有一条断言盯着,它就不是纪律。
 *
 * <h2>这条断言长什么样:三个数的大小关系</h2>
 *
 * <pre>
 *   RecognitionController.MAX_AUDIO_BYTES  ≤  max-file-size  ≤  file-size-threshold
 *            (代码里的拒收线)                  (容器的拒收线)        (开始落盘的线)
 * </pre>
 * 只要最右边那个不小于左边两个,<b>落盘那条路就永远走不到</b>。
 * 三个数散在两个文件里,而<b>散在两个文件里的数迟早对不上</b> ——
 * 所以不是分别断言三个具体数值,是断言它们的<b>关系</b>:
 * 有人把上限从 6 MiB 调到 20 MiB 而忘了动阈值,这里当场红。
 *
 * <h2>为什么是 {@code @SpringBootTest} 而不是读配置文件</h2>
 *
 * 读 {@code application.properties} 只能证明「那三行字写在那儿」。
 * 真正决定容器行为的是 {@link MultipartConfigElement} —— 它是 Boot 把配置翻译完之后
 * <b>真的交给容器的那个对象</b>。中间任何一步没生效(键名写错、被别的 profile 覆盖、
 * 自动装配被关掉),配置文件照样长得很对,而这里会红。
 */
@SpringBootTest
class AudioRetentionTest {

    /**
     * Boot 交给容器的那份 multipart 配置。
     *
     * <p>能注入到它本身就是一条断言的一部分:注不进来说明 multipart 自动装配整个没生效,
     * 那时容器用的是它自己的默认值,而那份默认值正是这个文件在防的东西。
     */
    @Autowired
    private MultipartConfigElement multipart;

    @Test
    @DisplayName("🔴 上传的音频永不落盘 —— file-size-threshold 必须高过 max-file-size")
    void multipartNeverSpillsToDisk() {
        assertTrue(multipart.getFileSizeThreshold() > 0, () -> """
                🔴 spring.servlet.multipart.file-size-threshold 是 %d。

                0 不是「不限制」,它是【一律先写成临时文件】—— 也就是说每一段上传的音频
                都已经落在容器的临时目录里了,而 docs/technical/INDEX.md §5.2 那一行写的是「不留存音频」。
                这条破口不在任何一行 Java 代码里,所以源码扫描一个字都扫不到。
                """.formatted(multipart.getFileSizeThreshold()));

        assertTrue(multipart.getFileSizeThreshold() >= multipart.getMaxFileSize(), () -> """
                🔴 落盘阈值 %d 低于单文件上限 %d —— 中间那一段大小的音频会被写到磁盘上。

                这两个数的关系才是红线本身,单看任何一个都看不出问题。
                改上限时把阈值一起抬上去,别只改一个。
                """.formatted(multipart.getFileSizeThreshold(), multipart.getMaxFileSize()));

        assertTrue(multipart.getFileSizeThreshold() >= multipart.getMaxRequestSize(), () -> """
                🔴 落盘阈值 %d 低于整请求上限 %d。

                单个 part 不超阈值不等于整个请求不超 —— 一次请求里带两个 part 就绕过去了。
                这个端点今天只有一个 part,而「今天只有一个」不是一条守得住的性质。
                """.formatted(multipart.getFileSizeThreshold(), multipart.getMaxRequestSize()));
    }

    @Test
    @DisplayName("🔴 代码里的音频上限不得超过容器的上限 —— 否则拒收由谁来做变成一件说不清的事")
    void applicationLimitSitsInsideContainerLimit() throws Exception {
        long codeLimit = maxAudioBytes();

        assertTrue(codeLimit <= multipart.getMaxFileSize(), () -> """
                🔴 RecognitionController.MAX_AUDIO_BYTES = %d 超过了 max-file-size = %d。

                后果不是「更宽松」,是【拒收的话由谁来说】变得不确定:超过容器上限的那一段
                会被容器直接掐掉,拿到的是一个没有错误码的通用拒绝,而不是那句
                「单条录音最长 60 秒,请切短后重录」。用户看到的提示因此取决于他超了多少。
                """.formatted(codeLimit, multipart.getMaxFileSize()));

        assertTrue(codeLimit <= multipart.getFileSizeThreshold(), () -> """
                🔴 RecognitionController.MAX_AUDIO_BYTES = %d 超过了落盘阈值 %d ——
                在被代码拒收之前,它已经被容器写到磁盘上了。
                """.formatted(codeLimit, multipart.getFileSizeThreshold()));
    }

    @Test
    @DisplayName("音频上限确实是按【60 秒 × 最宽格式】算出来的,不是随手写的一个数")
    void theAudioCeilingMatchesSixtySeconds() throws Exception {
        // 端点接受的最宽格式:48 kHz / 16 bit / 单声道 = 96,000 B/s(见 RecognitionController)。
        // 60 秒 = 5,760,000 字节。上限必须装得下它,否则一段【合法的 60 秒录音】会被字节数拦掉,
        // 而用户看到的是「太大了」,不是「太长了」—— 那句提示会把他引向错误的动作。
        long widestSixtySeconds = 48_000L * 2 * 1 * 60;
        long codeLimit = maxAudioBytes();

        assertTrue(codeLimit >= widestSixtySeconds, () -> """
                🔴 MAX_AUDIO_BYTES = %d 装不下一段合法的 60 秒录音(最宽格式 %d 字节)。

                这会让「超过 60 秒」这条真正的判据永远走不到:用户先撞上「太大了」,
                然后按着那句提示去压缩音质,而他真正该做的是把录音切短。
                """.formatted(codeLimit, widestSixtySeconds));

        // 而且不能宽太多:上限的另一半作用是给内存划线。留一倍余量给 WAV 头与附加块就够了。
        assertTrue(codeLimit <= widestSixtySeconds * 2, () -> """
                MAX_AUDIO_BYTES = %d 是 60 秒最宽格式(%d)的两倍以上 ——
                这个数已经不再是「60 秒能有多重」,而是一个随手放宽的内存预算了。
                """.formatted(codeLimit, widestSixtySeconds));
    }

    /**
     * 反射读那个包级常量。
     *
     * <p>不把它改成 {@code public} 只为让测试读到:{@code MAX_AUDIO_BYTES} 是端点自己的内部约束,
     * 放开可见性会让别处开始引用它,而<b>被引用的常量就再也不好改了</b>。
     */
    private static long maxAudioBytes() throws Exception {
        Class<?> controller = Class.forName("com.kaodian.server.api.record.RecognitionController");
        Field field = controller.getDeclaredField("MAX_AUDIO_BYTES");
        field.setAccessible(true);
        return field.getInt(null);
    }
}
