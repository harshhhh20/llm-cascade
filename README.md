# Intelligent LLM Cascade

A dynamic, multi-tier LLM routing gateway that optimizes for cost, latency, and correctness. This system intelligently routes user queries between a local open-weight model, a rule-based engine, and a frontier LLM API based on query complexity.

## Architecture Highlights

- **Dynamic Routing:** Queries are classified via an embedding-based few-shot classifier into trivial, easy, or hard buckets.
- **Graceful Fallback:** The local model tier enforces strict SLAs. If the local tier times out or fails, the request gracefully falls back to the frontier model without failing the user request.
- **Rule-Based Fast Path:** Trivial queries (e.g., greetings, basic math) are handled by a deterministic rule engine for zero cost and zero latency.
- **Async Logging & Traceability:** Every request generates a Trace ID that propagates through MDC headers and asynchronously logs route, cost, latency, and correctness data into Postgres without blocking the client.
- **Semantic Caching:** Integrated Redis Stack for HNSW vector search to catch semantically similar queries before hitting any LLM.
- **Interactive UI:** A real-time dashboard visualization showing exactly which tier handled the request, complete with animated route lines and live cost-savings metrics.

## Project Structure

```text
llm-cascade/
  docker-compose.yml           # Multi-container orchestration
  .env                         # Environment variables and API keys
  db/init.sql                  # Postgres schema initialization
  redis/create-index.sh        # Script to build the HNSW vector index
  gateway-service/             # Spring Boot orchestrator (Java 17)
  ml-services/                 # FastAPI: prompt optimization, embedding, classification
```

## Local Requirements

- **RAM:** Minimum 8GB (16GB recommended) allocated to Docker Desktop.
- **Storage:** ~3GB free disk space (1GB for the `qwen2.5:1.5b` model, ~2GB for the Docker images/DBs).

## Running it locally

1. Set your Gemini API key in the `.env` file:
   ```
   FRONTIER_API_KEY=your_api_key_here
   ```
2. Start the cluster:
   ```bash
   docker compose up --build -d
   ```
3. Pull the local model into the Ollama container:
   ```bash
   docker exec -it llm-cascade-ollama-1 ollama pull qwen2.5:1.5b
   ```
4. Create the Redis vector index (one-time setup):
   ```bash
   docker exec -it llm-cascade-redis-1 sh /redis/create-index.sh
   ```
5. Access the interactive UI at **http://localhost:8080/** to test the system live.

## Evaluation Benchmark

The system includes a 30-query evaluation suite that runs queries against the gateway and scores the correctness of the responses using an LLM-as-a-judge pattern.

**Final 30-Query Benchmark Results:**

| Metric | Value |
|---|---|
| Adaptive Avg. Correctness | 0.99 |
| Total Cost Saved | 63.1% |

**Route Distribution:**

| Route | Count | Explanation |
|---|---|---|
| `rule_based` | 8 | Greetings / simple math handled instantly for $0. |
| `rule_based_escalated_local` | 1 | Escaped the trivial rule set, but safely handled by the local tier. |
| `local_model` | 10 | Easy queries securely handled by qwen2.5:1.5b. |
| `frontier_model` | 11 | Hard queries correctly routed straight to Gemini. |
| `local_model_fallback_frontier`| 0 | No SLA timeouts or cold-start drops. |

## Local Tier Selection

Local-tier model selection was based on a direct latency vs. correctness benchmark across three candidates on the actual evaluation set, not a default choice:

| Model | Avg Latency | Avg Correctness | Decision |
|---|---|---|---|
| phi3:mini | 20.9s | 1.00 | Too slow - exceeds SLA on CPU |
| qwen2.5:0.5b | 3.3s | 0.74 | Too weak - poor correctness |
| qwen2.5:1.5b | 6.5s | 0.95 | Selected - optimal tradeoff |

`qwen2.5:1.5b` was selected for its best correctness-per-second tradeoff. The gateway SLA is strictly enforced at 20 seconds.

*Note on Hardware:* On a CPU-only Docker Desktop environment, local inference incurs higher latencies compared to a production deployment. In a production environment, the local tier would run on a GPU-backed instance (e.g., vLLM on a T4 or A10G), where `qwen2.5:1.5b` inference takes roughly 200ms, making the local tier significantly faster than the frontier network round-trip.

## Deployment Notes

- The `/api/stats` endpoint currently reads across all logged requests and lacks authentication. In a public-facing deployment, this should be secured.
- `FRONTIER_API_KEY` is loaded securely via the `.env` file and ignored by git to prevent secret leakage.
