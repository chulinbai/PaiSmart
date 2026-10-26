package com.yizhaoqi.smartpai.service;

import java.util.List;
import java.util.Map;

public interface TokenCounter {
    int countText(String text);
    int countMessages(List<Map<String, Object>> messages);
}
