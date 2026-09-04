package com.kaodian.server.api.syllabus;

import com.kaodian.server.api.support.CurrentSession;
import com.kaodian.server.api.support.ApiException;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.syllabus.TreeResponse;
import com.kaodian.server.coverage.CoverageReader;
import com.kaodian.server.coverage.CoverageReader.Snapshot;
import com.kaodian.server.api.dto.common.NodeDetailDto;
import com.kaodian.server.api.dto.syllabus.TreeResponse;
import com.kaodian.server.coverage.CoverageService.NodeCoverage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 骨架层的查询端点。
 *
 * <p>控制器里没有任何口径:覆盖率、五态、整块空白全部来自 {@link CoverageReader} 转发的
 * {@code CoverageService}。这里只做三件事 —— 收参数、校验 code、把领域对象翻成 DTO。
 */
@RestController
@RequestMapping("/api/syllabus")
public class SyllabusController {

    private final CoverageReader reader;

    public SyllabusController(CoverageReader reader) {
        this.reader = reader;
    }

    /**
     * 骨架树 + 覆盖,单模块整棵树一次返回(docs/technical/INDEX.md §6.4),前端不做懒加载。
     *
     * <h2>没有 {@code withCoverage=false} 这个开关</h2>
     *
     * 一棵不带覆盖的树没有任何用处 —— 用户不是来看考点目录的,是来看差集的。
     * 加这个参数只会多出一条谁都不该走的分支。
     *
     * @param subject 可选。只是一道断言:传了就必须等于当前载入的那个模块。
     *                <b>一个模块、一个学科起步</b>(决策记录 §Scope),两棵树同时冷启动被明确称为
     *                2–3 人团队的灾难 —— 所以这里不是「选科目」,是「确认没选错」
     */
    @GetMapping("/tree")
    public TreeResponse tree(CurrentSession session, @RequestParam(required = false) String subject) {
        Snapshot snapshot = reader.read(session.userId());
        String current = snapshot.syllabus().subject().code();
        if (subject != null && !subject.isBlank() && !subject.equals(current)) {
            // 回声在 ApiException 里统一截断 —— subject 是查询参数,没有 @Size 管得着它
            throw ApiException.subjectNotLoaded(subject, current);
        }
        return TreeResponse.of(snapshot.syllabus(), reader.summarize(snapshot), snapshot.groups());
    }

    /**
     * 考点详情。🔴 <b>没有讲解字段</b>(R-05)—— 见 {@link NodeDetailDto} 的字段表。
     *
     * <p>不在骨架树里的 code 返回 404,不做模糊匹配、不返回「最接近的考点」。
     * <b>宁缺毋滥</b>:猜错的考点会污染覆盖度,而覆盖度就是这个产品本身。
     */
    @GetMapping("/nodes/{code}")
    public NodeDetailDto node(CurrentSession session, @PathVariable String code) {
        NodeCoverage node = reader.read(session.userId()).node(code);
        if (node == null) {
            throw ApiException.nodeNotFound(code);
        }
        return NodeDetailDto.from(node);
    }
}
