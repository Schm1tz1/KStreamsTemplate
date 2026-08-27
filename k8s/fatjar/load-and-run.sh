#!/usr/bin/env bash
# Runs the template app in a cluster where pushing a custom image to a registry isn't
# possible: builds the fat jar, uploads it onto a PVC via a short-lived loader pod
# (kubectl cp), then deploys a stock JDK image that runs it straight from the PVC.
# See ../README.md for the full explanation.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
NAMESPACE="${NAMESPACE:-default}"
LOADER_POD="kstreams-fatjar-loader"
KUBECTL=(kubectl --namespace "$NAMESPACE")

jar_path="$(find "$PROJECT_ROOT/target" -maxdepth 1 -name '*-jar-with-dependencies.jar' 2>/dev/null | head -n1)"
if [ -z "$jar_path" ]; then
  echo "No fat jar found under target/, building it..."
  (cd "$PROJECT_ROOT" && mvn -q -DskipTests package)
  jar_path="$(find "$PROJECT_ROOT/target" -maxdepth 1 -name '*-jar-with-dependencies.jar' | head -n1)"
fi
echo "Using $jar_path"

echo "Creating PVC and loader pod..."
"${KUBECTL[@]}" apply -f "$SCRIPT_DIR/pvc-and-loader.yaml"

cleanup() {
  "${KUBECTL[@]}" delete pod "$LOADER_POD" --ignore-not-found --wait=true >/dev/null
}
trap cleanup EXIT

echo "Waiting for loader pod to be ready..."
"${KUBECTL[@]}" wait --for=condition=Ready "pod/$LOADER_POD" --timeout=120s

echo "Copying fat jar onto the PVC..."
"${KUBECTL[@]}" cp "$jar_path" "$NAMESPACE/$LOADER_POD:/data/app.jar"

echo "Removing loader pod so the Deployment pod can mount the PVC..."
trap - EXIT
cleanup

echo "Deploying application..."
"${KUBECTL[@]}" apply -f "$SCRIPT_DIR/deployment.yaml"

echo "Done. Follow logs with:"
echo "  kubectl --namespace $NAMESPACE logs deploy/kstreams-fatjar -f"
