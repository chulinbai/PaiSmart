package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmResponseValidationTest {
    final ObjectMapper mapper=new ObjectMapper();
    String event(String content,String reason){return "{\"choices\":[{\"delta\":{\"content\":\""+content+"\"},\"finish_reason\":"+(reason==null?"null":"\""+reason+"\"")+"}]}";}
    @Test void requiresDoneEvenAfterStop() {
        var accumulator=new LlmResponseAccumulator(mapper);accumulator.accept(event("answer","stop"),ignored->{});
        assertThrows(LlmResponseValidator.Failure.class,()->LlmResponseValidator.validate(accumulator.result()));
        accumulator.accept("[DONE]",ignored->{});assertDoesNotThrow(()->LlmResponseValidator.validate(accumulator.result()));
    }
    @Test void usageOnlyAndActualZeroRemainDistinctFromMissing() {
        var a=new LlmResponseAccumulator(mapper);a.accept(event("answer","stop"),ignored->{});
        assertNull(a.promptTokens());assertNull(a.completionTokens());
        assertFalse(a.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}",ignored->{}));
        a.accept("[DONE]",ignored->{});assertEquals(0,a.result().promptTokens());assertEquals(0,a.result().completionTokens());
    }
    @Test void reasoningIsProgressButNeverAnAnswer() {
        var a=new LlmResponseAccumulator(mapper);
        assertTrue(a.accept("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"},\"finish_reason\":\"stop\"}]}",ignored->fail()));
        assertFalse(a.accept(": heartbeat",ignored->fail()));a.accept("[DONE]",ignored->{});
        assertEquals(LlmResponseValidator.Kind.EMPTY,assertThrows(LlmResponseValidator.Failure.class,()->LlmResponseValidator.validate(a.result())).kind());
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "{bad", "{\"error\":{\"message\":\"failed\"}}", "{\"choices\":[]}"})
    void rejectsInvalidNonStreamingResponses(String body) {assertThrows(LlmResponseValidator.Failure.class,()->LlmResponseAccumulator.parse(mapper,body));}
    @Test void assemblesToolArgumentsAndRejectsTruncation() {
        var a=new LlmResponseAccumulator(mapper);
        a.accept(tool(0,"id","{\"query\":"),ignored->{});
        assertThrows(LlmResponseValidator.Failure.class,a::result);
        a.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"hello\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",ignored->{});
        a.accept("[DONE]",ignored->{});
        var result=a.result();assertEquals("hello",result.toolCalls().get(0).arguments().get("query"));LlmResponseValidator.validate(result);
    }
    @Test void duplicateAndMissingToolIdsRejectWholeTurn() {
        var duplicate=new LlmResponseAccumulator(mapper);duplicate.accept(tool(0,"same","{}"),ignored->{});duplicate.accept(tool(1,"same","{}"),ignored->{});
        assertThrows(LlmResponseValidator.Failure.class,duplicate::result);
        var missing=new LlmResponseAccumulator(mapper);missing.accept(tool(0,"","{}"),ignored->{});assertThrows(LlmResponseValidator.Failure.class,missing::result);
    }
    @ParameterizedTest @ValueSource(strings={"unknown", "aborted", "new_reason"})
    void unknownFinishIsNeverSuccess(String reason) {
        var a=new LlmResponseAccumulator(mapper);a.accept(event("answer",reason),ignored->{});a.accept("[DONE]",ignored->{});
        var error=assertThrows(LlmResponseValidator.Failure.class,()->LlmResponseValidator.validate(a.result()));assertFalse(LlmResponseValidator.retryable(error));
    }
    @Test void safetyAndLengthAreDistinct() {
        var length=new LlmResponseAccumulator(mapper);length.accept(event("part","length"),ignored->{});length.accept("[DONE]",ignored->{});
        assertDoesNotThrow(()->LlmResponseValidator.validate(length.result()));
        var safety=new LlmResponseAccumulator(mapper);safety.accept(event("","content_filter"),ignored->{});safety.accept("[DONE]",ignored->{});
        assertEquals(LlmResponseValidator.Kind.REJECTED,assertThrows(LlmResponseValidator.Failure.class,()->LlmResponseValidator.validate(safety.result())).kind());
    }
    String tool(int index,String id,String arguments) {
        try{return mapper.writeValueAsString(Map.of("choices",List.of(Map.of("delta",Map.of("tool_calls",List.of(Map.of("index",index,"id",id,"function",Map.of("name","search_graph","arguments",arguments))))))));}
        catch(Exception e){throw new RuntimeException(e);}
    }
}
