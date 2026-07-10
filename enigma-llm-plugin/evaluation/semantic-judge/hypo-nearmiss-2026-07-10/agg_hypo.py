#!/usr/bin/env python3
"""Aggregate the Hypo semantic-judge matrix into three lenses, per model, per rubric (lenient|strict).

Full judge x model matrix (auto-discovers whichever judged files exist, so it runs incrementally):
  models : codex_gpt-5.5_high (self-family=codex), claude_opus_high (self=claude), local_30b (self=none)
  judges : codex, claude, gemini, grok    (cross-family = every judge != the model's own family)

For each model:
  * base exact/normalised over the api slice is read from the model's *-realistic-benchmark.jsonl
    (mirrors extract_hypo.py); residuals = resid_hypo_<model>.json, list index == judged-file `id`.

Three lenses:
  1. SELF-PREFERENCE (the assumption made explicit): for the two commercial models that have a
     same-family judge, compare that judge's ACCEPT rate against the mean of the cross-family judges
     on the SAME residuals (paired). A positive delta = measured self-preference; validates excluding
     same-family judges. Aggregated across the two commercial models.
  2. NEAR-MISS vs WRONG (the headline): using CROSS-FAMILY judges only (the defensible set),
     permissive = ANY cross-family ACCEPT, strict = ALL cross-family ACCEPT, over n_residual; plus a
     uniform {gemini,grok} panel (the only two judges cross-family to EVERY model -> apples-to-apples).
     Combined semantic recovery over the api slice = (exact + plausible_residual)/n_api, Wilson 95% CI.
  3. JUDGE AGREEMENT: pairwise verdict agreement across all available judges on local_30b.

Usage: agg_hypo.py           (paths relative to this script's dir)
"""
import glob, json, math, os

SP = os.path.dirname(os.path.abspath(__file__))
HR = os.path.join(SP, "hypo-results")
ALL_JUDGES = ["codex", "claude", "gemini", "grok"]
UNIFORM = ["gemini", "grok"]  # cross-family to every model

MODELS = {
    "gpt-5.5 high (commercial)": {
        "resid": "resid_hypo_codex_gpt-5.5_high.json", "key": "codex_gpt-5.5_high",
        "jsonl": f"{HR}/codex_gpt-5.5_high/codex_gpt-5.5_high/hypo-model-2.4.1-realistic-benchmark.jsonl",
        "self": "codex", "cls": "commercial",
    },
    "opus high (commercial)": {
        "resid": "resid_hypo_claude_opus_high.json", "key": "claude_opus_high",
        "jsonl": f"{HR}/claude_opus_high/claude_opus_high/hypo-model-2.4.1-realistic-benchmark.jsonl",
        "self": "claude", "cls": "commercial",
    },
    "local 30B (local)": {
        "resid": "resid_hypo_local_30b.json", "key": "local_30b",
        "jsonl": f"{HR}/local_30b/qwen3-coder-30b-a3b-instruct_iq4_xs/hypo-model-2.4.1-realistic-benchmark.jsonl",
        "self": None, "cls": "local",
    },
    # --- 6-model extension (added as MODELS after reviewer feedback; judged cross-family by codex/claude/gemini) ---
    "gemini Pro high (commercial)": {
        "resid": "resid_hypo_gemini_pro_high.json", "key": "gemini_pro_high",
        "jsonl": f"{HR}/gemini_pro_high/gemini_Gemini_3.1_Pro_High_/hypo-model-2.4.1-realistic-benchmark.jsonl",
        "self": "gemini", "cls": "commercial",
    },
    "sonnet high (commercial)": {
        "resid": "resid_hypo_sonnet_high.json", "key": "sonnet_high",
        "jsonl": f"{HR}/claude_sonnet_high/claude_sonnet_high/hypo-model-2.4.1-realistic-benchmark.jsonl",
        "self": "claude", "cls": "commercial",
    },
    "grok-build (commercial)": {
        "resid": "resid_hypo_grok_build.json", "key": "grok_build",
        "jsonl": f"{HR}/grok_build/grok_grok-build/hypo-model-2.4.1-realistic-benchmark.jsonl",
        "self": "grok", "cls": "commercial",
    },
}


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return (round((c - h) / d, 3), round((c + h) / d, 3))


