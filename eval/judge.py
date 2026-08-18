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
import time

import requests

API_KEY = os.environ.get("FRONTIER_API_KEY")

# Delay between every request to avoid rate limits on free tier
REQUEST_DELAY_S = 5


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

    for attempt in range(8):
        resp = requests.post(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
            headers={
                "Content-Type": "application/json",
                "x-goog-api-key": API_KEY
            },
            json={"contents": [{"parts": [{"text": prompt}]}]},
            timeout=60,
        )
        if resp.status_code == 429:
            wait = (attempt + 1) * 5
            print(f"    rate-limited, waiting {wait}s (attempt {attempt+1}/8)...")
            if attempt == 7:
                resp.raise_for_status()
            time.sleep(wait)
            continue
        resp.raise_for_status()
        break

    text = resp.json()["candidates"][0]["content"]["parts"][0]["text"].strip()
    text = text.replace("```json", "").replace("```", "").strip()
    return json.loads(text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", default="results.jsonl")
    parser.add_argument("--out", default="scored_results.jsonl")
    args = parser.parse_args()

    if not API_KEY:
        raise SystemExit("Set FRONTIER_API_KEY before running the judge.")

    with open(args.results) as f:
        rows = [json.loads(line) for line in f]

    # Resume: load any previously scored results so we don't redo them
    already_scored = {}
    if os.path.exists(args.out):
        with open(args.out) as f:
            for line in f:
                row = json.loads(line)
                already_scored[row["id"]] = row
        print(f"Resuming: {len(already_scored)} already scored, {len(rows) - len(already_scored)} remaining")

    scored = []
    for i, row in enumerate(rows, 1):
        if row["id"] in already_scored:
            print(f"[{i}/{len(rows)}] {row['id']} already scored, skipping")
            scored.append(already_scored[row["id"]])
            continue

        print(f"[{i}/{len(rows)}] scoring {row['id']}...")
        judgment = score_answer(row["query"], row["adaptive"]["answer"], row["keypoints"])
        row["correctness_score"] = judgment["score"]
        row["judge_reasoning"] = judgment["reasoning"]
        scored.append(row)

        # Write incrementally so progress is never lost
        with open(args.out, "w") as f:
            for r in scored:
                f.write(json.dumps(r) + "\n")

        # Pace requests to stay under rate limit
        time.sleep(REQUEST_DELAY_S)

    print(f"\nWrote {len(scored)} scored results to {args.out}")
    print("Next: python plot_results.py --scored scored_results.jsonl")


if __name__ == "__main__":
    main()
