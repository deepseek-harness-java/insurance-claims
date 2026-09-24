package cn.xiaofuge.i.app;

import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/** 理赔 REST API */
@RestController
public class IController {

    private final IStore store;

    public IController(IStore store) { this.store = store; }

    @GetMapping("/api/policies")
    public Map<String, Object> policies(@RequestParam(required = false) String holder) {
        List<IStore.Policy> list = store.policies.stream()
                .filter(x -> holder == null || holder.isBlank() || x.holder.contains(holder))
                .collect(Collectors.toList());
        return Map.of("code", 0, "data", list);
    }

    @GetMapping("/api/claims")
    public Map<String, Object> claims(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String holder) {
        List<IStore.Claim> list = store.claims.stream()
                .filter(x -> status == null || status.isBlank() || x.status.equals(status))
                .filter(x -> holder == null || holder.isBlank() || x.holder.contains(holder))
                .collect(Collectors.toList());
        return Map.of("code", 0, "data", list);
    }

    @PostMapping("/api/check")
    public Map<String, Object> check(@RequestBody Map<String, Object> body) {
        String policyId = String.valueOf(body.getOrDefault("policyId", ""));
        double amount = IStoreHelper.dbl(body.get("amount"));
        String reason = String.valueOf(body.getOrDefault("reason", ""));
        return Map.of("code", 0, "data", store.check(policyId, amount, reason));
    }

    @PostMapping("/api/claim")
    public Map<String, Object> file(@RequestBody Map<String, Object> body) {
        String policyId = String.valueOf(body.getOrDefault("policyId", ""));
        double amount = IStoreHelper.dbl(body.get("amount"));
        String reason = String.valueOf(body.getOrDefault("reason", ""));
        return Map.of("code", 0, "data", store.file(policyId, amount, reason));
    }

    @PostMapping("/api/adjust")
    public Map<String, Object> adjust(@RequestBody Map<String, Object> body) {
        String claimId = String.valueOf(body.getOrDefault("claimId", ""));
        boolean pass = Boolean.parseBoolean(String.valueOf(body.getOrDefault("pass", "true")));
        String note = String.valueOf(body.getOrDefault("note", ""));
        return Map.of("code", 0, "data", store.adjust(claimId, pass, note));
    }

    @PostMapping("/api/docs")
    public Map<String, Object> docs(@RequestBody Map<String, Object> body) {
        String claimId = String.valueOf(body.getOrDefault("claimId", ""));
        String docs = String.valueOf(body.getOrDefault("docs", ""));
        return Map.of("code", 0, "data", store.askDocs(claimId, docs));
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        return Map.of("code", 0, "data", store.stats());
    }
}

/** 数值解析工具（包内共享） */
final class IStoreHelper {
    static double dbl(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
