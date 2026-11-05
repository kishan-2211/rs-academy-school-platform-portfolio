# System architecture

See the [architecture overview](../ARCHITECTURE.md) for the web/API layout, domain boundaries, authorization flow, financial history, file handling and deployment model.

The implementation uses a modular monolith and PostgreSQL as the system of record. The browser does not query databases or object storage directly. External provider adapters and background work remain controlled by their application and deployment settings.
