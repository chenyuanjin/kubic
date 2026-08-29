package com.kaodian.server.api.dto.syllabus;

import com.kaodian.server.api.dto.common.UnknownFieldException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 调整题型顺序。
 *
 * <h2>🔴 树序是有产品含义的,所以它要被显式支持</h2>
 *
 * 盲区排序在 {@code blindScore} 并列时<b>按树序决定先后</b>
 * ({@code CoverageService.blindSpots}),而「先补这几个」的前几名就是用户唯一会看的东西。
 * 换句话说:调整顺序不是排版偏好,它会直接改变产品给出的那个回答。
 *
 * <h2>必须是完整排列,不接受「只给要动的那几个」</h2>
 *
 * 少给一个和「想把它排到最后」在字节上没有区别,而前者的结果是<b>一个题型悄悄换了位置</b>,
 * 没有任何提示。所以对不上就整体拒绝({@code ORDER_NOT_A_PERMUTATION}),不做补救。
 *
 * @param groupCodes 现有题型 code 的完整排列
 */
public record GroupOrderRequest(

        @NotEmpty(message = "顺序不能为空")
        @Size(max = 200, message = "一次最多 200 个题型")
        List<@NotBlank @Size(max = 64) String> groupCodes
) {

    /** 🔴 R-07 的第二道锁,与 {@link CreateRecordRequest#rejectUnknownField} 同一条纪律。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new UnknownFieldException(name);
    }
}
