package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.UtteranceClassificationException;
import com.springclaw.service.agent.lifecycle.TurnContext;
import com.springclaw.service.agent.lifecycle.Utterance;

public interface UtteranceClassifier {
    Utterance classify(TurnContext context) throws UtteranceClassificationException;
}
