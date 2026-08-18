# 🦅 Karani Webhook Orchestrator

**Karani** es un motor de orquestación, enrutamiento y transformación de Webhooks de alto rendimiento y ultra-resiliente, diseñado en Kotlin y construido sobre Ktor. Permite recibir cargas útiles (payloads), validar su autenticidad criptográfica en tiempo de ejecución, aplicar filtros dinámicos (Ingress Rules), re-mapear estructuras (Payload Transformation) y despacharlos en paralelo hacia múltiples destinos (HTTP, Kafka, gRPC, WebSockets) con políticas avanzadas de reintento.

---

## 🏗️ Arquitectura y Flujo de Datos

```mermaid
sequenceDiagram
    autonumber
    actor Cliente as Emisor de Webhook
    participant Ingester as HTTP Ingester (Karani)
    participant DB as Config DB (Exposed)
    participant Engine as Rules & Transformation Engine
    participant Dispatcher as Async Dispatcher
    participant Destination as Destino Final (HTTP/Kafka/WS)

    Cliente->>Ingester: POST /webhook/{path} (Headers + Payload)
    Note over Ingester: Validar Rate Limiter (429)<br/>Validar Max Body Size (413)
    Ingester->>DB: Obtener Configuración & Llaves
    DB-->>Ingester: Endpoint Config (ACL, Secret, SecurityType)
    Note over Ingester: Validar Firma (HMAC_SHA256 / ECDSA_SHA256)
    Ingester->>Engine: Evaluar Ingress Rules
    Note over Engine: Evaluar JSONPath/XPath/Regex.<br/>Determinar Acción (ALLOW/DENY/CUSTOM)
    Engine-->>Ingester: Resultado (Ej: Responder Challenge o Aceptar)
    Ingester-->>Cliente: 202 Accepted (o Respuesta Custom)
    
    rect rgb(240, 248, 255)
        Note over Dispatcher: Ejecución Asíncrona en Coroutines
        Ingester-)Dispatcher: Despachar Evento
        Dispatcher->>Engine: Aplicar Plantillas de Transformación (${$.campo})
        Dispatcher->>Destination: Enviar Mensaje (HTTP/Kafka/WS)
        Note over Dispatcher: Si falla -> Reintento con Backoff Exponencial (delay)
        Dispatcher->>DB: Guardar Auditoría (Event + DispatchAttempts)
    end
```

---

## ⚡ Características Clave

1. **Configuración Dinámica Completa (CRUD):** Registro y modificación en caliente de endpoints, reglas de enrutamiento y destinos mediante una API REST protegida por API Key.
2. **Protección Perimetral y Resiliencia:**
   * **Rate Limiting:** Límite configurable de peticiones por minuto (RPM) con ventana móvil para mitigar spammers.
   * **Payload Size Limit:** Restringe el tamaño máximo del body (evitando ataques de desbordamiento de memoria `OOM`).
3. **Capa de Seguridad Avanzada:**
   * Handshakes dinámicos y firmas criptográficas (`HMAC-SHA256`, `ECDSA-SHA256` con resolución AES de secretos).
4. **Reglas de Filtrado y Transformación:**
   * Enrutamiento inteligente a destinos específicos basado en expresiones (JSONPath, XPath, Regex).
   * Remapeo de estructuras mediante plantillas de transformación dinámicas (ej: `{"id": "${$.order.id}"}`).
5. **Tolerancia a Fallos Operativos:**
   * Reintentos automáticos configurables por destino con políticas de **Backoff Exponencial**.
   * Registro completo de auditoría y detalles de intentos para depuración rápida.

---

## 🚀 Guía de Uso Rápido (Paso a Paso)

### 1. Crear un Endpoint Dinámico con Límites de Tasa
Registra un nuevo endpoint que acepte máximo 10 KB por payload y limite las peticiones a un máximo de 60 por minuto.

```bash
curl -X POST http://localhost:8080/api/v1/config/endpoints \
  -H "Content-Type: application/json" \
  -H "X-API-Key: tu-super-token-de-admin" \
  -d '{
    "name": "Pasarela Stripe",
    "path": "v1/stripe/payments",
    "enabled": true,
    "persistEvents": true,
    "defaultAction": "ALLOW",
    "securityType": "NONE",
    "rateLimitRpm": 60,
    "maxBodySizeBytes": 10240
  }'
```
*Respuesta:*
```json
{
  "status": "created",
  "id": "e2a6d71b-3f2c-49a8-9d7a-8f1b2c3d4e5f"
}
```

