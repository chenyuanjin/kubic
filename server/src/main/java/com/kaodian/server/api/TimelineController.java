package com.kaodian.server.api;

import com.kaodian.server.api.dto.TimelineItemDto;
import com.kaodian.server.api.dto.TimelineResponse;
import com.kaodian.server.collect.Touch;
import com.kaodian.server.collect.TouchStore;
import com.kaodian.server.syllabus.SyllabusSource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * 时间线 —— 「我这段时间碰过些什么」。
 *
 * <h2>🔴 这个端点最容易被要求长出内容字段,所以它的形状要写死</h2>
 *
 * 一屏按时间倒序排列的记录,看上去就是一本笔记本;而笔记本的下一个需求必然是
 * 「能不能把当时那段话/那张图也存下来」。答复在 {@link TimelineItemDto} 的字段表里:
 * <b>只有来源名、时间、方式、考点</b>。上游的 {@link Touch} 结构上就没有内容字段
 * (01 §2.2 不碰内容),这里也不会凭空长出来。
 *
 * <h2>直接依赖 TouchStore,不走覆盖层</h2>
 *
 * 时间线要的是原始记录本身,不是差集结果。硬拉一遍 {@code compute} 只为拿到同一批记录,
 * 是白算一次全树覆盖。
 */
@RestController
public class TimelineController {

    private final TouchStore store;
    private final SyllabusSource syllabus;

    public TimelineController(TouchStore store, SyllabusSource syllabus) {
        this.store = store;
        this.syllabus = syllabus;
    }

    /**
     * @param limit 最近多少条。默认 50,上限 200。
     *              cursor 分页是阶段 2 的事(docs/10 §6.2)—— 现在全量在一个本地 JSON 文件里,
     *              先欠着游标编码/失效/兼容那一整套
     */
    @GetMapping("/api/timeline")
    public TimelineResponse timeline(
            @RequestParam(defaultValue = "50")
            @Min(value = 1, message = "至少要 1 条")
            @Max(value = 200, message = "一次最多 200 条")
            int limit) {

        List<Touch> all = store.findAll();
        // 树只问一次:同一屏上的记录必须用同一棵树翻译考点名,否则中途一次改名会让上下两条对不上
        var syllabusNow = syllabus.current();
        List<TimelineItemDto> items = all.stream()
                // store 按发生时间升序给,时间线要倒序 —— 最近发生的在最上面
                .sorted(Comparator.comparing(Touch::occurredAt).reversed())
                .limit(limit)
                .map(t -> TimelineItemDto.from(t, syllabusNow))
                .toList();

        return new TimelineResponse(all.size(), items.size(), items);
    }
}
