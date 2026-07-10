package com.springclaw.service.chat.impl;

public record LocalFileWritePlan(String relativePath,
                                 String content,
                                 boolean overwrite,
                                 String reason) {
}
