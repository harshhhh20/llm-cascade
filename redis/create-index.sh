#!/bin/sh
# Run this once against the redis-stack container to enable vector KNN search.
# dim 384 matches the all-MiniLM-L6-v2 embedding size used by the ml-service.
redis-cli FT.CREATE query_cache_idx ON HASH PREFIX 1 cache: SCHEMA \
  query TEXT \
  answer TEXT \
  embedding VECTOR HNSW 6 TYPE FLOAT32 DIM 384 DISTANCE_METRIC COSINE
