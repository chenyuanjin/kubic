package com.kaodian.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号的一生 —— 注册即登录、绑定、合并、注销。
 *
 * <h2>没有 {@code register()} 这个方法,以后也不会有</h2>
 *
 * docs/technical/后端系统设计与组件接入.md §1.7:契约里没有 {@code /auth/register}。
 * {@link #loginByPhone} 里那一行 {@code create(...)} <b>就是「注册」</b>。
 * <p>
 * 少一个页面是次要的,少一个「我到底注册过没有」的犹豫才是主要的 ——
 * 而这个犹豫恰好发生在用户离开成本最低的那一秒。
 *
 * <h2>合并永远不自动发生</h2>
 *
 * {@code R-33} 说的是「两端账号未打通 → 行为层被拆两半,盲区凭空多出来」,
 * 但反向的错误更严重:<b>手机号会被运营商回收,微信会被借用登录。
 * 自动合并可能把两个人的记录并到一起,而那会让覆盖度彻底失真 —— 那个指标就是整个产品。</b>
 * <p>
 * 所以合并必须:用户显式发起 → 预览会迁移多少条 → 二次确认 → 写日志 → 不可逆。
 */
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /**
     * 建号撞上竞态时的重试次数。
     *
     * <p>需要重试的唯一情形是「抢赢的那个账号在两步之间又被注销了」——
     * 注销会把 identity 摘掉,于是这个身份又空了出来。3 次足够;
     * 连续 3 次都撞上意味着数据在被高频改动,那时候<b>响亮失败好过继续猜</b>。
     */
    private static final int CREATE_ATTEMPTS = 3;

    /** 合并预览令牌的有效期。短是有意的 —— 它授权的是一件不可逆的事。 */
    private static final Duration MERGE_TOKEN_TTL = Duration.ofMinutes(5);

    private final AccountStore accounts;
    private final SignupLedger signups;
    private final TokenService tokens;
    private final PhoneCipher cipher;
    private final Clock clock;

    /**
     * 合并预览令牌。<b>放内存,不落盘</b> —— 与 {@link FileSmsCodeStore} 的选择相反,而理由正是对称的:
     * 重启导致号码锁定消失是<b>放松</b>一道防线,重启导致预览令牌失效是<b>收紧</b>。
     * 前者必须落盘,后者不必 —— 最坏结果只是用户重新预览一次。
     */
    private final Map<String, PendingMerge> pendingMerges = new ConcurrentHashMap<>();

    public AccountService(AccountStore accounts, SignupLedger signups, TokenService tokens,
                          PhoneCipher cipher, Clock clock) {
        this.accounts = accounts;
        this.signups = signups;
        this.tokens = tokens;
        this.cipher = cipher;
        this.clock = clock;
    }

    // —— 登录 ——

    /**
     * 手机号验证码通过之后的那一步 —— <b>号没见过就建号,见过就登进去</b>。
     *
     * @param passed   {@link SmsCodeService.VerifyOutcome.Passed},保证验证码已核销
     * @param referrer 从哪个入口来的,只在建号时记进 {@link SignupLedger}
     */
    public LoginResult loginByPhone(SmsCodeService.VerifyOutcome.Passed passed,
                                    String deviceLabel, String referrer) {
        Instant now = clock.instant();
        Optional<AppUser> existing = accounts.findByIdentity(IdentityType.PHONE, passed.phoneHmac());

        AppUser user;
        boolean isNew;
        if (existing.isPresent() && existing.get().isActive()) {
            user = existing.get();
            isNew = false;
        } else {
            // 走到这里的两种情况:号从没见过,或者原账号已注销(注销时 identity 已被摘掉)。
            // 后者是一次真正的新注册 —— 用户自己删掉了账号又回来了。
            Created created = createOrJoin(IdentityType.PHONE, passed.phoneHmac(),
                    cipher.protect(passed.phone()), now);
            user = created.user();
            isNew = created.freshlyCreated();
        }
        if (isNew) {
            signups.record(new SignupLedger.Entry(user.id(), now, IdentityType.PHONE, referrer));
            log.info("建账号 channel=phone userId={} 累计注册={}", user.id(), signups.totalCount());
        }
        return new LoginResult(user, tokens.issue(user.id(), TokenScope.FULL, deviceLabel), isNew);
    }

    /**
     * 微信通道登录 —— <b>阶段 2 后</b>。docs/technical/INDEX.md §7.1 那张场景表的落地。
     *
     * <h2>为什么要同时按 unionid 和 openid 查两次</h2>
     *
     * 契约那张表只写了「unionid 已存在 → 直接登录」。但 unionid <b>不是从第一天就有的</b> ——
     * 应用没绑到微信开放平台时只能拿到 openid({@link com.kaodian.server.auth.vendor.WeChatIdentity})。
     * 于是有一条静默分裂的路:
     *
     * <ol>
     *   <li>没绑开放平台时用户登录 → 用 openid 建了账号 A</li>
     *   <li>后来绑好了开放平台</li>
     *   <li><b>同一个人、同一个入口</b>再登录 → 这次拿到了 unionid → 查 unionid 查不到 → <b>建账号 B</b></li>
     * </ol>
     *
     * 用户什么都没做错,记录却被拆成两半 —— <b>那正是 {@code R-33} 本身,而且是我们自己造的</b>。
     * <p>
     * 所以这里 unionid 查不到时<b>还要按 openid 再查一次</b>:查到了就说明是同一个人,
     * 给那个账号补一行 unionid identity(「升级」),而不是建新号。
     *
     * <h2>两边查到<b>不同</b>账号时:登进 unionid 那个,并<b>建议</b>合并</h2>
     *
     * 这说明分裂已经发生过了。绝不自动合并 —— docs/technical/INDEX.md §7.1 的理由是
     * <b>「自动合并可能把两个人的记录并到一起,而那会让覆盖度彻底失真」</b>。
     * 这里只给一个一次性合并令牌,由用户显式发起。
     */
    public LoginResult loginByWeChat(com.kaodian.server.auth.vendor.WeChatIdentity wx,
                                     String deviceLabel, String referrer) {
        Instant now = clock.instant();
        WeChatResolution wxRes = resolveWeChat(wx);

        AppUser user;
        boolean isNew = false;
        PendingMerge split = null;

        if (wxRes.primary().isPresent()) {
            // 登进 primary。有 unionid 时它就是 unionid 那个 —— 那是「现在这个人」的权威身份;
            // 只有 openid 时说明当初没拿到 unionid,现在补一行即可(下面 linkQuietly),不建新号。
            user = wxRes.primary().get();
            split = suggestMergeFor(user, now, wxRes.other());
        } else {
            // 有 unionid 就拿它当第一条身份:它是跨入口同一个人的锚点,openid 换个入口就变。
            IdentityType primary = wx.hasUnionId() ? IdentityType.WX_UNION : IdentityType.WX_OPEN;
            String identifier = wx.hasUnionId() ? wx.unionid() : wx.openid();
            Created created = createOrJoin(primary, identifier, null, now);
            user = created.user();
            isNew = created.freshlyCreated();
            if (isNew) {
                signups.record(new SignupLedger.Entry(user.id(), now, primary, referrer));
                log.info("建账号 channel={} userId={} 累计注册={}",
                        primary.wireName(), user.id(), signups.totalCount());
            }
        }

        // 幂等补齐两行。openid 这一行是**将来**的保险:哪天 unionid 取不到了,
        // 或者这个人从另一个还没绑开放平台的入口进来,靠它仍然认得出是同一个人。
        linkQuietly(user.id(), IdentityType.WX_UNION, wx.hasUnionId() ? wx.unionid() : null, now);
        linkQuietly(user.id(), IdentityType.WX_OPEN, wx.openid(), now);

        return new LoginResult(user, tokens.issue(user.id(), TokenScope.FULL, deviceLabel),
                isNew, split == null ? null : split.token());
    }

    /**
     * 小程序一步登录:<b>同一次交互里同时拿到微信身份与手机号</b>。
     *
     * <h2>这是「联合登录」最顺的形态,顺到 {@code R-33} 根本不会发生</h2>
     *
     * 用户点一下,两条通道从第一天起就落在同一个账号上 —— 不需要事后引导补绑,
     * 更不需要合并(合并是不可逆的、要二次确认的、会出错的那一条)。
     *
     * <h2>🔴 两边都已存在但不是同一个账号时,登进<b>手机号</b>那个</h2>
     *
     * 不是随便挑的:docs/technical/INDEX.md §7.2 已定<b>阶段 2 只做手机号,微信在阶段 2 后</b>。
     * 所以微信那个账号必然更晚建、更可能是个刚建的空号,而记录大概率在手机号那边。
     * 登错一边的代价是用户打开就看见一个空白的盲区页 —— 而那正是这个产品的首屏。
     *
     * <p>⚠ 这条路径<b>契约里没有</b>(docs/technical/INDEX.md §6.1 那张表写于手机号快速验证未纳入考虑时)。
     * 它是新增的,不是对既有条目的改写。
     *
     * @param phone 微信已经验证过的手机号。<b>因此不再走一次短信验证码</b> ——
     *              运营商级验证正是这个接口值 0.03 元的原因
     */
    public LoginResult loginByWeChatWithPhone(com.kaodian.server.auth.vendor.WeChatIdentity wx,
                                              String phone, String deviceLabel, String referrer) {
        Instant now = clock.instant();
        String phoneHmac = cipher.hmacOf(phone);
        Optional<AppUser> byPhone = activeByIdentity(IdentityType.PHONE, phoneHmac);
        // 🔴 微信这一侧走与 loginByWeChat 完全同一段解析(resolveWeChat)——
        // 共用代码而不是各写一遍,因为「一致」如果只靠注释声明,它就会在某次改动后不再成立。
        WeChatResolution wxRes = resolveWeChat(wx);
        Optional<AppUser> byWx = wxRes.primary();

        AppUser user;
        boolean isNew = false;
        PendingMerge split = null;

        if (byPhone.isPresent()) {
            // 老用户从小程序进来 —— 不建新号,直接把微信挂上去。
            // 微信那一侧若另有账号(甚至两个:unionid 一个、openid 一个),一并纳入合并候选。
            user = byPhone.get();
            split = suggestMergeFor(user, now, byWx, wxRes.other());
        } else if (byWx.isPresent()) {
            user = byWx.get();
            split = suggestMergeFor(user, now, wxRes.other());
        } else {
            // 手机号当第一条身份:它是这个产品阶段 2 唯一的通道,也是最稳的那个锚点。
            Created created = createOrJoin(IdentityType.PHONE, phoneHmac, cipher.protect(phone), now);
            user = created.user();
            isNew = created.freshlyCreated();
            if (isNew) {
                signups.record(new SignupLedger.Entry(user.id(), now, IdentityType.PHONE, referrer));
                log.info("建账号 channel=phone(微信一步) userId={} 累计注册={}",
                        user.id(), signups.totalCount());
            }
        }

        linkPhoneQuietly(user.id(), phone, phoneHmac, now);
        linkQuietly(user.id(), IdentityType.WX_UNION, wx.hasUnionId() ? wx.unionid() : null, now);
        linkQuietly(user.id(), IdentityType.WX_OPEN, wx.openid(), now);

        return new LoginResult(user, tokens.issue(user.id(), TokenScope.FULL, deviceLabel),
                isNew, split == null ? null : split.token());
    }


    /**
     * 🔴 建号 —— <b>撞上「这个身份刚被别人建走了」时不报错,而是登进那个账号。</b>
     *
     * <h2>不这么写会怎样</h2>
     *
     * 「先 find、再 create」这两步之间没有锁。用户在登录页<b>连点两次</b>、
     * 或者前端在网络抖动时重试一次,两个请求都会查不到身份、都走进 create;
     * store 内的 {@code synchronized} 保证了不会真建出两个账号 —— 但<b>第二个会拿到
     * {@link AccountStore.IdentityTakenException}</b>,那是一个 RuntimeException,
     * 一路逃到兜底 handler,用户看到的是 <b>500</b>。
     * <p>
     * 而这条路上最糟的一格是微信一步登录:那 0.03 元<b>已经花掉了</b>,换号也成功了,
     * 用户拿到的却是一句「服务器内部错误,请把 traceId 报给我们」。
     *
     * <p>正确语义本来就是幂等的:<b>查不到就建、撞上已存在就登进去</b>。
     *
     * @return 账号,以及这一次是不是真的由我们建的({@code false} = 抢输了,登的是别人刚建的)
     */
    private Created createOrJoin(IdentityType type, String identifier,
                                 PhoneNumberSecret phoneSecret, Instant now) {
        AccountStore.IdentityTakenException last = null;
        for (int attempt = 1; attempt <= CREATE_ATTEMPTS; attempt++) {
            long id = accounts.nextUserId();
            try {
                AppUser user = accounts.create(AppUser.fresh(id, now),
                        new UserIdentity(id, type, identifier, now), phoneSecret);
                return new Created(user, true);
            } catch (AccountStore.IdentityTakenException e) {
                last = e;
                // 抢输了。那个身份现在<b>通常</b>指向某个活跃账号 —— 登进去。
                Optional<AppUser> winner = accounts.findByIdentity(type, identifier)
                        .filter(AppUser::isActive);
                if (winner.isPresent()) {
                    log.info("建号竞态:身份 {} 已由 {} 建走,本次直接登入(不是新注册)",
                            type.wireName(), winner.get().id());
                    return new Created(winner.get(), false);
                }
                // 走到这里说明:抢赢的那个账号<b>在这两步之间又被注销了</b>
                // (注销会把 identity 一并摘掉)。那么这个身份此刻又空了出来 —— 重试建号。
                // 不重试而直接抛,就是这条路上最后一个 500。
                log.info("建号竞态后目标账号已注销,身份 {} 重新空出,第 {} 次重试",
                        type.wireName(), attempt);
            }
        }
        // 连续 CREATE_ATTEMPTS 次都在同一个窗口里被抢走又注销 —— 现实中不该发生。
        // 真发生了就是数据在被高频改动,响亮失败好过继续猜。
        throw new IllegalStateException(
                "建号连续 " + CREATE_ATTEMPTS + " 次都撞上竞态:" + type.wireName(), last);
    }

    /** @param freshlyCreated 这一次是不是真的由我们建的 —— <b>只有它为 true 才记注册流水</b> */
    private record Created(AppUser user, boolean freshlyCreated) {
    }


    /**
     * 把一个微信身份解析成「登哪个账号」+「有没有第二个账号」。
     *
     * <h2>为什么必须是<b>并查比对</b>,不能是<b>回退</b></h2>
     *
     * 「unionid 查不到就查 openid」(回退)只修好了一件事:openid-only 的老账号会被认出来。
     * 但它<b>看不见分裂</b> —— 当 unionid 与 openid 指向<b>两个不同的账号</b>时,
     * 回退会在查到 unionid 的那一刻就返回,openid 那个账号连同它的记录一起消失在视野里,
     * 而用户不会收到任何合并提示。
     * <p>
     * 两条登录路径共用这一段,是为了让「一致」这件事由<b>代码结构</b>保证,
     * 而不是由两处各写一遍再在注释里声明一致 —— 后者正是上一版出的问题。
     *
     * @param primary 登哪个(有 unionid 就是它:openid 换个入口就变,unionid 不变)
     * @param other   分裂出来的另一个;没有分裂时为空
     */
    private record WeChatResolution(Optional<AppUser> primary, Optional<AppUser> other) {
    }

    private WeChatResolution resolveWeChat(com.kaodian.server.auth.vendor.WeChatIdentity wx) {
        Optional<AppUser> byUnion = wx.hasUnionId()
                ? activeByIdentity(IdentityType.WX_UNION, wx.unionid())
                : Optional.empty();
        Optional<AppUser> byOpen = activeByIdentity(IdentityType.WX_OPEN, wx.openid());

        if (byUnion.isPresent() && byOpen.isPresent()
                && byUnion.get().id() != byOpen.get().id()) {
            return new WeChatResolution(byUnion, byOpen);       // 分裂已经发生
        }
        return new WeChatResolution(byUnion.isPresent() ? byUnion : byOpen, Optional.empty());
    }

    /**
     * 登进 {@code chosen},并为「同一个人名下的其它账号」开一个合并建议。
     *
     * <h2>一次只处理一个分裂</h2>
     *
     * 一次登录最多可能牵出三个账号(手机号一个、unionid 一个、openid 一个)。
     * 但合并令牌只能给一个 —— 因为合并是<b>不可逆</b>的,一次确认只该授权一次合并。
     * 剩下的那些不会消失:<b>合并完之后的下一次登录会把下一个分裂重新报出来。</b>
     * <p>
     * 这比「一次性给三个令牌让用户挨个点」更安全:后者会让用户在一个他还没看懂的
     * 界面上连做三件不可逆的事。
     */
    private PendingMerge suggestMergeFor(AppUser chosen, Instant now, Optional<AppUser>... candidates) {
        List<AppUser> others = java.util.Arrays.stream(candidates)
                .flatMap(Optional::stream)
                .filter(a -> a.id() != chosen.id())
                .collect(java.util.stream.Collectors.toMap(AppUser::id, a -> a, (a, b) -> a,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
        if (others.isEmpty()) {
            return null;
        }
        AppUser first = others.get(0);
        log.warn("检测到账号分裂:登入 {},建议合并 {}(共 {} 个待并){}",
                chosen.id(), first.id(), others.size(),
                others.size() > 1 ? " —— 一次只处理一个,合并后下次登录会报出下一个" : "");
        return startMerge(first.id(), chosen.id(), now);
    }

    private Optional<AppUser> activeByIdentity(IdentityType type, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        return accounts.findByIdentity(type, identifier).filter(AppUser::isActive);
    }

    /**
     * 补一行 identity,<b>已被别人占着就什么都不做</b>。
     *
     * <p>这里吞掉 {@link AccountStore.IdentityTakenException} 是有意的:调用点是<b>登录成功之后</b>
     * 的补齐动作,而<b>登录不能因为一条补充身份挂不上去就失败</b>。
     * 真正的冲突(两个账号)已经在上面被识别并给出了合并建议 —— 这里再抛一次只会把用户挡在门外。
     */
    private void linkQuietly(long userId, IdentityType type, String identifier, Instant now) {
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        try {
            accounts.addIdentity(new UserIdentity(userId, type, identifier, now), null);
        } catch (AccountStore.IdentityTakenException e) {
            // 已属他人 —— 这是【预期内】的:分裂已经在上面被识别并给了合并建议。
            log.debug("补挂身份 {} 未成功(已属他人)", type.wireName());
        } catch (IllegalStateException e) {
            // 🔴 这一类不一样:它意味着这个账号自身的身份约束被撞了(比如已经挂着另一个同类身份)。
            // 那是数据形状上的意外,不是业务上的正常分支 —— 用 DEBUG 记等于把它藏起来,
            // 而它的后果是【后续的分裂识别会失效】。登录仍然放行,但这一条必须能被看见。
            log.warn("补挂身份 {} 撞上账号自身的约束,该账号的身份可能不完整:{}",
                    type.wireName(), e.getMessage());
        }
    }

    private void linkPhoneQuietly(long userId, String phone, String phoneHmac, Instant now) {
        try {
            accounts.addIdentity(new UserIdentity(userId, IdentityType.PHONE, phoneHmac, now),
                    cipher.protect(phone));
        } catch (AccountStore.IdentityTakenException | IllegalStateException e) {
            log.debug("补挂手机号未成功(已属他人或该账号已有手机号):{}", e.getMessage());
        }
    }

    // —— 绑定 ——

    /**
     * 给已登录账号绑一条 identity。
     *
     * @return {@link BindResult.Bound} 或 {@link BindResult.TakenByAnother}。
     *         <b>后者绝不自动合并</b> —— 只返回「可以合并」这个事实
     */
    public BindResult bind(long userId, IdentityType type, String identifier, String phonePlain) {
        Instant now = clock.instant();
        try {
            accounts.addIdentity(new UserIdentity(userId, type, identifier, now),
                    phonePlain == null ? null : cipher.protect(phonePlain));
            return new BindResult.Bound();
        } catch (AccountStore.IdentityTakenException e) {
            // 🔴 e.ownerUserId() 不出现在返回值里。回给客户端等于确认「这个号存在账号」,
            // 而任何人都能拿别人的号来试这一下。上层只知道「可以走合并」。
            return new BindResult.TakenByAnother(startMerge(e.ownerUserId(), userId, now));
        } catch (IllegalStateException e) {
            // 「这个账号已经绑了手机号,换号请先解绑」走这里。
            // 🔴 它必须是一个【结果】而不是一个逃逸的异常:IllegalStateException 没有任何
            // @ExceptionHandler 认领,会落到兜底那一格变成 500 ——
            // 用户拿到的是「请把 traceId 报给我们」,而他真正需要的是「先解绑旧号」。
            return new BindResult.Refused(e.getMessage());
        }
    }

    /**
     * 已登录账号绑微信 —— docs/technical/INDEX.md §7.1:<b>最顺的那条路径,产品应主动引导走这条</b>。
     *
     * <h2>为什么它不只是 {@code bind(userId, WX_UNION, unionid)} 一行</h2>
     *
     * 和 {@link #loginByWeChat} 同一个理由:<b>unionid 与 openid 都要挂上去</b>。
     * 只挂 unionid 的话,这个人从一个还没绑开放平台的入口进来时仍然认不出他 ——
     * 而那正是 {@code R-63} 描述的那条路。
     *
     * <p>冲突判定看的是<b>主身份</b>(有 unionid 就是它):它才是跨入口同一个人的锚点。
     * openid 挂不上去不算失败({@link #linkQuietly}),因为那可能只是它属于同一个人的另一个旧账号,
     * 而那种情况该走的是合并,不是把绑定整个否掉。
     */
    public BindResult bindWeChat(long userId, com.kaodian.server.auth.vendor.WeChatIdentity wx) {
        IdentityType primary = wx.hasUnionId() ? IdentityType.WX_UNION : IdentityType.WX_OPEN;
        String identifier = wx.hasUnionId() ? wx.unionid() : wx.openid();
        BindResult result = bind(userId, primary, identifier, null);
        if (result instanceof BindResult.Bound && wx.hasUnionId()) {
            linkQuietly(userId, IdentityType.WX_OPEN, wx.openid(), clock.instant());
        }
        return result;
    }

    // —— 合并 ——

    /**
     * 预览 —— <b>只读,不产生副作用</b>(docs/technical/INDEX.md §6.1)。
     *
     * @param mergeToken {@link BindResult.TakenByAnother} 里那个一次性令牌
     */
    public MergePreview previewMerge(long userId, String mergeToken) {
        PendingMerge pm = requirePending(userId, mergeToken);
        return new MergePreview(
                maskOf(pm.fromUserId()),
                maskOf(pm.toUserId()),
                movableRecordCount(pm.fromUserId()),
                pm.expiresAt());
    }

    /**
     * 执行 —— <b>不可逆</b>。
     *
     * @throws IllegalStateException 令牌不存在 / 已过期 / 已用过 / 不属于这个账号
     */
    public AccountMergeLog confirmMerge(long userId, String mergeToken) {
        PendingMerge pm = requirePending(userId, mergeToken);
        pendingMerges.remove(mergeToken);       // 一次性:先摘掉再执行,防重复提交打两次
        Instant now = clock.instant();
        AccountMergeLog merged = accounts.merge(pm.fromUserId(), pm.toUserId(),
                movableRecordCount(pm.fromUserId()), now);
        tokens.revokeAll(pm.fromUserId());      // 被并走的账号的会话必须立刻断掉
        log.info("账号合并 from={} to={} moved={}", merged.fromUserId(), merged.toUserId(),
                merged.movedRecordCount());
        return merged;
    }

    /**
     * ⚪ 会迁移多少条记录 —— <b>当前恒为 0,而且这不是 bug</b>。
     *
     * <p>行为层({@code collect} 包)现在是<b>单用户</b>的:{@code Touch} 上没有 {@code user_id},
     * 整个进程只有一份 {@code touches.json}。所以「把 A 的记录搬到 B」这件事
     * 在今天的数据模型里<b>无处可搬</b>。
     * <p>
     * 把行为层改成多租户是一次独立的、比本模块更大的改动(docs/technical/INDEX.md §5.2 的 {@code record_event.user_id}),
     * 这里不顺手做半个 —— 半个的后果是「有些记录带 user_id 有些不带」,
     * 而那会让覆盖率的分子在某些账号上凭空少一截。
     * <p>
     * 合并端点因此与微信登录一起被关在<b>阶段 2 后</b>的开关后面(docs/technical/INDEX.md §6.1)。
     */
    private int movableRecordCount(long fromUserId) {
        return 0;
    }

    private PendingMerge startMerge(long fromUserId, long toUserId, Instant now) {
        String token = UUID.randomUUID().toString().replace("-", "");
        PendingMerge pm = new PendingMerge(token, fromUserId, toUserId, now.plus(MERGE_TOKEN_TTL));
        pendingMerges.put(token, pm);
        return pm;
    }

    private PendingMerge requirePending(long userId, String mergeToken) {
        PendingMerge pm = mergeToken == null ? null : pendingMerges.get(mergeToken);
        if (pm == null || !clock.instant().isBefore(pm.expiresAt())) {
            pendingMerges.remove(mergeToken);
            throw new IllegalStateException("合并令牌不存在或已过期,请重新发起");
        }
        if (pm.toUserId() != userId) {
            throw new IllegalStateException("这个合并请求不属于当前账号");
        }
        return pm;
    }

    private String maskOf(long userId) {
        return accounts.phoneSecretOf(userId).map(PhoneNumberSecret::masked).orElse("微信账号");
    }

    // —— 注销 ——

    /**
     * 注销 —— <b>吊销全部令牌,摘掉全部 identity</b>。
     *
     * <p>🔴 服务端数据的<b>硬删时点本层不定</b>。docs/technical/INDEX.md §6.1 明确把它留给 {@code L-A5} 的律师稿:
     * {@code 1.3.1.3.2} 的原文是「注销即删除」,而「软删 → T+7 硬删」是行业惯例(防误删)——
     * 后者把 08 的一条已写死的合规判据改松了,架构不能顺手替它做这个决定。
     * <p>
     * 界面(D27)里那一格因此是<b>故意留白</b>的:不写任何具体天数。
     */
    public void deactivate(long userId) {
        accounts.deactivate(userId, clock.instant());
        int revoked = tokens.revokeAll(userId);
        log.info("账号注销 userId={} 吊销会话={} 条", userId, revoked);
    }

    // —— 读 ——

    public Optional<AppUser> find(long userId) {
        return accounts.findById(userId);
    }

    public List<UserIdentity> identitiesOf(long userId) {
        return accounts.identitiesOf(userId);
    }

    public Optional<String> maskedPhoneOf(long userId) {
        return accounts.phoneSecretOf(userId).map(PhoneNumberSecret::masked);
    }

    /** 阶段 3 的那个累计数。 */
    public int totalSignups() {
        return signups.totalCount();
    }

    // —— 内部 ——

    // 🔴 这里曾经有一个 newUserId():前缀 + UUID 的字符串 id。B0-2 §3.3 之后 id 由
    // AccountStore#nextUserId 发,起始 10001 —— 发号器在 store 里是因为「发号」与「建账号」
    // 必须是同一次原子落盘,而 service 这一层拿不到那次写。
    // 调用点仍然必须把返回值存进一个局部变量再同时用在 AppUser 与 UserIdentity 上:
    // 调两次会得到一个账号和一条挂在别处的身份,也就是一个谁也登不进去、而且不报错的账号。

    // —— 结果类型 ——

    /**
     * @param isNewAccount 这一次是不是建了新号。<b>阶段 3 的判据靠它区分新老</b>,
     *                     产品侧则据此决定要不要走引导
     * @param splitMergeToken 🔴 <b>登录成功的同时发现这个人在库里有两个账号</b>时的一次性合并令牌;
     *                        没有分裂则为 {@code null}。
     *                        <b>它只是「可以合并」这个事实,不代表已经合并</b> —— 合并永远由用户显式发起
     */
    public record LoginResult(AppUser user, IssuedToken token, boolean isNewAccount,
                              String splitMergeToken) {

        /** 手机号通道不会产生分裂(手机号就是唯一锚点),用这个构造器。 */
        public LoginResult(AppUser user, IssuedToken token, boolean isNewAccount) {
            this(user, token, isNewAccount, null);
        }
    }

    public sealed interface BindResult {

        record Bound() implements BindResult {
        }

        /**
         * 绑不了,而且不是「已属他人」——比如这个账号已经有手机号了。
         *
         * <p>它与 {@link TakenByAnother} 的区别是<b>没有合并这条出路</b>:
         * 要换号得先解绑,那是用户自己账号里的事,不涉及第二个账号。
         */
        record Refused(String reason) implements BindResult {
        }

        /**
         * 目标身份已属他人。<b>不自动合并</b>,只给出可以合并这个事实与一次性令牌。
         *
         * <p>注意 {@code pending} 里带着对方的 userId —— <b>接口层只能取 {@code token},
         * 不能把 {@code fromUserId} 放进响应</b>。
         */
        record TakenByAnother(PendingMerge pending) implements BindResult {
        }
    }

    /**
     * 一次待确认的合并。
     *
     * @param fromUserId 会被并走并注销的那个
     * @param toUserId   留下来的那个(= 当前登录的账号)
     */
    public record PendingMerge(String token, long fromUserId, long toUserId, Instant expiresAt) {
    }

    /**
     * 预览结果。
     *
     * @param movedRecordCount ⚪ 当前恒为 0,见 {@link #movableRecordCount}
     */
    public record MergePreview(String fromLabel, String toLabel, int movedRecordCount, Instant expiresAt) {
    }
}
