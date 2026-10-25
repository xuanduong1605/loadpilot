<div align="center">

# Loadpilot

### Adaptive Load Testing and Kubernetes Resource Optimization

**A thesis project combining performance evidence, bounded experiments, and AI assistance.**

</div>

## Overview

Loadpilot is a thesis project exploring **adaptive load testing**, **Kubernetes resource optimization**, and **AI-assisted performance analysis**.

The project centers on two questions: **How much load can an application handle while meeting its performance goals?** and **Which CPU and replica configuration meets those goals with fewer allocated resources?**

It approaches these questions through controlled experiments, reproducible measurements, and comparisons under the same required load. The emphasis is on evidence that engineers can inspect and explain when assessing application capacity and resource tradeoffs.

## Research focus

| Theme | Focus |
| --- | --- |
| Adaptive load testing | Finding an observed capacity interval through bounded load adjustments |
| Resource optimization | Comparing CPU and replica configurations against latency, throughput, and error goals |
| Scenario authoring | Expressing performance goals through reviewed k6 test scenarios |
| Performance evidence | Connecting measurements to their workload, configuration, and observation window |
| AI assistance | Exploring scenario drafting and explanations grounded in experiment evidence |

## Experiment principles

Reproducibility and workload recovery are central to the project. Experiments have explicit scope and resource budgets; configuration comparisons use the same required load. Missing or invalid measurements mean **Insufficient data**.

The optimization scope covers one Kubernetes Deployment/container's CPU and replicas, with memory fixed. Recommendations concern tested configurations, with restoration of the original controlled settings as a core requirement.

AI has an advisory role in scenario authoring and analysis. Deterministic rules govern performance verdicts and execution bounds.

## Technology

| Layer | Technology |
| --- | --- |
| Frontend | Next.js, React, TypeScript |
| Backend | Java, Spring Boot, Spring Security |
| Persistence | PostgreSQL, Flyway |
