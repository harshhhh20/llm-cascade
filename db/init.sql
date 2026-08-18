CREATE TABLE request_log (
  id UUID PRIMARY KEY,
  trace_id UUID NOT NULL,
  query TEXT NOT NULL,
  route VARCHAR(48) NOT NULL,
  model_used VARCHAR(64),
  cache_hit BOOLEAN DEFAULT FALSE,
  cache_similarity FLOAT,
  latency_ms INTEGER,
  estimated_cost_usd NUMERIC(10,6),
  correctness_score FLOAT,
  created_at TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_request_log_trace_id ON request_log(trace_id);
CREATE INDEX idx_request_log_route ON request_log(route);
