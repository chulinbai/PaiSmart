package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatHandlerFinishReasonTest {

    @Test
    void classifiesNormalFinishReasons() {
        assertEquals(ChatHandler.FinishReasonKind.NORMAL, ChatHandler.classifyFinishReason("stop"));
        assertEquals(ChatHandler.FinishReasonKind.NORMAL, ChatHandler.classifyFinishReason("END_TURN"));
        assertEquals(ChatHandler.FinishReasonKind.NORMAL, ChatHandler.classifyFinishReason(" completed "));
        assertEquals(ChatHandler.FinishReasonKind.NORMAL, ChatHandler.classifyFinishReason("tool_streamed"));
    }

    @Test
    void classifiesLengthFinishReasons() {
        assertEquals(ChatHandler.FinishReasonKind.LENGTH, ChatHandler.classifyFinishReason("length"));
        assertEquals(ChatHandler.FinishReasonKind.LENGTH, ChatHandler.classifyFinishReason("MAX_TOKENS"));
        assertEquals(ChatHandler.FinishReasonKind.LENGTH, ChatHandler.classifyFinishReason("max_output_tokens"));
    }

    @Test
    void classifiesSafetyFinishReasons() {
        assertEquals(ChatHandler.FinishReasonKind.SAFETY, ChatHandler.classifyFinishReason("content_filter"));
        assertEquals(ChatHandler.FinishReasonKind.SAFETY, ChatHandler.classifyFinishReason("SAFETY"));
        assertEquals(ChatHandler.FinishReasonKind.SAFETY, ChatHandler.classifyFinishReason("blocked"));
    }

    @Test
    void classifiesToolAndUnknownFinishReasons() {
        assertEquals(ChatHandler.FinishReasonKind.TOOL, ChatHandler.classifyFinishReason("tool_calls"));
        assertEquals(ChatHandler.FinishReasonKind.TOOL, ChatHandler.classifyFinishReason("function_call"));
        assertEquals(ChatHandler.FinishReasonKind.UNKNOWN, ChatHandler.classifyFinishReason(null));
        assertEquals(ChatHandler.FinishReasonKind.UNKNOWN, ChatHandler.classifyFinishReason(""));
        assertEquals(ChatHandler.FinishReasonKind.UNKNOWN, ChatHandler.classifyFinishReason("provider_specific"));
    }
}
