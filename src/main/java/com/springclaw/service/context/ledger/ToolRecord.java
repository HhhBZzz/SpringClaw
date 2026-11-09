package com.springclaw.service.context.ledger;

/**
 * 一次工具执行的账本记录(spec 2026-11-09 §3.1)。
 *
 * @param name       工具运行时名(如 WorkspaceEditToolPack.workspaceWriteFile)
 * @param argsJson   参数 JSON(已截断至账本上限,防参数爆炸)
 * @param resultText 结果文本(String 结果已经 ToolResultPruner 剪枝;非 String 结果
 *                   为 String.valueOf 后截断)
 */
public record ToolRecord(String name, String argsJson, String resultText) {
}
