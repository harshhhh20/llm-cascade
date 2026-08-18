import argparse
import json
import os
import time
import requests

API_KEY = os.environ.get("FRONTIER_API_KEY")

def score_answer(query: str, answer: str, keypoints: list[str]) -> dict:
    if not API_KEY:
        raise ValueError("FRONTIER_API_KEY environment variable is not set")
        
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
            if attempt == 7:
                resp.raise_for_status()
            time.sleep(wait)
            continue
        resp.raise_for_status()
        break

    text = resp.json()["candidates"][0]["content"]["parts"][0]["text"].strip()
    text = text.replace("```json", "").replace("```", "").strip()
    return json.loads(text)

def generate_local(ollama_url: str, model: str, query: str) -> tuple[str, float]:
    start = time.time()
    try:
        resp = requests.post(
            f"{ollama_url}/api/generate",
            json={
                "model": model,
                "prompt": query,
                "stream": False,
                "keep_alive": "10m"
            },
            timeout=300 # Wait up to 5 mins for slow CPU inference
        )
        resp.raise_for_status()
        answer = resp.json()["response"]
    except Exception as e:
        answer = f"Error: {str(e)}"
    wall_sec = time.time() - start
    return answer, wall_sec

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ollama", default="http://ollama:11434")
    parser.add_argument("--dataset", default="dataset.jsonl")
    parser.add_argument("--models", default="phi3:mini,qwen2.5:0.5b,qwen2.5:1.5b")
    args = parser.parse_args()

    models = args.models.split(",")
    examples = []
    
    with open(args.dataset) as f:
        for line in f:
            line = line.strip()
            if line:
                ex = json.loads(line)
                if ex["expected_difficulty"] == "easy":
                    examples.append(ex)
                    
    print(f"Loaded {len(examples)} easy queries.")
    
    results = {m: {"latency_sec": [], "correctness": []} for m in models}

    for model in models:
        print(f"\n--- Evaluating {model} ---")
        for i, ex in enumerate(examples, 1):
            print(f"[{i}/{len(examples)}] {ex['id']}: {ex['query'][:40]}...", end=" ", flush=True)
            
            # Generate
            answer, wall_sec = generate_local(args.ollama, model, ex["query"])
            results[model]["latency_sec"].append(wall_sec)
            
            if "Error" in answer:
                score = 0.0
                print(f"FAIL ({wall_sec:.1f}s)")
            else:
                # Score with Gemini (pace to avoid 429)
                time.sleep(5) 
                judgment = score_answer(ex["query"], answer, ex["keypoints"])
                score = float(judgment["score"])
                print(f"-> score {score:.2f} ({wall_sec:.1f}s)")
                
            results[model]["correctness"].append(score)

    print("\n\n" + "="*50)
    print(f"{'Model':<20} | {'Avg Latency (s)':<15} | {'Avg Correctness':<15}")
    print("-" * 55)
    for model in models:
        avg_lat = sum(results[model]["latency_sec"]) / max(1, len(results[model]["latency_sec"]))
        avg_corr = sum(results[model]["correctness"]) / max(1, len(results[model]["correctness"]))
        print(f"{model:<20} | {avg_lat:<15.1f} | {avg_corr:<15.2f}")
    print("="*50)

if __name__ == "__main__":
    main()
