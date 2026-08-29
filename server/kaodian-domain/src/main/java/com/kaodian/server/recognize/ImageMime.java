package com.kaodian.server.recognize;

/**
 * 从<b>字节</b>认图片格式 —— 不认客户端说的那个 MIME。
 *
 * <h2>为什么不信客户端</h2>
 *
 * {@code Content-Type} 是调用方随便写的。信它意味着「声称 image/jpeg 的一段任意字节」
 * 会被原样转发给模型厂商,而我们对那段字节到底是什么一无所知。
 * 认字节则不同:{@code image/jpeg} 是我们自己的常量,客户端影响不了它。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * 2026-08-28 之前这段逻辑只在 {@code RecognitionController} 里(拍照采集用)。
 * agent 支持图片之后,<b>第二个</b>入口也要认同一批格式 —— 而这不是「顺手复用一下」,
 * 是一条纪律:两处各写一份魔数判定,迟早会出现「采集端点收得下、agent 端点收不下」
 * 这种谁都解释不清的差异。<b>一个判据一处</b>,与 {@code LoginFieldLimits}
 * 「一个数三个入口共用」是同一个理由。
 *
 * <p>放在 {@code recognize} 包:它是识别链路的入口约束,而这个包本来就是
 * 「把图片这件事挡在外面」的那一层(见 {@link VisionTagger} 类注释)。
 *
 * <h2>🔴 白名单,不是黑名单</h2>
 *
 * 只认 JPEG / PNG / WebP 三种,<b>认不出就是 null,调用方必须拒绝</b>。
 * 反过来写(「不是这几种危险格式就放行」)等于把没想到的格式全放进去,
 * 而我们要转发给厂商的正是这段字节。
 */
public final class ImageMime {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String WEBP = "image/webp";

    private ImageMime() {
    }

    /**
     * 认出来就返回我们自己的常量;<b>认不出返回 null</b>。
     *
     * <p>返回 null 不是「未知格式」的温和说法,是「不要把这段字节发出去」。
     */
    public static String of(byte[] image) {
        if (image == null) {
            return null;
        }
        if (startsWith(image, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (startsWith(image, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (image.length >= 12 && ascii(image, 0, "RIFF") && ascii(image, 8, "WEBP")) {
            return WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int... magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((data[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean ascii(byte[] data, int offset, String text) {
        if (data.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((data[offset + i] & 0xFF) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
