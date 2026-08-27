# Kubernetes Example

Two ways to run the template app, depending on whether you can push a custom image to
a registry your cluster can pull from:

- **`./app`** — the normal path: build the Docker image and deploy it.
- **`./fatjar`** — for a test cluster where pushing to the registry/artifactory isn't
  allowed: run the fat jar on a stock JDK image instead of a custom-built one.

Both supply `kstreams.properties` via a `Secret` rather than baking it into the image
or committing it to git.

## Deploy (custom image)

1. Build and push an image your cluster can pull:
   ```bash
   mvn package docker:build
   # tag/push to a registry reachable from your cluster, then update
   # `image:` in kstreams-app.yaml accordingly
   ```
2. Create the config `Secret` from one of the example properties files (see
   `../examples/`), or your own:
   ```bash
   kubectl create secret generic kstreams-config \
     --from-file=kstreams.properties=../examples/streams_ccloud.properties
   ```
3. Apply the manifest:
   ```bash
   kubectl apply -f app/kstreams-app.yaml
   ```

## Deploy (no registry push — fat jar on a PV)

For a test environment where you can't push a custom image (e.g. a locked-down
artifactory), `./fatjar` runs the app on a stock `eclipse-temurin:17-jre-alpine` image
instead, with the fat jar supplied via a `PersistentVolumeClaim`:

1. Create the config `Secret`, same as above:
   ```bash
   kubectl create secret generic kstreams-config \
     --from-file=kstreams.properties=../examples/streams_ccloud.properties
   ```
2. Run the script — it builds the fat jar if needed (`mvn package`), creates the PVC
   and a short-lived loader pod, `kubectl cp`s the jar onto the PVC, deletes the loader
   pod, then deploys the app:
   ```bash
   ./fatjar/load-and-run.sh
   ```
   Set `NAMESPACE=your-ns` if not deploying to `default`.

This trades away a few things the image-based path gets for free, in exchange for not
needing registry access:
- **No JMX/Prometheus metrics** — the exporter javaagent and its config only exist in
  the custom-built image (`src/main/docker/config`), so there are no probes and no
  metrics `Service` here. This is a test-only fallback, not a production deployment
  path.
- **A manual two-phase rollout** instead of a single `kubectl apply` — updating the jar
  means re-running `load-and-run.sh`, which deletes and recreates the loader pod (the
  Deployment pod itself needs a manual rollout restart to pick up the new jar, since
  the PVC contents changing doesn't trigger one on its own).
- **PVC read/write ordering matters**: the loader pod must be fully deleted before the
  Deployment pod starts, since both mount the same `ReadWriteOnce` PVC — the script
  handles this ordering; don't apply `fatjar/deployment.yaml` by hand while the loader
  pod still exists.

## Notes

- Kafka Streams instances sharing an `application.id` form a consumer group, so
  `spec.replicas` in the `Deployment` can be scaled up to the input topic's partition
  count without any other changes.
- The JMX Prometheus exporter (baked into the image, port 1234) is used for the
  startup/liveness/readiness probes and is exposed via the `kstreams-template-metrics`
  `Service` (headless, with `prometheus.io/scrape` annotations) for scraping — the app
  itself doesn't serve any traffic, so there's no app-facing `Service`.
- The pod runs as a non-root user with a read-only root filesystem
  (`securityContext`), per the Kubernetes [Pod Security
  Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/)
  "restricted" profile; a small `emptyDir` is mounted at `/tmp` for JVM scratch files.
