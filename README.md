# Sensex Option Trader Backend

Spring Boot 3.3 + Java 21 backend for Sensex options trading predictions.

## Quick Start

```bash
docker-compose up postgres redis -d
mvn clean compile
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Server starts at http://localhost:8080

## Architecture
- Main Backend: Spring Boot (this repo)
- ML Service: Python FastAPI (separate repo)
- Communication: gRPC
- Database: PostgreSQL + TimescaleDB
- Cache: Redis