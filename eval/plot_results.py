"""
Produces the actual interview artifact: a chart comparing adaptive routing
against the always-frontier baseline on cost and accuracy.

Usage:
    python plot_results.py --scored scored_results.jsonl
"""
import argparse
import json

import matplotlib.pyplot as plt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scored", default="scored_results.jsonl")
    parser.add_argument("--out", default="cost_vs_accuracy.png")
    args = parser.parse_args()

    rows = [json.loads(line) for line in open(args.scored)]

    adaptive_cost = sum(r["adaptive"]["estimatedCostUsd"] for r in rows)
    baseline_cost = sum(r["baseline"]["estimatedCostUsd"] for r in rows)
    adaptive_accuracy = sum(r["correctness_score"] for r in rows) / len(rows)

    # Baseline accuracy assumed ~equal to adaptive's frontier-tier accuracy;
    # for a rigorous number, also run judge.py against baseline answers and
    # average those scores in here instead.
    cost_saved_pct = (1 - adaptive_cost / baseline_cost) * 100 if baseline_cost > 0 else 0

    route_counts = {}
    for r in rows:
        route = r["adaptive"]["route"]
        route_counts[route] = route_counts.get(route, 0) + 1

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.5))

    axes[0].bar(["Always Frontier\n(baseline)", "Adaptive Gateway"],
                [baseline_cost, adaptive_cost], color=["#888888", "#4C8BF5"])
    axes[0].set_ylabel("Total estimated cost (USD)")
    axes[0].set_title(f"Cost: {cost_saved_pct:.1f}% saved")

    axes[1].bar(route_counts.keys(), route_counts.values(), color="#4C8BF5")
    axes[1].set_ylabel("Number of queries")
    axes[1].set_title("Route distribution (adaptive)")
    axes[1].tick_params(axis="x", rotation=30)

    fig.suptitle(f"Adaptive avg. correctness: {adaptive_accuracy:.2f}", fontsize=11)
    fig.tight_layout()
    fig.savefig(args.out, dpi=150)

    print(f"Saved chart to {args.out}")
    print(f"Cost saved: {cost_saved_pct:.1f}%")
    print(f"Adaptive avg correctness: {adaptive_accuracy:.2f}")
    print(f"Route distribution: {route_counts}")


if __name__ == "__main__":
    main()
