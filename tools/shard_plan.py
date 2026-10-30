"""Deterministic whole-origin scheduling; every normal/stamp/abort case is kept."""
import math

def validate(plan,origins,shards):
    flat=[origin for group in plan for origin in group]
    if len(plan)!=shards or any(not group for group in plan) or len(flat)!=len(origins) or len(flat)!=len(set(flat)) or set(flat)!=set(origins):
        raise ValueError('Missing, duplicate or unexpected origin in shard plan')
    return plan

def schedule(origins,costs,shards):
    if not origins or len(origins)!=len(set(origins)) or not 1<=shards<=len(origins) or set(costs)!=set(origins):
        raise ValueError('Invalid origin/shard/cost coverage')
    if any(type(value) not in (int,float) or not math.isfinite(value) or value<=0 for value in costs.values()):
        raise ValueError('Every origin needs a finite positive measured/declared cost')
    groups=[[] for _ in range(shards)];loads=[0.0]*shards
    for origin in sorted(origins,key=lambda name:(-costs[name],name)):
        index=min(range(shards),key=lambda value:(loads[value],len(groups[value]),value))
        groups[index].append(origin);loads[index]+=costs[origin]
    return validate(groups,origins,shards)
