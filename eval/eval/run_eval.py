"""
Runs the labeled eval set through the gateway twice per query:
  1. Adaptive routing  — normal /api/query call
  2. Baseline          — same call with forceFrontier=true (always frontier)

Writes results to eval/results.jsonl for judge.py to score.

Usage:
    python run_eval.py --gateway http://localhost:8080
"""
import argparse
import json
import time

import requests


def load_dataset(path="dataset.jsonl"):
    examples = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                examples.append(json.loads(line))
    return examples


def call_gateway(gateway_url: str, query: str, force_frontier: bool):
    payload = {"query": query, "userId": "eval-harness", "forceFrontier": force_frontier}
    start = time.time()
    resp = requests.post(f"{gateway_url}/api/query", json=payload, timeout=30)
    wall_ms = int((time.time() - start) * 1000)
    resp.raise_for_status()
    body = resp.json()
    body["measured_wall_ms"] = wall_ms
    return body


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8080")
    parser.add_argument("--dataset", default="dataset.jsonl")
    parser.add_argument("--out", default="results.jsonl")
    args = parser.parse_args()

    examples = load_dataset(args.dataset)
    results = []

    for i, ex in enumerate(examples, 1):
        print(f"[{i}/{len(examples)}] {ex['id']}: {ex['query'][:50]}...")

        adaptive = call_gateway(args.gateway, ex["query"], force_frontier=False)
        baseline = call_gateway(args.gateway, ex["query"], force_frontier=True)

        results.append({
            "id": ex["id"],
            "query": ex["query"],
            "expected_difficulty": ex["expected_difficulty"],
            "keypoints": ex["keypoints"],
            "adaptive": adaptive,
            "baseline": baseline,
        })

    with open(args.out, "w") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")

    print(f"\nWrote {len(results)} results to {args.out}")
    print("Next: python judge.py --results results.jsonl")


if __name__ == "__main__":
    main()
