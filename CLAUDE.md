# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Tolgee is an open-source localization platform that allows developers to translate their applications in context. It consists of a Spring Boot backend (Kotlin), React frontend (TypeScript), and comprehensive E2E testing suite.

## Commands

### Backend Development
```bash
# Run backend with dev profile
./gradlew server-app:bootRun --args='--spring.profiles.active=dev'

# Run all backend tests
./gradlew test

# Format Kotlin code
./gradlew ktlintFormat

# Check Kotlin code style
./gradlew ktlint
```

### Frontend Development
```bash
# Install dependencies and start frontend
cd webapp && npm ci && npm run start

# Run static analysis
cd webapp
npm run prettier    # Fix formatting issues
npm run tsc         # TypeScript compilation check
npm run eslint      # Run ESLint

# Check for unused exports
npm run unused-exports

# Generate API schemas
npm run schema         # Generate public schemas
npm run billing-schema # Generate billing schemas

# Tolgee CLI operations
npm run load-translations    # Pull translations from Tolgee
npm run check-translations  # Check translation extraction
npm run tag-keys            # Tag translation keys
```

### E2E Testing
```bash
# Run E2E tests (from root)
./gradlew runE2e

# From e2e directory
cd e2e
npm run cy:run    # Run tests headlessly
npm run cy:open   # Open Cypress UI
```

### Database Operations
```bash
# Generate database changelog (after schema changes)
./gradlew diffChangeLog

# If gradle daemon issues occur
./gradlew diffChangeLog --no-daemon
```

### Build & Release
```bash
# Build entire project (includes E2E tests)
./gradlew build

# Build without server (frontend only)
SKIP_SERVER_BUILD=true ./gradlew build

# Package resources
./gradlew packResources
```

## Architecture

### Backend Structure
- **Modular Kotlin/Spring Boot application** with multi-module Gradle setup
- **Main modules:**
  - `server-app`: Main Spring Boot application
  - `data`: Data layer with JPA entities and repositories
  - `api`: API layer controllers and DTOs
  - `security`: Authentication and authorization
  - `testing`: Shared testing utilities
  - `development`: Development utilities
  - `misc`: Miscellaneous utilities

### Frontend Structure
- **React 17** application with **TypeScript**
- **State Management:** Zustand for local state, React Query for server state
- **Styling:** Material-UI v5 with Emotion
- **Routing:** React Router v5
- **Key directories:**
  - `src/component/`: Reusable UI components
  - `src/views/`: Page-level components
  - `src/service/`: API service layer and HTTP clients
  - `src/hooks/`: Custom React hooks
  - `src/globalContext/`: Global application state
  - `src/ee/`: Enterprise Edition features (separate licensing)

### Testing Strategy
- **Backend:** JUnit integration and unit tests
- **E2E:** Cypress tests covering critical user workflows
- **Frontend:** Limited unit testing, primarily E2E coverage

## Key Development Guidelines

### Code Formatting
- **Backend:** Use `./gradlew ktlintFormat` before commits
- **Frontend:** Use `npm run prettier` and ensure `npm run eslint` passes
- Both linting checks run in CI and will fail the build if not passing
- **IMPORTANT:** Always run linting and formatting commands after completing code changes:
  - Run `./gradlew ktlintFormat` to automatically format Kotlin code to project standards
  - Run `./gradlew ktlintCheck` to verify Kotlin code style compliance
  - For frontend changes: run `npm run prettier` and `npm run eslint` in webapp directory

### Database Changes
- Always run `./gradlew diffChangeLog` after modifying JPA entities
- Liquibase manages all database migrations automatically on startup
- Never modify existing changelog files, always generate new ones

### API Development
- Backend uses OpenAPI/Swagger for API documentation
- Frontend generates TypeScript types from OpenAPI specs using `npm run schema`
- API versioning: v1 (legacy) and v2 (current) endpoints coexist

### Translation Workflow
- Application uses Tolgee itself for internationalization
- Translation keys are extracted automatically and tagged with `npm run tag-keys`
- Production translations are managed through Tolgee cloud or self-hosted instance

### Enterprise Edition (EE)
- EE features are conditionally compiled based on license availability
- EE modules are in `ee/` directory with separate licensing
- Use feature flags and conditional compilation patterns for EE functionality

## Development Environment Setup

### Prerequisites
- Java 21
- Node.js 18+
- Docker (for database and E2E tests)

### Configuration
- Create `backend/app/src/main/resources/application-dev.yaml` for local overrides
- Create `webapp/.env.development.local` for frontend environment variables

### Running Tests
- Backend tests require no additional setup
- E2E tests spin up Docker containers automatically via Gradle
- Tests use retry mechanisms in CI (configured in root `build.gradle`)

## Common Patterns

### Error Handling
- Backend uses structured exception handling with proper HTTP status codes
- Frontend uses React Query error boundaries and global error handling

### Business Event Logging
- Automatic logging via `ActivityHolder` for modifying operations
- Manual logging via `BusinessEventPublisher` for custom events
- Frontend logging through `useReportEvent` hooks

### Multi-tenancy
- Organization-based multi-tenancy with project isolation
- Permissions system with role-based and scope-based access control

## Build System Notes
- Uses Gradle with composite builds for backend modules
- Frontend builds are integrated into Gradle via `gradle/webapp.gradle`
- Docker images built through `gradle/docker.gradle`
- Semantic release configuration in root `package.json`