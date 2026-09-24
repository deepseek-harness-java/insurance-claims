package cn.xiaofuge.i.app;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 保单与理赔数据中心：保单档案、理赔单、审核规则、统计 */
@Component
public class IStore {

    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 险种规则：等待期天数 / 免赔额 / 赔付比例 / 单次上限 / 年度上限 */
    public record Rule(int waitingDays, double deductible, double payoutRate, double perLimit, double yearLimit, String docs) {}
    static final Map<String, Rule> RULES = Map.of(
            "医疗险", new Rule(30, 100, 0.90, 20000, 100000, "发票、费用清单、病历"),
            "重疾险", new Rule(90, 0, 1.00, 500000, 500000, "二级及以上医院确诊证明、病理报告"),
            "意外险", new Rule(0, 0, 0.80, 50000, 200000, "意外事故证明、发票、伤残鉴定(如适用)"),
            "车险",   new Rule(0, 500, 0.85, 200000, 1000000, "交警责任认定书、维修发票、现场照片"),
            "家财险", new Rule(0, 200, 0.70, 30000, 100000, "损失清单、维修发票、现场照片")
    );

    public static class Policy {
        public String id; public String holder; public String product;
        public double premium; public String effective; public String expires; public String status;
        public double paidThisYear; // 本年已赔
    }

    public static class Claim {
        public String id; public String policyId; public String holder; public String product;
        public String reason; public double amount; public String status; // 待审核/已赔付/已拒赔/材料补齐中
        public String filedAt; public String decision; public double payout;
    }

    public final List<Policy> policies = new ArrayList<>();
    public final List<Claim> claims = new ArrayList<>();
    private int policySeq = 2001;
    private int claimSeq = 5001;

    public IStore() { seed(); }

    private void seed() {
        policies.add(p("P2001", "王先生", "医疗险", 1200, "2025-10-01", "2026-09-30", "有效", 0));
        policies.add(p("P2002", "李女士", "重疾险", 5800, "2024-03-15", "2027-03-14", "有效", 0));
        policies.add(p("P2003", "张先生", "车险", 4200, "2025-12-01", "2026-11-30", "有效", 8500));
        policies.add(p("P2004", "刘女士", "意外险", 300, "2026-01-10", "2027-01-09", "有效", 0));
        policies.add(p("P2005", "陈先生", "家财险", 450, "2025-06-20", "2026-06-19", "已过期", 0));

        claims.add(c("C5001", "P2001", "王先生", "医疗险", "阑尾炎手术住院", 8600, "待审核", "09-18 10:20", "", 0));
        claims.add(c("C5002", "P2003", "张先生", "车险", "追尾事故维修", 12000, "已赔付", "09-05 14:00", "责任明确，定损 12000，按 85% 赔付 10200（免赔 500 已扣）", 10200));
        claims.add(c("C5003", "P2002", "李女士", "重疾险", "甲状腺癌确诊", 300000, "材料补齐中", "09-12 09:30", "需补充病理报告原件", 0));
        claims.add(c("C5004", "P2004", "刘女士", "意外险", "崴脚门诊治疗", 850, "已赔付", "08-28 16:45", "门诊发票齐全，按 80% 赔付 680", 680));
        claims.add(c("C5005", "P2005", "陈先生", "家财险", "水淹地板维修", 6000, "已拒赔", "09-15 11:00", "保单已于 2026-06-19 过期，出险时保障失效", 0));
    }

    private Policy p(String id, String holder, String product, double premium, String eff, String exp, String status, double paid) {
        Policy x = new Policy(); x.id = id; x.holder = holder; x.product = product; x.premium = premium;
        x.effective = eff; x.expires = exp; x.status = status; x.paidThisYear = paid; return x;
    }

    private Claim c(String id, String pid, String holder, String product, String reason, double amount, String status, String at, String decision, double payout) {
        Claim x = new Claim(); x.id = id; x.policyId = pid; x.holder = holder; x.product = product;
        x.reason = reason; x.amount = amount; x.status = status; x.filedAt = at; x.decision = decision; x.payout = payout;
        return x;
    }

    /** 事前核验：保单有效 + 等待期 + 免赔/比例/上限测算 → 返回 ok + 问题 + 预估赔付 */
    public Map<String, Object> check(String policyId, double amount, String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Policy pol = findPolicy(policyId);
        if (pol == null) {
            r.put("ok", false); r.put("problems", List.of("保单号 " + policyId + " 不存在，请核对"));
            return r;
        }
        Rule rule = RULES.get(pol.product);
        double est = 0;
        if (!"有效".equals(pol.status)) problems.add("保单状态为「" + pol.status + "」（" + pol.expires + " 到期），出险不在保障期内");
        double afterDeduct = Math.max(0, amount - rule.deductible);
        est = afterDeduct * rule.payoutRate;
        if (est > rule.perLimit) { problems.add("申请金额对应赔付 " + est + 元() + "，超单次上限 " + rule.perLimit + 元()); est = rule.perLimit; }
        if (pol.paidThisYear + est > rule.yearLimit) {
            problems.add("年度累计赔付将超上限 " + rule.yearLimit + 元() + "（本年已赔 " + pol.paidThisYear + 元() + "）");
        }
        r.put("ok", problems.isEmpty());
        r.put("policyId", pol.id); r.put("holder", pol.holder); r.put("product", pol.product);
        r.put("policyStatus", pol.status);
        r.put("deductible", rule.deductible); r.put("payoutRate", rule.payoutRate);
        r.put("requiredDocs", rule.docs);
        r.put("estimatePayout", Math.round(est * 100) / 100.0);
        r.put("problems", problems);
        r.put("advice", problems.isEmpty() ? "预估赔付 " + Math.round(est * 100) / 100.0 + 元() + "，理赔需备齐：" + rule.docs : "请先解决以上问题再提交理赔");
        return r;
    }

