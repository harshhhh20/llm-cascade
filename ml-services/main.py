import os

from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
import numpy as np
import requests

from guardrails import is_suspicious

app = FastAPI(title="LLM Gateway ML Service")

embedder = SentenceTransformer("all-MiniLM-L6-v2")

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://ollama:11434")
LOCAL_MODEL_NAME = os.environ.get("LOCAL_MODEL_NAME", "qwen2.5:1.5b")

CONFIDENCE_MARGIN_THRESHOLD = 0.08

SEED_EXAMPLES = {
    "trivial": [
        "hi", "what is 2+2", "what time is it", "hello there", "good morning",
        "is the sky blue?", "what day is today?", "5*5", "how are you?", "bye",
        "is water wet?", "3+8", "good evening", "what is 10/2", "hi bot",
        "hello", "is 5 greater than 3?", "do you sleep?", "what year is it?", "good afternoon",
        "thank you", "thanks", "sup", "hey", "tell me the time",
        "is the earth round?", "what is 100-2", "who am i", "howdy", "hiya",
        "what's up", "goodnight", "ok", "okay", "yes",
        "no", "is fire hot?", "are you an ai?", "is 1+1=2?", "what is 9*9",
        "12/4", "morning", "evening", "greetings", "hello bot",
        "is ice cold?", "is 0 even?", "can you hear me?", "are you there?", "test",
        "testing 123", "who are you?", "what is 0+0", "is 10<20?",
    ],
    "easy": [
        "explain what a hash map is", "how do linked lists work", "what is a binary tree",
        "summarize this paragraph", "what is the capital of france", "convert 10 miles to km", "explain what a variable is",
        "translate hello to spanish", "what is the tallest mountain", "define photosynthesis", "how many cups in a quart",
        "who wrote romeo and juliet", "what is the speed of light", "convert 32 fahrenheit to celsius", "summarize the plot of the matrix",
        "what is a noun?", "translate thank you to japanese", "who was the first us president?", "how long is a marathon in miles?",
        "what is the boiling point of water?", "define quantum mechanics briefly", "who painted the mona lisa", "what is the currency of japan",
        "convert 50 kg to lbs", "what is an api?", "how many continents are there", "explain gravity to a kid",
        "translate good morning to french", "who discovered penicillin", "what is the atomic number of oxygen", "convert 100 meters to yards",
        "what is a prime number", "summarize the rules of basketball", "what is html?", "translate goodbye to german",
        "what is the largest ocean", "explain what an ip address is", "who invented the telephone", "convert 5 gallons to liters",
        "what is a black hole", "summarize world war 2 in one sentence", "translate dog to spanish", "what is the population of earth",
        "how many days in a leap year", "define machine learning", "what is the distance to the moon", "convert 12 inches to cm",
        "who is the ceo of microsoft", "what is a metaphor", "summarize the story of cinderella", "translate cat to french",
        "what is the capital of australia", "explain the water cycle", "what is photosynthesis", "how many planets in the solar system",
    ],
    "hard": [
        "design a fault-tolerant distributed cache eviction strategy",
        "compare the tradeoffs of microservices vs a monolith for a fintech system",
        "write a proof that this algorithm runs in O(n log n)",
        "debug this race condition in my multithreaded code",
        "architect a scalable event-driven microservices platform on aws",
        "analyze the time and space complexity of dijkstra's algorithm",
        "explain the consensus mechanism in raft vs paxos",
        "write a python script to implement a lock-free queue",
        "how does a b-tree work under the hood in postgresql?",
        "compare the pros and cons of graphql and rest for a complex frontend",
        "design a real-time collaborative text editor like google docs",
        "write a mathematical proof by induction for the sum of the first n squares",
        "diagnose a memory leak in a nodejs application",
        "architect a globally distributed database with strong consistency",
        "explain how garbage collection works in java's g1 collector",
        "design a rate limiter using redis and lua scripts",
        "compare optimistic and pessimistic locking in rdbms",
        "write a rust macro to generate boilerplate for a state machine",
        "debug a deadlocking issue in a c++ multithreaded application",
        "design a system to handle 10 million concurrent websocket connections",
        "analyze the impact of cpu cache lines on multi-threaded performance",
        "explain the mathematical foundation of rsa encryption",
        "architect a video streaming platform like netflix",
        "compare different strategies for database sharding",
        "write a custom memory allocator in c",
        "debug a segmentation fault in a linux kernel module",
        "design a scalable search engine using inverted indices",
        "explain the CAP theorem and how it applies to cassandra",
        "write a proof of correctness for mergesort",
        "compare different approaches to handling distributed transactions",
        "design a system for distributed tracing across microservices",
        "analyze the performance tradeoffs of b-trees vs lsm trees",
        "explain how vector embeddings are generated and stored",
        "write a go program to implement a distributed hash table",
        "debug a subtle synchronization bug in an os kernel",
        "architect a highly available message queue like kafka",
        "compare different garbage collection algorithms in v8",
        "write a mathematical proof of fermat's little theorem",
        "design a system to process terabytes of log data daily",
        "analyze the security implications of cross-site request forgery",
        "explain the inner workings of the linux scheduler",
        "write a python script to parse and analyze binary network traffic",
        "debug a memory corruption issue in a c program",
        "design a distributed system for generating unique ids",
        "compare different approaches to load balancing at layer 4 and layer 7",
        "write a proof that the halting problem is undecidable",
        "architect a recommendation engine for an e-commerce platform",
        "explain the mathematical concept of eigenvectors and eigenvalues",
        "design a system to handle high-frequency trading data",
        "analyze the tradeoffs between different container orchestration tools",
    ],
}
_seed_embeddings = {
    bucket: embedder.encode(examples) for bucket, examples in SEED_EXAMPLES.items()
}

