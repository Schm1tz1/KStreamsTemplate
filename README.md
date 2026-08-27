# Kafka Streams Template App

A minimal Kafka Streams application intended as a starting point for building
stream-processing apps. The processing logic itself is intentionally a no-op stub — the
value of this repo is the surrounding scaffolding: CLI config handling, properties
loading, metrics wiring, and the Docker/Kubernetes deployment setup.

## Architecture

```
KStreamsTemplateApp (picocli CLI entrypoint)
  -> PipelineConfigTools (builds Properties)
  -> StreamsPipeline (builds and runs the Topology)
  -> ExampleStreamProcessor (per-record Processor API logic)
```

- **`KStreamsTemplateApp`** — picocli `Runnable` main class. Parses `-c/--config-file`,
  `-C/--additional-config-file`, and `--enable-monitoring-interceptor`, then delegates to
  `PipelineConfigTools` and `StreamsPipeline`.
- **`PipelineConfigTools`** — builds the `Properties` used to configure Kafka Streams:
  starts from hardcoded defaults (`localhost:9092`, String/String serdes,
  `auto.offset.reset=earliest`), then layers on one or more properties files in order.
  `addMonitoringInterceptorConfig` adds Confluent Control Center monitoring interceptor
  config for producer/consumer. `getPropertyChecked` is used to require mandatory
  properties and fails fast if one is missing.
- **`StreamsPipeline`** — builds the `Topology` using the **Processor API** (not the DSL,
  apart from the initial `stream()`/`to()` calls): reads `streamsApp.inputTopic` /
  `streamsApp.outputTopic` from properties (mandatory), wires
  `.process(() -> new ExampleStreamProcessor())` between them with String/String serdes,
  and runs the resulting `KafkaStreams` instance with a shutdown hook (`CountDownLatch`)
  for graceful Ctrl-C handling.
- **`ExampleStreamProcessor`** — implements `Processor<String, String, String, String>`.
  This is the extension point for actual business/filtering logic — currently it just
  forwards records unchanged while recording custom KafkaStreams metrics sensors
  (`processor-in`, `processor-out`) via `StreamsMetrics.addRateTotalSensor`. When adding
  real processing logic, this is the class to extend, along with adding any new mandatory
  config keys via `PipelineConfigTools.getPropertyChecked`.

## Build

- Compile: `mvn compile`
- Package (runs tests): `mvn package`
- Docker image build (via fabric8 docker-maven-plugin, uses `src/main/docker`):
  `mvn package docker:build`

Java target is set via `<java.version>` in `pom.xml` (currently `11`); CI builds with
JDK 11 (`.github/workflows/maven.yml`) and releases with JDK 17
(`.github/workflows/maven-publish.yml`).

The maven-assembly-plugin block in `pom.xml` is bound to the `package` phase, so every
`mvn package` also produces `target/KStreamsTemplate-<version>-jar-with-dependencies.jar`
— a single-file "fat jar" alternative to the lib-folder classpath approach used by the
Docker image.

## Testing

- Run all tests: `mvn test` (also runs as part of `mvn package`)
- Run a single test class: `mvn test -Dtest=StreamsPipelineTest`
- `StreamsPipelineTest` uses Kafka Streams' `TopologyTestDriver` (topology test driver /
  TTD) to pipe records through the built topology in-process, without a running broker.

## Application Configuration

No properties file is committed to the repo. At runtime, Kafka Streams `Properties` are
assembled by `PipelineConfigTools`:

1. Hardcoded defaults (`bootstrap.servers=localhost:9092`, String/String serdes,
   `auto.offset.reset=earliest`, `application.id=kstreams-template`).
2. One or more properties files, applied in order, passed via `-c/--config-file` and
   `-C/--additional-config-file`.

Two application-specific properties are mandatory and must be supplied by a config file:

```properties
streamsApp.inputTopic=input-topic
streamsApp.outputTopic=output-topic
```

Example configuration files are provided under `examples/`:

- `streams_plaintext.properties` — a plain, unauthenticated broker.
- `streams_ccloud.properties` — SASL/PLAIN over SSL, as used for Confluent Cloud.
- `streams_oauth_ssl.properties` — SASL/OAUTHBEARER against an OIDC-compatible token
  endpoint (client_credentials grant), plus a PEM truststore for a self-signed broker
  cert.

All three enable `processing.guarantee=exactly_once_v2` and `acks=all` by default.

Pass `--enable-monitoring-interceptor` to add the Confluent Control Center monitoring
interceptors to the producer/consumer configs (not needed/used for the Confluent Cloud
example).

Run directly with Maven/Java, e.g.:

```bash
java -jar target/KStreamsTemplate-<version>-jar-with-dependencies.jar \
  -c examples/streams_plaintext.properties
```

## Deployment, Running

### Docker

`mvn package docker:build` builds `schmitzi/kstreams-template:<version>` (see the
`docker-maven-plugin` config in `pom.xml`), based on Alpine + OpenJDK 11 JRE
(`src/main/docker/Dockerfile`). The container's `run_application.sh`
(`src/main/docker/scripts/run_application.sh`) starts the JVM with the JMX Prometheus
javaagent and expects the app's properties file mounted at
`/app/config/kstreams.properties` — matching the local dev volume bind configured for
`docker:build`/`docker:start` (`examples/streams_ccloud.properties`).

A fat jar (`mvn package`, see Build above) can be used instead of the lib-folder
classpath approach for single-file deployments.

### Kubernetes

See `k8s/README.md` for full details. Two paths are provided:

- `k8s/app` — the normal path: build & push the Docker image above, then
  `kubectl apply -f k8s/app/kstreams-app.yaml`. Includes JMX/Prometheus metrics wiring
  and startup/liveness/readiness probes on the metrics port.
- `k8s/fatjar` — for clusters where pushing a custom image isn't possible: runs the fat
  jar on a stock JDK image via a `PersistentVolumeClaim`, loaded with
  `k8s/fatjar/load-and-run.sh`. Trades away JMX metrics and single-command rollout for
  not needing registry access.

Both supply `kstreams.properties` via a Kubernetes `Secret` rather than baking it into
the image.

## Log Format

- Logging: log4j via slf4j (`slf4j-log4j12`), default config in
  `src/main/resources/log4j.properties` (INFO level to STDOUT).
- Overridable at runtime by passing a config file on the command line:
  ```bash
  java -Dlog4j.configuration=file:/path/to/log4jconfig.properties \
    -jar target/KStreamsTemplate-<version>-jar-with-dependencies.jar -c ...
  ```

## Metrics

- `ExampleStreamProcessor` registers two custom rate/total sensors, `processor-in` and
  `processor-out`, exposed like any other Kafka Streams metric via JMX.
- The Docker image launches the JVM with the Prometheus JMX exporter javaagent
  (`jmx_prometheus_javaagent`) on port 1234, configured via
  `src/main/docker/config/jmx_exporter_kafka_streams.yml`. In Kubernetes (`k8s/app`),
  this port backs the startup/liveness/readiness probes and is scraped via the headless
  `kstreams-template-metrics` `Service`.
- No metrics are exposed when running outside the Docker image/without the javaagent.