    /** 提交理赔：必须核验通过 */
    public synchronized Map<String, Object> file(String policyId, double amount, String reason) {
        Map<String, Object> pre = check(policyId, amount, reason);
        if (Boolean.FALSE.equals(pre.get("ok"))) {
            return Map.of("ok", false, "msg", "核验未通过：" + pre.get("problems"));
        }
        Policy pol = findPolicy(policyId);
        Claim x = new Claim();
        x.id = "C" + claimSeq++;
        x.policyId = policyId; x.holder = pol.holder; x.product = pol.product;
        x.reason = reason; x.amount = amount; x.status = "待审核";
        x.filedAt = LocalDateTime.now().format(HM);
        claims.add(0, x);
        return Map.of("ok", true, "claimId", x.id, "estimatePayout", pre.get("estimatePayout"),
                "requiredDocs", pre.get("requiredDocs"));
    }

    /** 审核：通过 → 计算赔付并记入保单本年已赔；拒绝 → 必须给理由 */
    public synchronized Map<String, Object> adjust(String claimId, boolean pass, String note) {
        Claim x = claims.stream().filter(cl -> cl.id.equals(claimId)).findFirst().orElse(null);
        if (x == null) return Map.of("ok", false, "msg", "理赔单 " + claimId + " 不存在");
        if (!"待审核".equals(x.status) && !"材料补齐中".equals(x.status))
            return Map.of("ok", false, "msg", "理赔单 " + claimId + " 状态为「" + x.status + "」，无需重复审核");
        if (!pass) {
            x.status = "已拒赔";
            x.decision = note == null || note.isBlank() ? "未提供拒赔理由" : note;
            return Map.of("ok", true, "claimId", x.id, "status", x.status);
        }
        Rule rule = RULES.get(x.product);
        double payout = Math.min(Math.max(0, x.amount - rule.deductible) * rule.payoutRate, rule.perLimit);
        payout = Math.round(payout * 100) / 100.0;
        x.status = "已赔付"; x.payout = payout;
        x.decision = (note == null || note.isBlank() ? "审核通过" : note) + "；免赔 " + rule.deductible + 元() + "，按 " + (int) (rule.payoutRate * 100) + "% 赔付 " + payout + 元();
        findPolicy(x.policyId).paidThisYear += payout;
        return Map.of("ok", true, "claimId", x.id, "payout", payout, "status", x.status);
    }

    /** 补材料：待审核/材料补齐中可要求补充 */
    public synchronized Map<String, Object> askDocs(String claimId, String docs) {
        Claim x = claims.stream().filter(cl -> cl.id.equals(claimId)).findFirst().orElse(null);
        if (x == null) return Map.of("ok", false, "msg", "理赔单 " + claimId + " 不存在");
        if (!"待审核".equals(x.status)) return Map.of("ok", false, "msg", "仅「待审核」状态可要求补材料");
        x.status = "材料补齐中";
        x.decision = "需补充：" + (docs == null || docs.isBlank() ? RULES.get(x.product).docs : docs);
        return Map.of("ok", true, "claimId", x.id, "status", x.status);
    }

    public Map<String, Object> stats() {
        long pending = claims.stream().filter(c -> "待审核".equals(c.status)).count();
        long docs = claims.stream().filter(c -> "材料补齐中".equals(c.status)).count();
        double paid = claims.stream().filter(c -> c.payout > 0).mapToDouble(c -> c.payout).sum();
        double estLiability = claims.stream().filter(c -> "待审核".equals(c.status)).mapToDouble(c -> c.amount).sum();
        Map<String, Long> byStatus = claims.stream().collect(Collectors.groupingBy(c -> c.status, Collectors.counting()));
        List<Map<String, Object>> products = policies.stream().collect(Collectors.groupingBy(pv -> pv.product))
                .entrySet().stream().map(e -> {
                    Rule rule = RULES.get(e.getKey());
                    double exposure = e.getValue().size() * rule.yearLimit;
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("product", e.getKey());
                    m.put("policies", e.getValue().size());
                    m.put("paidThisYear", Math.round(e.getValue().stream().mapToDouble(pv -> pv.paidThisYear).sum() * 100) / 100.0);
                    m.put("payoutRate", rule.payoutRate); m.put("deductible", rule.deductible);
                    return m;
                }).collect(Collectors.toList());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalClaims", claims.size());
        r.put("pending", pending); r.put("docsMissing", docs);
        r.put("paidAmount", paid);
        r.put("pendingLiability", estLiability);
        r.put("byStatus", byStatus);
        r.put("products", products);
        r.put("advice", pending > 0 ? "积压 " + pending + " 笔待审核，建议优先处理金额最大的案件；材料补齐 " + docs + " 笔需跟进催收" : "无积压案件，材料补齐 " + docs + " 笔需跟进");
        return r;
    }

    static String 元() { return " 元"; }

    public Policy findPolicy(String id) { return policies.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null); }
}
