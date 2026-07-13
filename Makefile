COMPOSE = docker compose --env-file .env
DEV_COMPOSE = $(COMPOSE) -f docker-compose.yml -f docker-compose.dev.yml

.PHONY: dev-infra native-backend up ps verify down logs

dev-infra:
	$(DEV_COMPOSE) up -d mysql redis rabbitmq

native-backend:
	./scripts/run-native-backend.sh

up:
	$(COMPOSE) up -d --build

ps:
	$(COMPOSE) ps

verify:
	./scripts/verify-deployment.sh

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f --tail=200