### 2. Registrar una Regla de Handshake (Custom Response)
Configura una regla para responder dinámicamente a los desafíos de autenticación `GET` (por ejemplo, el parámetro de consulta `challenge` que envía Slack o Facebook).

```bash
curl -X POST http://localhost:8080/api/v1/config/endpoints/e2a6d71b-3f2c-49a8-9d7a-8f1b2c3d4e5f/rules \
  -H "Content-Type: application/json" \
  -H "X-API-Key: tu-super-token-de-admin" \
  -d '{
    "priority": 1,
    "source": "HEADER",
    "expression": "query:challenge",
    "operator": "EXISTS",
    "action": "CUSTOM",
    "customResponse": {
      "statusCode": 200,
      "headers": {
        "Content-Type": "text/plain"
      },
      "bodyTemplate": "${query:challenge}"
    }
  }'
```

### 3. Registrar un Destino con Backoff de Reintento
Agrega un destino HTTP. Si el servidor destino devuelve un error, Karani reintentará el envío hasta 3 veces esperando 1s en el primer fallo, luego 2s y finalmente 4s de forma exponencial.

```bash
curl -X POST http://localhost:8080/api/v1/config/endpoints/e2a6d71b-3f2c-49a8-9d7a-8f1b2c3d4e5f/destinations \
  -H "Content-Type: application/json" \
  -H "X-API-Key: tu-super-token-de-admin" \
  -d '{
    "name": "Backend Servidor Central",
    "type": "HTTP",
    "enabled": true,
    "settings": {
      "url": "https://api.miempresa.com/v1/receiver",
      "retryCount": "3",
      "retryIntervalMs": "1000",
      "retryBackoffMultiplier": "2.0"
    },
    "transformationTemplate": "{\"evento_id\": \"${$.id}\", \"monto\": \"${$.data.amount}\"}"
  }'
```

### 4. Recibir el Webhook y Transformar el Payload
Envía un webhook simulado al endpoint recién configurado:

```bash
curl -X POST http://localhost:8080/webhook/v1/stripe/payments \
  -H "Content-Type: application/json" \
  -d '{
    "id": "evt_998877",
    "data": {
      "amount": "150.00"
    }
  }'
```
*Respuesta inmediata al cliente:* `202 Accepted`

*Transformación y Envío Asíncrono:*
El backend receptor (`miempresa.com/v1/receiver`) recibirá de forma asíncrona la estructura mapeada:
```json
{
  "evento_id": "evt_998877",
  "monto": "150.00"
}
```

### 5. Consultar Auditoría Detallada e Intentos
Consulta el estado de despacho y el historial detallado de reintentos de un webhook mediante su ID único devuelto en la ingesta.

```bash
curl -X GET http://localhost:8080/api/v1/events/evt_998877 \
  -H "X-API-Key: tu-super-token-de-admin"
```
*Respuesta:*
```json
{
  "id": "evt_998877",
  "endpointId": "e2a6d71b-3f2c-49a8-9d7a-8f1b2c3d4e5f",
  "status": "SUCCESS",
  "receivedAt": "2026-08-14T20:10:05Z",
  "contentType": "application/json",
  "payload": {"id": "evt_998877", "data": {"amount": "150.00"}},
  "attempts": [
    {
      "id": "att_001",
      "destinationId": "dest-1",
      "status": "FAILED",
      "attemptNumber": 1,
      "responseStatusCode": 503,
      "errorMessage": "Service Unavailable",
      "executedAt": "2026-08-14T20:10:06Z"
    },
    {
      "id": "att_002",
      "destinationId": "dest-1",
      "status": "SUCCESS",
      "attemptNumber": 2,
      "responseStatusCode": 200,
      "errorMessage": null,
      "executedAt": "2026-08-14T20:10:08Z"
    }
  ]
}
```

---

## 🛠️ Ejecución y Despliegue

### Local (Gradle)
```bash
# Correr pruebas unitarias e integración
./gradlew test

# Levantar el servidor localmente
./gradlew run
```

### Docker y Docker Compose (Producción)
Compilar la imagen de producción optimizada multi-stage:
```bash
docker build -t karani-orc:latest -f docker/Dockerfile .
```

Levantar el entorno completo orquestado con base de datos PostgreSQL:
```bash
cd docker
docker-compose up -d
```

---

## 📝 Licencia
Este proyecto es software libre bajo la licencia MIT.