class OptimizeRequest(BaseModel):
    raw_query: str

class EmbedRequest(BaseModel):
    text: str

class ClassifyRequest(BaseModel):
    text: str

def cosine_sim(a, b) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

def llm_tiebreak(query: str) -> str | None:
    prompt = (
        "Classify the difficulty of the following question as exactly one word: "
        "trivial, easy, or hard.\n"
        "trivial = a greeting, small talk, or basic arithmetic.\n"
        "easy = a single well-known fact, definition, or concept, answerable in one or two sentences.\n"
        "hard = requires multi-step reasoning, system design, a proof, or deep technical explanation.\n\n"
        f"Question: {query}\n"
        "Answer with exactly one word: trivial, easy, or hard."
    )
    try:
        resp = requests.post(
            f"{OLLAMA_URL}/api/generate",
            json={"model": LOCAL_MODEL_NAME, "prompt": prompt, "stream": False, "keep_alive": "10m"},
            timeout=15,
        )
        resp.raise_for_status()
        text = resp.json().get("response", "").strip().lower()
        for bucket in ("trivial", "easy", "hard"):
            if bucket in text:
                return bucket
    except Exception:
        pass
    return None

@app.post("/optimize")
def optimize(req: OptimizeRequest):
    if is_suspicious(req.raw_query):
        return {"optimized_query": "", "rejected": True}

    cleaned = " ".join(req.raw_query.strip().split())
    for filler in ["um, ", "like, ", "you know, ", "please "]:
        cleaned = cleaned.replace(filler, "")

    return {"optimized_query": cleaned, "rejected": False}

@app.post("/embed")
def embed(req: EmbedRequest):
    vector = embedder.encode(req.text)
    return {"embedding": vector.tolist()}

@app.post("/classify")
def classify(req: ClassifyRequest):
    query_embedding = embedder.encode(req.text)

    scores = {}
    for bucket, embeddings in _seed_embeddings.items():
        sims = [cosine_sim(query_embedding, e) for e in embeddings]
        scores[bucket] = max(sims)

    ranked = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
    top_bucket, top_score = ranked[0]
    second_score = ranked[1][1]
    margin = top_score - second_score

    final_bucket = top_bucket
    method = "embedding"

    if margin < CONFIDENCE_MARGIN_THRESHOLD:
        tiebreak = llm_tiebreak(req.text)
        if tiebreak is not None:
            final_bucket = tiebreak
            method = "llm_tiebreak"

    return {"complexity": final_bucket, "score": top_score, "margin": margin, "method": method}

@app.get("/health")
def health():
    return {"status": "ok"}
