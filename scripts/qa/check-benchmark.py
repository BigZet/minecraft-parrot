#!/usr/bin/env python3
"""Evaluate the nine full runs against technical specification section 14."""
import argparse
import json
from pathlib import Path


def evaluate(runs):
    failures = []
    indexed = {(run["scenario"], run["run"]): run for run in runs}
    expected = {(scenario, number) for scenario in ("baseline", "vanilla50", "custom50") for number in (1, 2, 3)}
    if len(runs) != 9 or set(indexed) != expected:
        failures.append("Exactly three full runs of each of the three scenarios are required")
    comparisons = []
    for key, run in indexed.items():
        label = f"{key[0]} #{key[1]}"
        if run.get("warmupTicks") != 6000 or run.get("sampleTicks") != 12000:
            failures.append(f"{label}: requires 5-minute warmup and 10-minute measurement")
        if not run.get("storageStayedReady", False):
            failures.append(f"{label}: storage did not stay READY")
        required_entities = 0 if key[0] == "baseline" else 50
        if run.get("minimumLoadedEntities") != required_entities:
            failures.append(f"{label}: incorrect loaded entity count")
        threshold = 19.8 if key[0] == "baseline" else 19.5
        if run.get("tps", 0) < threshold:
            failures.append(f"{label}: TPS below {threshold}")
        if key[0] == "custom50":
            if run.get("schedulerP95Ms", float("inf")) > 1:
                failures.append(f"{label}: scheduler p95 above 1 ms")
            baseline = indexed.get(("baseline", key[1]))
            if baseline:
                addition = run["tickP95Ms"] - baseline["tickP95Ms"]
                comparisons.append({"run": key[1], "customTickP95AdditionMs": addition})
                if addition > 5:
                    failures.append(f"{label}: tick p95 addition above 5 ms")
    return {"passed": not failures, "failures": failures, "runs": len(runs), "comparisons": comparisons,
            "scope": "full 9-run performance thresholds; hardware isolation recorded separately"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    result = evaluate(json.loads(arguments.results.read_text(encoding="utf-8-sig")))
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
