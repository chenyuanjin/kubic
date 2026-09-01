package com.kaodian.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * {@code R-59} 的防线 —— <b>把「换了手机号密钥」从一次无声的数据损坏变成启动期的一次响亮事件。</b>
 *
 * <h2>不做这件事会发生什么</h2>
 *
 * HMAC 密钥换一把,同一个手机号算出的 {@code identifier} 就变了,于是<b>每一次登录都会走
 * 「查不到 → 建号」这条分支</b>。不报错、不告警、接口全部 200:
 * 老用户各自多出一个空账号,记录留在那个再也查不到的旧账号上。
 * 等到有人发现的时候,{@code SignupLedger} 上已经多了一批假注册 ——
 * <b>而那是阶段 3 判据的数据源。</b>
 *
 * <h2>🔴 四种情形,四种处置。只有一种该拒绝启动</h2>
 *
 * <table border="1">
 *   <caption>启动期判定</caption>
 *   <tr><th>情形</th><th>处置</th><th>为什么</th></tr>
 *   <tr><td>指纹一致</td><td>放行</td><td>正常</td></tr>
 *   <tr><td>库里没盖过章</td><td><b>补盖 + WARN</b></td>
 *       <td>本机制上线之前写的老数据。没有依据说密钥变过,拒绝启动只会拦住无辜的人</td></tr>
 *   <tr><td><b>只有 HMAC 变了,AES 没变</b></td><td><b>自动换钥,零损失</b></td>
 *       <td>密文还能解出手机号 → 用新密钥重算一遍就完了。<b>这是最常见的一种,
 *           而它本来就不该让任何人半夜爬起来</b></td></tr>
 *   <tr><td>AES 也变了</td><td><b>拒绝启动</b></td>
 *       <td>手机号明文再也拿不回来。此时继续跑 = 一边接客一边毁数据</td></tr>
 * </table>
 *
 * <h2>为什么必须留一条出路</h2>
 *
 * 一个<b>没有出路的守卫,会被撞上它的人在半夜关掉</b> —— 然后它就永远关着了。
 * 所以最后一种情形给了 {@code kaodian.auth.keys.accept-key-loss=true}:
 * 它不是「跳过检查」,而是<b>「我确认这些账号找不回来了,请把它们标出来并重新开始」</b>。
 * 用的时候日志里会留下一条 ERROR 和确切的条数。
 *
 * <h2>它不是 Spring 的东西</h2>
 *
 * 与 {@code TokenService} / {@code CoverageService} 同一形态:纯逻辑,能直接 new 出来测。
 * 装配在 {@code AuthBeans} 里 —— 谁组装,谁依赖框架。
 */
public class PhoneKeyGuard {

    private static final Logger log = LoggerFactory.getLogger(PhoneKeyGuard.class);

    private final AccountStore accounts;
    private final PhoneCipher current;

    /**
     * 上一把密钥。配了才有 —— 用于「两把都换了」的<b>有计划</b>轮换。
     *
     * <p>与「HMAC 丢了但 AES 还在」那条自愈路径不同:那条不需要旧密钥
     * (旧 AES 就是当前 AES)。这一条是真正的双钥轮换,必须由人显式提供旧密钥。
     */
    private final PhoneCipher previous;

    private final boolean acceptKeyLoss;

    public PhoneKeyGuard(AccountStore accounts, PhoneCipher current,
                         PhoneCipher previous, boolean acceptKeyLoss) {
        this.accounts = accounts;
        this.current = current;
        this.previous = previous;
        this.acceptKeyLoss = acceptKeyLoss;
    }

    /**
     * 启动期跑一次。
     *
     * @return 这次做了什么
     * @throws KeyMismatchException 密钥变了而且救不回来
     */
    public Outcome check() {
        PhoneKeyFingerprint now = current.fingerprint();
        Optional<PhoneKeyFingerprint> storedOpt = accounts.keyFingerprint();
        int phones = accounts.phoneIdentityCount();

        // 一、没盖过章
        if (storedOpt.isEmpty()) {
            accounts.stampKeyFingerprint(now);
            if (phones > 0) {
                // 老数据。没有任何依据说密钥变过 —— 拒绝启动只会拦住无辜的人。
                // 但要说清楚:这一次盖的章是【假定当前密钥就是当初那把】。
                log.warn("账号数据里没有密钥指纹({} 个手机号身份),已按当前密钥补盖。"
                        + "如果这批数据其实是用另一把密钥写的,它们从现在起查不到了 —— "
                        + "请核对是否用的是同一份 auth-keys.properties。指纹:{}", phones, now);
                return new Outcome.StampedOnLegacyData(phones, now);
            }
            log.info("账号数据为空,已盖上密钥指纹 {}", now);
            return new Outcome.StampedOnEmpty(now);
        }

        PhoneKeyFingerprint stored = storedOpt.get();

        // 二、没变
        if (stored.matches(now)) {
            return new Outcome.Unchanged(now);
        }

        // 三、只有 HMAC 变了 —— AES 密文还能解开,重算一遍就完了,零损失
        if (stored.sameAesAs(now)) {
            int n = rekeyWith(current, now);
            log.warn("检测到 HMAC 密钥已更换(旧 {} → 新 {}),AES 密钥未变。"
                    + "已用密文解出手机号并重算全部 {} 条身份 —— 没有账号丢失。",
                    stored.hmacKeyId(), now.hmacKeyId(), n);
            return new Outcome.Rekeyed(n, stored, now);
        }

        // 四、AES 也变了 —— 只有拿到旧 AES 才救得回来
        if (previous != null && previous.fingerprint().matches(stored)) {
            int n = rekeyWith(previous, now);
            log.warn("检测到两把密钥都已更换(旧 {} → 新 {}),已用配置里给出的旧密钥完成换钥,共 {} 条。",
                    stored, now, n);
            return new Outcome.Rekeyed(n, stored, now);
        }

        if (phones == 0) {
            // 库里没有手机号,换钥没有任何代价。别为了一个空库拦住启动。
            accounts.stampKeyFingerprint(now);
            log.warn("密钥指纹已变({} → {}),但库里没有手机号身份,直接改盖新指纹。", stored, now);
            return new Outcome.StampedOnEmpty(now);
        }

        if (acceptKeyLoss) {
            // 🔴 这不是「跳过检查」,是「我确认这些账号找不回来了」。
            // 明确记下条数 —— 将来对不上账时,这一行是唯一的线索。
            accounts.stampKeyFingerprint(now);
            log.error("【已确认密钥丢失】{} 个账号的手机号身份从此无法匹配,它们已成为孤儿账号。"
                    + "这些用户再次登录会被当成新用户建号,SignupLedger 上会多出 {} 笔【非真实】注册 —— "
                    + "阶段 3 的判据据此需要人工扣减。旧指纹 {},新指纹 {}",
                    phones, phones, stored, now);
            return new Outcome.AcceptedLoss(phones, stored, now);
        }

        throw new KeyMismatchException(stored, now, phones);
    }

