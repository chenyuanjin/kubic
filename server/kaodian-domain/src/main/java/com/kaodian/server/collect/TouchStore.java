package com.kaodian.server.collect;

import java.util.List;

/**
 * 行为层的存储契约。
 *
 * <h2>为什么先是接口,而且现在的实现是一个文件</h2>
 *
 * docs/technical/INDEX.md §零 写明:数据层落库最早到<b>阶段 1 的 {@code 1.2.4}</b>,
 * 「阶段 0 是本地文件夹 + 纯文本」,阶段 0/1 全本地、不需要服务器。
 * 所以现在的实现就是一个 JSON 文件,没有数据库、没有 ORM、没有连接池。
 * <p>
 * 留这个接口不是为了「将来可能要换」这种空泛理由,而是因为换库这件事<b>已经排好期了</b> ——
 * 到阶段 1 换成 JDBC 时,只增加一个实现类,{@link com.kaodian.server.coverage.CoverageService}
 * 一行不用改。docs/technical/INDEX.md §2.2 说的「包之间只通过接口调用,不共享 DAO」就是这个意思。
 *
 * <h2>🔴 这个接口上没有「按内容搜索」这类方法,也永远不会有</h2>
 *
 * 因为 {@link Touch} 里根本没有内容可搜。查询维度只有考点、来源名、时间 ——
 * 「有没有、几次、多久前」,与 决策记录 §2.2 的能力边界逐字对应。
 *
 * <h2>🔴 谁传 {@code userId} 进来(B0 §4.3)</h2>
 *
 * <b>{@code app} 从鉴权上下文取出来,显式传进这些方法的参数。</b>这一层不去拿,
 * 也不认识 {@code kaodian-auth} 的任何类型 —— 那条边由 enforcer 在构建期拦着。
 *
 * <h2>三个方法名里带 {@code AcrossUsers},它们是有意的跨用户口</h2>
 *
 * {@link #findAllAcrossUsers()} / {@link #countByNodeAcrossUsers} / {@link #reassign} 三个<b>不按用户收窄</b>。
 * 名字里写出来,是因为「默认全库」正是 B0-4 要修的那个默认值:一个叫 {@code findAll()} 的方法
 * 悄悄返回别人的记录,读代码的人看不出来;叫 {@code findAllAcrossUsers()} 就看得出来。
 * 各自为什么跨用户,写在各自的方法上。
 */
public interface TouchStore {

    /** 这个用户的全部记录,按发生时间升序。 */
    List<Touch> findAll(long userId);

    /**
     * 全库记录,<b>跨用户</b>,按发生时间升序。
     *
     * <h2>🔴 今天只剩一个调用方:{@code kaodian-agent} 的工具经 {@code CoverageReader#read()}</h2>
     *
     * {@code /api/agent/**} 的租户列归 KUBI-78,B0 §5.4 明写「本轮给目标形态,不动手」。
     * 在它落地之前,agent 那条路读的仍然是全库 —— 而 {@code ApiAuthFilter} 已经让
     * 那五个端点默认打不通,所以这条路今天在 HTTP 上走不通。
     * <b>这不是「顺手留的后门」,是一处被登记着的红:B0 §3.5 判据②「延后的是动手时间不是这条判据,
     * 它现在是红的,红着是对的」。</b>
     */
    List<Touch> findAllAcrossUsers();

    /**
     * 按去重键找这个用户已经落过的那条记录。
     *
     * <p>🔴 <b>去重键按用户判,不是全局判。</b>客户端自己生成的键(UUID / 时间戳+序号)
     * 在两个人之间没有任何约定,全局判重意味着 A 的补传可能被 B 的一条老记录顶掉 ——
     * 而那正是「他记了却没记上,他不会知道」那一类失败。
     *
     * @param clientToken 客户端生成的去重键;{@code null} 或空白一律返回 {@code null} ——
     *                    「没有去重键」不是一个可以互相匹配的值(见 {@link Touch} 的构造器)
     * @return 已存在的那条;没有则 {@code null}
     */
    Touch findByClientToken(long userId, String clientToken);

