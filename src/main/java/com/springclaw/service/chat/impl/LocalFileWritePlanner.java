package com.springclaw.service.chat.impl;

import java.util.Optional;

public interface LocalFileWritePlanner {

    Optional<LocalFileWritePlan> plan(ChatContext context);
}
