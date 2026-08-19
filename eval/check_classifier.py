import json
import requests
import argparse

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ml-service", default="http://localhost:8000")
    args = parser.parse_args()

    mismatches = []
    low_margins = []

    with open("dataset.jsonl", "r") as f:
        for line in f:
            if not line.strip(): continue
            data = json.loads(line)
            query = data["query"]
            expected = data["expected_difficulty"]

            resp = requests.post(f"{args.ml_service}/classify", json={"text": query})
            if resp.status_code != 200:
                print(f"Error classifying: {query}")
                continue
            
            result = resp.json()
            actual = result["complexity"]
            margin = result.get("margin", 0.0)

            if actual != expected:
                mismatches.append({"query": query, "expected": expected, "actual": actual, "margin": margin})
            elif margin < 0.02: # Arbitrary threshold for "barely won"
                low_margins.append({"query": query, "expected": expected, "actual": actual, "margin": margin})

    print(f"\n=== MISMATCHES ({len(mismatches)}) ===")
    for m in mismatches:
        print(f"[EXPECTED {m['expected']} -> CLASSIFIED {m['actual']}] (Margin: {m['margin']:.3f}) | {m['query']}")

    print(f"\n=== LOW MARGIN CORRECT CLASSES (< 0.02) ({len(low_margins)}) ===")
    for m in low_margins:
        print(f"[{m['actual']}] Margin: {m['margin']:.4f} | {m['query']}")

if __name__ == "__main__":
    main()