    /**
     * 追加一条记录。
     *
     * <p><b>这个方法必须永不失败地把记录落下来。</b> docs/execution/INDEX.md §1.3.7:
     * 识别服务不可用时,记录动作本身永不失败 —— 先落地,标签可以之后再补。
     * 所以调用方在识别失败时应当照样写入一条 {@code nodeCode} 已由用户指定的记录,
     * 而不是把整条记录丢掉。
     *
     * <h2>🔴 幂等在这一层,不在调用方</h2>
     *
     * {@code touch.clientToken()} 非空、且已经有一条记录用着同一个键时:
     * <b>原样返回已存在的那条,不新建、不报错、不更新</b>(docs/technical/INDEX.md §6.2「{@code client_token} 幂等」)。
     * <p>
     * 放在这里而不是让调用方「先查再写」,是因为先查再写有一个窗口:
     * 离线队列补传本来就是<b>重发</b>——同一批 50 条,断线重连后再发一次,两次请求可以叠在一起。
     * 两个线程各自查到「没有」,然后各自写一条,用户看到的是记录变成了双份,
     * 而<b>覆盖度的分子里那个考点被数了两次的次数</b>正是这个产品唯一的那个数字。
     * 实现必须在自己的写锁里完成「查 + 写」。
     *
     * <p>这个方法<b>没有单独的 {@code userId} 参数</b> —— 归属就在 {@code touch.userId()} 上,
     * 再传一个只会让「两个值对不上时听谁的」变成一个要回答的问题。判重按
     * {@code (userId, clientToken)},见 {@link #findByClientToken}。
     *
     * @return 落下的那条;命中去重键时是<b>原来那条</b>(id 与 occurredAt 都是第一次的)
     */
    Touch append(Touch touch);

    /**
     * 删掉一条记录。
     *
     * <h2>这是行为层唯一的删除口,而且只删一条</h2>
     *
     * 与骨架层的删除守则(有记录就不许删,只能归档)不是一回事:
     * <b>那条守则保护的正是这里的记录</b>。用户删掉自己记错的一笔,是他对自己的行为层的处置权,
     * 没有任何理由拦着 —— 拦着的结果是他去别处找个更硬的删法(docs/technical/INDEX.md §6.1 注销那条同理)。
     *
     * <p>删除不需要通知覆盖层:覆盖度是每次请求从 {@link #findAll()} 现算的差集
     * (见 {@code CoverageReader#read}),没有一份需要跟着失效的缓存。
     * 契约里那句「触发覆盖层重算」在这个实现形态下<b>是自动成立的</b>,不是被忽略了。
     *
     * @param id 记录 id
     * <p>🔴 <b>别人的记录等于不存在。</b>拿着别人的记录 id 来删,返回的是 {@code null} 而不是
     * {@code 403} —— 与 {@code TaggingService#tagWithId} 那句「先按记录取全集再找 id」同一条:
     * 分不出「没有这条」与「这条不是你的」,本身就是不该泄露的信息。
     *
     * @return 被删掉的那条;{@code id} 不存在<b>或不属于这个用户</b>时返回 {@code null}
     *         (<b>不抛异常</b> ——「删一条不存在的记录」是调用方要分辨的情况,不是服务端的故障)
     */
    Touch delete(long userId, String id);

    /** 这个用户的记录总数。 */
    int count(long userId);

    /**
     * 某个考点上挂着几条记录,<b>跨用户</b>。
     *
     * <h2>🔴 这一处必须跨用户,收窄成单用户是错的</h2>
     *
     * 它唯一的调用方是骨架层的删除守则({@link com.kaodian.server.syllabus.NodeRecordLedger#countFor}):
     * 「这个考点上还有记录就不许删」。骨架树是<b>全进程共用的一棵</b>(阶段 0/1 只有一棵,
     * 见 {@code DomainBeans}),删掉一个节点会让<b>所有人</b>挂在它上面的记录变成孤儿。
     * 只数当前这个人的记录,他就能删掉别人正在用的考点,而且删得很干净、不报错。
     */
    int countByNodeAcrossUsers(String nodeCode);

