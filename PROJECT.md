# Karani Webhook Orchestrator - Documentación del Proyecto

Karani es un orquestador de webhooks asíncrono y resiliente, desarrollado en Kotlin y potenciado por Ktor. Está diseñado bajo principios de Arquitectura Limpia (Clean Architecture) para procesar e ingerir notificaciones y redirigirlas dinámicamente hacia múltiples destinos en paralelo.

---

## 1. Arquitectura del Sistema
El backend está estructurado siguiendo un diseño hexagonal desacoplado:
* **`domain/`**: Entidades puras de negocio (`WebhookEvent`, `IngressRule`, `Destination`, `DispatchAttempt`).
* **`ports/`**:
  * **API (Entrada)**: Casos de uso (`WebhookIngestUseCase`, `EventDispatcherUseCase`).
  * **SPI (Salida)**: Puertos de infraestructura (`ConfigurationRepository`, `EventRepository`, `EventPublisher`).
* **`usecases/`**: Implementación de lógica, filtros y motores de evaluación.
* **`adapters/`**:
  * **http/**: Enrutamiento Ktor (`Routing.kt`) para ingesta síncrona y consultas GET de eventos.
  * **database/**: 
    * `sql`: Persistencia relacional de Exposed (SQLite y PostgreSQL).
    * `mongo`: Persistencia NoSQL para eventos.
    * `composite`: Mirroring / Replicación paralela de base de datos.
  * **clients/**: Publicador HTTP saliente (`HttpEventPublisher.kt`) utilizando Ktor Client CIO.
  * **evaluators/**: Evaluadores dinámicos de cuerpo (JSONPath, XPath, Regex).

---

## 2. Características Principales

### A. Persistencia Híbrida y Compuesta (Replicación en "all" Motores)
El sistema permite configurar bases de datos independientes para la configuración permanente y la bitácora de eventos. Al activar el modo `all`, Karani replica de manera paralela y asíncrona todos los estados y logs en **SQLite, PostgreSQL y MongoDB** a la vez utilizando Coroutines.

### B. Ingesta y Validación Síncrona (Handshakes GET y POST)
* Soporte nativo para handshakes mediante peticiones **`GET`** (ej. Facebook Messenger, Slack, Teams) inyectando los parámetros de consulta directamente en el contexto del motor de reglas utilizando el prefijo `query:`.
* Motor síncrono que decide en tiempo real si responde al proveedor inmediatamente con código HTTP personalizado (`RESPOND`) o encola el webhook asíncronamente en segundo plano (`FORWARD`).

### C. Despacho Asíncrono Paralelo No Bloqueante
El reenvío se ejecuta de forma asíncrona e independiente para cada destino registrado. En lugar de usar locks síncronos de Java (`synchronized`), el flujo utiliza `async`/`awaitAll` nativos de Kotlin, liberando los hilos del pool de ejecución.

### D. Soporte WebSockets en Tiempo Real
* Exposición de un canal WebSocket reactivo en el path `/ws/events`.
* Permite a dashboards y consumidores conectarse indicando un `clientId` en query parameters.
* El despachador distribuye los webhooks en tiempo real a clientes específicos o a todos en modo broadcast.

### E. Integración de Alta Velocidad (Kafka & gRPC)
* **Kafka:** Publicador asíncrono y no bloqueante que despacha cargas útiles a tópicos y llaves parametrizables.
* **gRPC:** Reenvío de baja latencia a microservicios internos mediante llamadas RPC síncronas.

### F. Seguridad Criptográfica & Híbrida (HMAC & ECDSA)
* **SecretResolver:** Resolutor inteligente para llaves y secretos. Soporta llaves en disco (`file://`), variables de entorno (`env://`) o encriptadas en la base de datos (`enc:` vía AES-256).
* **Validación de Firmas:** Soporte simétrico (HMAC SHA-256) y asimétrico con firmas de curvas elípticas (ECDSA SHA-256, como Stark Bank).

### G. Reglas tipo ACL y Procesamiento In-Memory
* **Políticas ACL por Defecto:** Configuración a nivel de endpoint para `DefaultAction.ALLOW` (dejar pasar todo por defecto) o `DefaultAction.DENY` (bloquear todo a menos que una regla lo acepte).
* **Bypass de Persistencia (`persistEvents = false`):** Permite procesar y despachar webhooks de altísimo tráfico en memoria de forma efímera, omitiendo escrituras en base de datos.

### H. APIs REST de Configuración y Auditoría Protegidas
* Endpoints REST para consultar eventos (`GET /api/v1/events`) y registrar configuraciones dinámicamente (`POST /api/v1/config/...`).
* Protegidos mediante autenticación por cabecera: requiere pasar la clave configurada en el header `X-API-Key`. Si no está configurada, las peticiones pasan sin verificar (desactivado).

---

## 3. Guía de Configuración (`application.yaml`)
El archivo de configuración externalizado reside en la raíz del proyecto ([`application.yaml`](file:///Users/diegoa.leon/Documents/dev/realm/karani-orc/application.yaml)) y soporta la inyección de variables de entorno y perfiles múltiples de conexión:

```yaml
karani:
  database:
    config-type: "all"
    events-type: "all"
    
    sqlite:
      - id: "sqlite-1"
        url: "jdbc:sqlite:karani-config-1.db"
    postgres:
      - id: "postgres-1"
        url: "jdbc:postgresql://localhost:5432/karani_db"
    mongodb:
      - id: "mongo-1"
        url: "mongodb://localhost:27017/karani_db"
```
