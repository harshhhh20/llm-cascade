"""
Scores each result's adaptive answer for correctness using an LLM-as-judge
call against the frontier API, checking whether the answer covers the
expected keypoints. Human-spot-check a sample of these before trusting them
fully — LLM judges are a reasonable proxy, not ground truth.

Usage:
    export FRONTIER_API_KEY=sk-ant-...
    python judge.py --results results.jsonl
"""
import argparse
import json
import os

import requests

API_KEY = os.environ.get("FRONTIER_API_KEY")


def score_answer(query: str, answer: str, keypoints: list[str]) -> dict:
    keypoints_str = "\n".join(f"- {k}" for k in keypoints)
    prompt = f"""You are grading an AI system's answer for correctness.

Query: {query}

Answer given: {answer}

Expected keypoints the answer should cover:
{keypoints_str}

Score the answer from 0 to 1 (1 = fully correct and covers the keypoints,
0 = wrong or missing the keypoints entirely). Respond ONLY with a JSON object:
{{"score": <float 0-1>, "reasoning": "<one short sentence>"}}"""

    resp = requests.post(
        "https://api.anthropic.com/v1/messages",
        headers={
            "x-api-key": API_KEY,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
        json={
            "model": "claude-sonnet-4-6",
            "max_tokens": 200,
            "messages": [{"role": "user", "content": prompt}],
        },
        timeout=30,
    )
    resp.raise_for_status()
    text = resp.json()["content"][0]["text"].strip()
    text = text.replace("```json", "").replace("```", "").strip()
    return json.loads(text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", default="results.jsonl")
    parser.add_argument("--out", default="scored_results.jsonl")
    args = parser.parse_args()

    if not API_KEY:
        raise SystemExit("Set FRONTIER_API_KEY before running the judge.")

    scored = []
    with open(args.results) as f:
        rows = [json.loads(line) for line in f]

    for i, row in enumerate(rows, 1):
        print(f"[{i}/{len(rows)}] scoring {row['id']}...")
        judgment = score_answer(row["query"], row["adaptive"]["answer"], row["keypoints"])
        row["correctness_score"] = judgment["score"]
        row["judge_reasoning"] = judgment["reasoning"]
        scored.append(row)

    with open(args.out, "w") as f:
        for row in scored:
            f.write(json.dumps(row) + "\n")

    print(f"\nWrote {len(scored)} scored results to {args.out}")
    print("Next: python plot_results.py --scored scored_results.jsonl")


if __name__ == "__main__":
    main()
