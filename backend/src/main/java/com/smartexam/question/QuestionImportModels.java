package com.smartexam.question;

import com.smartexam.question.QuestionModels.Type;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 题库批量导入的请求与响应模型。
 *
 * <p>导入接口刻意做成「同一份内容可以提交两次」：第一次预览、第二次真正写库。
 * 服务端不保存两次之间的中间状态，因此不存在「预览结果过期」或「会话丢失」的问题，
 * 代价只是内容多传一遍——对几十行文本来说完全可以接受。
 */
public final class QuestionImportModels {
    private QuestionImportModels() {}

    /** 单行的处理结果。 */
    public enum Outcome {
        /** 校验通过。预览时表示「可以导入」，正式导入时表示「已写入题库」。 */
        IMPORTED,
        /** 题干与题库中已有题目重复，或与本次导入的前一行重复，跳过而不算失败。 */
        SKIPPED,
        /** 格式或业务校验不通过，本行未导入。 */
        FAILED
    }

    /**
     * 导入请求。
     *
     * @param content 表格正文，CSV 或 TSV。由前端读取文件后作为文本提交，因此后端不需要处理
     *                multipart、上传大小和临时文件——文本长度上限由 {@code @Size} 直接挡住
     * @param dryRun  是否只预览。<b>缺省为 true</b>：这个默认值是刻意选的，
     *                漏传参数的后果应该是「什么都没发生」，而不是「几十道题已经进了题库」
     */
    public record ImportRequest(
            @NotBlank @Size(max = 200_000, message = "导入内容过长，请分批导入") String content,
            Boolean dryRun) {

        /** 是否为预览。不传按预览处理，写库必须显式传 {@code false}。 */
        public boolean preview() { return dryRun == null || dryRun; }
    }

    /**
     * 单行结果。
     *
     * @param line       原文行号（含表头行），与教师在 Excel 里看到的行号一致
     * @param stem       题干，解析失败时可能为 null
     * @param type       题型，解析失败时为 null
     * @param questionId 写入后的题目 ID；预览、跳过和失败时为 null
     * @param message    跳过或失败的原因；成功时为 null
     */
    public record RowResult(int line, Outcome outcome, String stem, Type type, Long questionId, String message) {}

    /**
     * 导入结果。
     *
     * <p>三个计数分开给而不是只给「成功/失败」：教师最关心的恰恰是「跳过了几道」——
     * 那通常意味着他把同一个文件导了两次，而这既不是成功也不是错误。
     *
     * @param imported 预览时是「可导入」的行数，正式导入时是实际写入的行数
     */
    public record ImportResult(boolean dryRun, int total, int imported, int skipped, int failed,
            List<RowResult> rows) {}
}