    /**
     * 把挂在 {@code fromNodeCode} 上的记录整体改挂到 {@code toNodeCode},<b>跨用户</b>。
     *
     * <h2>🔴 这是「删除守则」给出的出路,不是一个通用的编辑接口</h2>
     *
     * 记录挂在 code 上,所以删掉一个已有记录的考点会让那些记录成为孤儿
     * (见 {@code SyllabusStore#deleteNode})。这个方法存在,是为了让「我想删掉这个考点」
     * 有一个<b>不丢数据的答复</b>:先把记录搬到另一个考点,搬完那个考点就是空的,可以删了。
     * <p>
     * 实现必须保证<b>记录总数不变</b> —— 它搬家,不扔东西。搬迁只改 {@code nodeCode} 一个字段,
     * 时间戳、来源名、做题数原样保留:「多久前」是这个产品仅有的三个维度之一,不能因为搬家而重置。
     * <p>
     * 目标 code 是否在骨架树里、是否已归档,由 {@code SyllabusStore#moveRecords} 在调用前判定 ——
     * 这个接口不认识骨架树。
     *
     * <h2>🔴 这一处也必须跨用户,理由与 {@link #countByNodeAcrossUsers} 是同一条</h2>
     *
     * 它是「这个考点我想删掉」的<b>出路</b>,而删除守则数的是全库的记录数。
     * 只搬当前这个人的,守则会看到「还剩 3 条」于是仍然不许删 —— 出路走不通;
     * 或者更糟:先按单用户搬空、守则也按单用户数,于是删掉了别人还挂着记录的考点。
     * <b>数的口径与搬的口径必须是同一个,否则这条出路会在两个方向上都出错。</b>
     *
     * @return 搬走了几条;来源上本来就没有记录时返回 0
     */
    int reassign(String fromNodeCode, String toNodeCode);

    /**
     * 删光这个用户的全部记录 —— <b>{@code M5} 注销与 {@code collect} 包的唯一交界面</b>
     * ({@code M1-记录采集与离线补传} §7.3)。
     *
     * <h2>🔴 存在的理由就是「不许写成一个循环」</h2>
     *
     * 注销最省事的实现是 {@code for (id : findAll(userId)) delete(userId, id)},而它的代价是不可见的:
     * {@link #delete} 每次<b>全量重写整个文件并落盘</b>,记了三年的用户注销一次 = N 次全量重写。
     * 换 JDBC 之后同构:N 次单行删除 + N 次索引维护。
     * 而注销是<b>用户按下之后必须完成</b>的动作 —— 超时了没有第二次机会,而半途失败留下的是
     * 一个「删了一半」的账号。这个方法把它收成<b>一次</b>写。
     *
     * <p>🔴 <b>M5 只碰这一个方法(以及 {@link RecordTagStore#deleteAllOf}),不碰 collect 包其余任何符号。</b>
     * 这条有编译期保证:{@code kaodian-auth} 不依赖 {@code kaodian-domain},所以注销的编排只能落在 {@code app}。
     * <b>什么时候调、调完账号怎么办、导出在删之前还是之后,全部归 M5 编排</b>,本接口只定签名与语义。
     *
     * <p>标签不在这里级联删:它们在另一个 store 里,由 M5 的编排各调一次
     * —— 让 {@code TouchStore} 反过来认识 {@code RecordTagStore} 会在 collect 包内部造出一条新的依赖。
     *
     * @return 删掉了几条;这个用户本来就没有记录时返回 0(<b>不抛异常</b>:注销一个从没记过东西的账号是正常的)
     */
    int deleteAllOf(long userId);
}