def read_base(jsonl):
    n_api = exact = err = resid_like = 0
    if not os.path.exists(jsonl):
        return None
    for line in open(jsonl):
        line = line.strip()
        if not line:
            continue
        r = json.loads(line)
        if r.get("slice") != "api" or r.get("preservationControl"):
            continue
        n_api += 1
        if r.get("exact") or r.get("normalized"):
            exact += 1
        elif r.get("error"):
            err += 1
        elif (r.get("suggested") or "").strip():
            resid_like += 1
    return {"n_api": n_api, "exact": exact, "err": err, "resid_like": resid_like}


def load_verdicts(prefix, judge, model_key):
    p = os.path.join(SP, f"{prefix}{judge}_{model_key}.json")
    if not os.path.exists(p):
        return None
    return {int(x["id"]): x.get("verdict", "MISSING") for x in json.load(open(p))}


def acc_rate(vd, n_res):
    """returns (accept_count, answered_count) — answered excludes ids the judge never returned."""
    a = ans = 0
    for i in range(n_res):
        v = vd.get(i)
        if v is None or v == "MISSING":
            continue
        ans += 1
        if v == "ACCEPT":
            a += 1
    return a, ans


def main():
    lines, report = [], {}
    lines.append("=== Hypo semantic-judge aggregation :: 3 lenses (auto-discovered coverage) ===")
    selfpref_pairs = []  # (model, self_rate, cross_rate, n)

    for label, m in MODELS.items():
        rp = os.path.join(SP, m["resid"])
        if not os.path.exists(rp):
            lines.append(f"\n## {label}: NO residual file yet — skipped")
            continue
        n_res = len(json.load(open(rp)))
        base = read_base(m["jsonl"]) or {"n_api": 0, "exact": 0, "err": 0, "resid_like": n_res}
        n_api, exact = base["n_api"], base["exact"]
        sane = "OK" if base["resid_like"] == n_res else f"CHECK(jsonl_resid={base['resid_like']} vs {n_res})"
        entry = {"cls": m["cls"], "self": m["self"], "n_api": n_api, "exact": exact,
                 "err": base["err"], "n_res": n_res, "sanity": sane, "rubrics": {}}
        lines.append(f"\n## {label}   [{sane}]   api n={n_api} exact={exact}"
                     f"{f' ({exact/n_api:.1%})' if n_api else ''} residuals={n_res}  self-family={m['self']}")

        for rubric, prefix in (("lenient", "judged_hypo_"), ("strict", "judged_hypo_strict_")):
            avail = {}
            for j in ALL_JUDGES:
                vd = load_verdicts(prefix, j, m["key"])
                if vd is not None:
                    avail[j] = vd
            cross = [j for j in avail if j != m["self"]]
            rb = {"available": sorted(avail), "cross_family": sorted(cross), "per_judge": {}}
            for j in avail:
                a, ans = acc_rate(avail[j], n_res)
                rb["per_judge"][j] = {"accept": a, "answered": ans,
                                      "rate": (a / ans) if ans else None}

            def panel(judges):
                # majority = strict majority of ANSWERED judges (Gemini/Codex: primary near-miss lens,
                # avoids single-judge "roll a die" inflation); perm/strict kept as sensitivity; miss =
                # fraction of (judge,item) verdicts that were MISSING for this panel.
                perm = strict = major = cov = ans_cells = 0
                for i in range(n_res):
                    vs = [avail[j].get(i) for j in judges if avail[j].get(i) not in (None, "MISSING")]
                    ans_cells += len(vs)
                    if not vs:
                        continue
                    cov += 1
                    acc = sum(1 for v in vs if v == "ACCEPT")
                    if acc >= 1:
                        perm += 1
                    if acc == len(vs):
                        strict += 1
                    if acc > len(vs) / 2:
                        major += 1
                miss = round(1 - ans_cells / (len(judges) * n_res), 3) if (judges and n_res) else 0.0
                return {"perm": perm, "strict": strict, "major": major, "cov": cov, "miss": miss}

            EMPTY = {"perm": 0, "strict": 0, "major": 0, "cov": 0, "miss": 0.0}
            pc = panel(cross) if cross else dict(EMPTY)
            uni = [j for j in UNIFORM if j in avail]
            pu = panel(uni) if uni else dict(EMPTY)
            allj = sorted(avail)
            pa = panel(allj) if allj else dict(EMPTY)  # incl. self-family (sensitivity)
            rb["cross_family_panel"] = pc
            rb["uniform_panel"] = {"judges": uni, **pu}
            rb["all_panel"] = {"judges": allj, **pa}
            if n_api:
                rb["combined_recovery_api"] = {
                    "exact": round(exact / n_api, 4),
                    "cross_majority": round((exact + pc["major"]) / n_api, 4),
                    "cross_majority_ci": wilson(exact + pc["major"], n_api),
                    "cross_permissive": round((exact + pc["perm"]) / n_api, 4),
                    "cross_strict": round((exact + pc["strict"]) / n_api, 4),
                    "cross_miss_rate": pc["miss"],
                    "uniform_majority": round((exact + pu["major"]) / n_api, 4) if uni else None,
                    "all_majority": round((exact + pa["major"]) / n_api, 4),
                    "all_strict": round((exact + pa["strict"]) / n_api, 4),
                }
            # self-preference contribution (paired on answered-by-both items)
            if m["self"] in avail and cross:
                self_vd = avail[m["self"]]
                s_acc = s_n = c_acc = c_n = 0
                for i in range(n_res):
                    sv = self_vd.get(i)
                    cvs = [avail[j].get(i) for j in cross if avail[j].get(i) not in (None, "MISSING")]
                    if sv in (None, "MISSING") or not cvs:
                        continue
                    s_n += 1
                    s_acc += (sv == "ACCEPT")
                    c_n += len(cvs)
                    c_acc += sum(v == "ACCEPT" for v in cvs)
                if s_n:
                    sr, cr = s_acc / s_n, (c_acc / c_n if c_n else 0)
                    # robustness vs the "one pessimist drags the mean" concern: compare self against
                    # the MOST LENIENT cross-family judge, not just the pooled cross mean.
                    cross_rates = {j: (rb["per_judge"][j]["rate"] or 0) for j in cross}
                    best_j = max(cross_rates, key=cross_rates.get) if cross_rates else None
                    cmax = cross_rates[best_j] if best_j else 0
                    rb["self_pref"] = {"self_rate": round(sr, 3), "cross_rate": round(cr, 3),
                                       "delta": round(sr - cr, 3), "n_paired": s_n,
                                       "most_lenient_cross": best_j, "cross_max_rate": round(cmax, 3),
                                       "delta_vs_max": round(sr - cmax, 3)}
                    if rubric == "lenient":
                        selfpref_pairs.append((label, sr, cr, cmax, best_j, s_n))

            entry["rubrics"][rubric] = rb
            pj = "  ".join(f"{j}={rb['per_judge'][j]['accept']}/{rb['per_judge'][j]['answered']}"
                           f"({(rb['per_judge'][j]['rate'] or 0):.0%})" for j in sorted(avail))
            lines.append(f"   [{rubric:7}] avail={sorted(avail)} cross={sorted(cross)}")
            lines.append(f"             per-judge ACCEPT: {pj or '(none yet)'}")
            if cross and pc["cov"]:
                c = pc["cov"]
                lines.append(f"             cross-family: MAJORITY={pc['major']}/{c}({pc['major']/c:.0%}) "
                             f"perm={pc['perm']}/{c}({pc['perm']/c:.0%}) strict={pc['strict']}/{c}({pc['strict']/c:.0%}) "
                             f"miss={pc['miss']:.0%}")
            if uni and pu["cov"]:
                lines.append(f"             uniform{uni}: MAJORITY={pu['major']}/{pu['cov']}"
                             f"({pu['major']/pu['cov']:.0%}) strict={pu['strict']}/{pu['cov']}({pu['strict']/pu['cov']:.0%})")
            if n_api and cross:
                cr_ = rb["combined_recovery_api"]
                lines.append(f"             combined /api: exact={cr_['exact']:.1%}  "
                             f"cross-MAJORITY={cr_['cross_majority']:.1%} {cr_['cross_majority_ci']}  "
                             f"cross-perm={cr_['cross_permissive']:.1%}  cross-strict={cr_['cross_strict']:.1%}")
            if rb.get("self_pref"):
                sp = rb["self_pref"]
                lines.append(f"             SELF-PREF: self({m['self']})={sp['self_rate']:.0%} vs "
                             f"cross-mean={sp['cross_rate']:.0%} (delta={sp['delta']:+.0%})  vs "
                             f"most-lenient-cross({sp['most_lenient_cross']})={sp['cross_max_rate']:.0%} "
                             f"(delta_vs_max={sp['delta_vs_max']:+.0%})  n={sp['n_paired']}")
        report[label] = entry

    # aggregate self-preference across commercial models (lenient)
    lines.append("\n=== LENS 1 :: self-preference (lenient, commercial models) ===")
    if selfpref_pairs:
        ds = [sr - cr for _, sr, cr, _, _, _ in selfpref_pairs]
        dm = [sr - cmax for _, sr, _, cmax, _, _ in selfpref_pairs]
        for lbl, sr, cr, cmax, best_j, n in selfpref_pairs:
            lines.append(f"   {lbl}: self={sr:.0%}  cross-mean={cr:.0%} (d={sr-cr:+.0%})  "
                         f"most-lenient-cross({best_j})={cmax:.0%} (d_vs_max={sr-cmax:+.0%})  n={n}")
        mean_d, mean_dm = sum(ds)/len(ds), sum(dm)/len(dm)
        lines.append(f"   mean delta vs cross-mean = {mean_d:+.1%};  mean delta vs MOST-LENIENT cross = {mean_dm:+.1%}")
        lines.append(f"   -> {'self-preference survives even vs the most lenient outside judge (exclusion justified)' if mean_dm > 0.03 else 'self-edge vanishes against the most lenient cross judge (weak/no self-preference)'}")
    else:
        lines.append("   (no self-family cells judged yet)")

    # commercial vs local gap, cross-family lenient
    lines.append("\n=== LENS 2 :: commercial vs local (cross-family, lenient) ===")

    def gap(field):
        cv = [report[l]["rubrics"]["lenient"].get("combined_recovery_api", {}).get(field)
              for l in report if report[l]["cls"] == "commercial"]
        lv = [report[l]["rubrics"]["lenient"].get("combined_recovery_api", {}).get(field)
              for l in report if report[l]["cls"] == "local"]
        cv = [x for x in cv if x is not None]
        lv = [x for x in lv if x is not None]
        return (sum(cv)/len(cv) if cv else None), (sum(lv)/len(lv) if lv else None)

    lines.append("   (macro-mean over models per class; absolute points lead, ratio secondary)")
    for field, name in (("exact", "EXACT only"), ("cross_majority", "+near-miss (MAJORITY)"),
                        ("cross_permissive", "+near-miss (perm, sens.)"),
                        ("cross_strict", "+near-miss (strict, sens.)")):
        c, l = gap(field)
        if c is not None and l:
            lines.append(f"   {name:26}: commercial={c:.1%} local={l:.1%}  (delta {c-l:+.1%}, ratio {c/l:.2f}x)")
        elif c is not None:
            lines.append(f"   {name:20}: commercial={c:.1%} local={l}")

    # judge agreement on local_30b (lenient)
    lines.append("\n=== LENS 3 :: judge agreement on local 30B (lenient) ===")
    m = MODELS["local 30B (local)"]
    if os.path.exists(os.path.join(SP, m["resid"])):
        n_res = len(json.load(open(os.path.join(SP, m["resid"]))))
        vds = {j: load_verdicts("judged_hypo_", j, m["key"]) for j in ALL_JUDGES}
        vds = {j: v for j, v in vds.items() if v is not None}
        js = sorted(vds)
        for a in range(len(js)):
            for b in range(a + 1, len(js)):
                ja, jb = js[a], js[b]
                same = agree = 0
                for i in range(n_res):
                    va, vb = vds[ja].get(i), vds[jb].get(i)
                    if va in (None, "MISSING") or vb in (None, "MISSING"):
                        continue
                    same += 1
                    agree += (va == vb)
                lines.append(f"   {ja}~{jb}: {agree}/{same} ({(agree/same if same else 0):.0%})")

    open(os.path.join(SP, "agg_hypo_report.txt"), "w").write("\n".join(lines) + "\n")
    json.dump(report, open(os.path.join(SP, "agg_hypo_report.json"), "w"), indent=1)
    print("\n".join(lines))
    print(f"\n[written] agg_hypo_report.txt / .json")


if __name__ == "__main__":
    main()
