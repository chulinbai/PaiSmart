package com.yizhaoqi.smartpai.service.knowledge;

import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

public final class EvidencePool {
    private final Map<String,Evidence> evidence=new LinkedHashMap<>();
    private final Map<String,Double> fusion=new HashMap<>();
    public void add(List<Evidence> results) {
        for(var item:results.stream().sorted(Comparator.comparingInt(Evidence::rank).thenComparing(Evidence::evidenceId)).toList()) {
            evidence.putIfAbsent(item.evidenceId(),item);fusion.merge(item.evidenceId(),1d/(60+item.rank()),Double::sum);
        }
    }
    public List<Evidence> values() { return List.copyOf(evidence.values()); }
    public List<Evidence> ranked() {
        return evidence.values().stream().sorted(Comparator.<Evidence>comparingDouble(e->-fusion.get(e.evidenceId())).thenComparing(Evidence::evidenceId)).toList();
    }
}
