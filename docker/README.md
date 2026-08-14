# Karani Orchestrator - Docker Guide

This directory contains configuration files to run and test Karani Webhook Orchestrator in containerized environments.

## How to Build the Image

Run the following command from the project root:

```bash
docker build -t karani-orc:latest -f docker/Dockerfile .
```

## How to Run locally with Docker Compose

To launch Karani along with a PostgreSQL instance as the database back-end:

```bash
cd docker
docker-compose up -d --build
```

The application will be exposed on port `8080` (e.g., `http://localhost:8080`).