    private int rekeyWith(PhoneCipher decryptor, PhoneKeyFingerprint newFingerprint) {
        return accounts.rekeyPhones(
                old -> current.protect(decryptor.reveal(old.ciphertext())),
                newFingerprint);
    }

    /**
     * 换钥的一个副作用,记在这里而不是藏着。
     *
     * <p>{@code auth-sms.json} 与 {@code auth-sms-quota.json} 也是按手机号 HMAC 建键的,
     * 而它们<b>不参与换钥</b> —— 因为里面可能有<b>还没有账号</b>的号(正在注册的人、
     * 以及正在被锁定的刷子),那些号没有密文,算不出新的 HMAC。
     * <p>
     * 所以换钥之后:未核销的验证码作废(用户重发一次即可),
     * <b>而号码锁定与当日频控计数会被清零</b>。后者是一次真实但短暂的防线削弱,
     * 代价远小于「拿不准就拒绝启动」。换钥是罕见且有人在场的操作,不是常态。
     */
    public static String sideEffectNotice() {
        return "换钥会让未核销的验证码失效,并清空号码锁定与当日短信频控计数";
    }

    /** 判定结果。 */
    public sealed interface Outcome {

        record Unchanged(PhoneKeyFingerprint fingerprint) implements Outcome {
        }

        record StampedOnEmpty(PhoneKeyFingerprint fingerprint) implements Outcome {
        }

        record StampedOnLegacyData(int phoneCount, PhoneKeyFingerprint fingerprint) implements Outcome {
        }

        /** 自动换钥完成,<b>零账号丢失</b>。 */
        record Rekeyed(int phoneCount, PhoneKeyFingerprint from, PhoneKeyFingerprint to) implements Outcome {
        }

        /** 人为确认放弃这批账号。 */
        record AcceptedLoss(int orphanedCount, PhoneKeyFingerprint from, PhoneKeyFingerprint to)
                implements Outcome {
        }
    }

    /**
     * 密钥变了而且救不回来 —— <b>拒绝启动</b>。
     *
     * <p>消息里把三条出路都写全。一个只说「不匹配」的异常,会让人直接去找怎么关掉它。
     */
    public static class KeyMismatchException extends IllegalStateException {

        public KeyMismatchException(PhoneKeyFingerprint stored, PhoneKeyFingerprint current, int phones) {
            super("""
                    拒绝启动:手机号密钥与账号数据对不上,而且 AES 密钥也变了(R-59)。

                      数据上盖的指纹:%s
                      当前配置的指纹:%s
                      受影响的手机号身份:%d 个

                    继续跑下去不会报错 —— 每一次登录都会走「查不到 → 建号」分支,
                    老用户各自多出一个空账号,记录留在再也查不到的旧账号上,
                    而 SignupLedger 上会多出一批假注册(那是阶段 3 判据的数据源)。

                    三条出路,按可能性排序:
                      ① 最可能:你在用另一份密钥。找回原来的 ~/.kaodian/auth-keys.properties,
                         或把原来的 KAODIAN_AUTH_KEYS_PHONE_HMAC / _AES 配回来。
                         【密钥文件属于「数据」,备份数据目录时必须一起备份】
                      ② 有计划的轮换:把旧的两把配到
                         kaodian.auth.keys.phone-hmac-previous / phone-aes-previous,
                         启动时会自动换钥,零账号丢失。
                      ③ 确实丢了:kaodian.auth.keys.accept-key-loss=true。
                         这会让上面那 %d 个账号成为孤儿,不可逆。%s"""
                    .formatted(stored, current, phones, phones,
                            "\n\n    注:" + PhoneKeyGuard.sideEffectNotice() + "。"));
        }
    }
}
