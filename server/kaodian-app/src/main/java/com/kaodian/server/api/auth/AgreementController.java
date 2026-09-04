package com.kaodian.server.api.auth;

import com.kaodian.server.api.dto.auth.AgreementResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协议版本与正文地址 —— {@code M0} 唯一的后端面({@code B0} §十二)。
 *
 * <h2>🔴 路径是 {@code /auth/agreements/current},<b>不是</b>某个 “public” 前缀下的位置</h2>
 *
 * {@code 接口契约} §三 有一条现行红线:<b>不许靠改路径前缀把匿名入口移出白名单统计。</b>
 * 协议端点被这么挪过一次,已撤回,理由原样成立 ——「挪出去并不会让它少一个匿名入口,
 * 只是让白名单的行数变好看,<b>而白名单存在的意义正是『匿名入口的全集在这一处数得清』</b>。
 * 一个靠改路径前缀维持的数字,下一个人查审计时会漏掉它。」
 * <p>
 * 这条红线有一行可跑的对应物,不是一句要记住的话:
 * {@code B0} §12.4 判据 ③ 是一行 grep:那个前缀在 {@code kaodian-app} 的生效代码里
 * <b>一处都不出现,包括注释</b> —— 写一句「我们没挪过去」会让那行 grep 自己命中自己。
 *
 * <h2>为什么单开一个 controller,而不是塞进 {@link AuthController}</h2>
 *
 * 它<b>不碰账号</b>:没有 {@code AccountService}、没有 {@code TokenService}、
 * 没有任何与调用者有关的输入。放进 {@code AuthController} 会让这个类的依赖表
 * 看起来像是它也需要那些东西,而「它什么都不需要」正是它能匿名的原因
 * ({@code B0} §12.4:落在 {@code app},静态配置,不进 {@code domain} 也不进 {@code auth})。
 */
@RestController
@RequestMapping("/api/v1/auth/agreements")
public class AgreementController {

    private final String version;
    private final String url;

    /**
     * 两项都是配置({@code B0} §12.4)。默认值是<b>占位</b>:
     * 正文与版本号由 {@code L-A5} 律师稿定,本仓库不替它决定内容。
     */
    public AgreementController(
            @Value("${kaodian.agreements.version:0000-00-00}") String version,
            @Value("${kaodian.agreements.url:}") String url) {
        this.version = version;
        this.url = url;
    }

    /**
     * 🔴 <b>匿名</b>(白名单第 4 行),<b>无请求参数</b>,只返回版本号与正文地址。
     *
     * <p>不返回 {@code agreed},也不返回任何用户数据 —— 理由写在
     * {@link AgreementResponse} 上:{@code U5.2} 的第三档(正文拉不到)
     * <b>在结构上不可能变成「已同意」</b>。
     *
     * <p>错误码:<b>无本端点专属码</b>。拉不到就是拉不到(网络层),
     * 端侧表现为 {@code NETWORK_TIMEOUT} —— 而那是端自己产生的值,服务端永远不返回它。
     */
    @GetMapping("/current")
    public AgreementResponse current() {
        return new AgreementResponse(version, url);
    }
}
