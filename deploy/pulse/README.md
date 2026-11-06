# Utter Pulse collector — owner runbook

A one-replica pod in namespace `stt-lab` (cluster context `pipeshift-k8s-v1-34-1-cad4c`) running
`server/pulse_server.py` (docs/spec.md §18.3): receives the phone's gzip NDJSON batches on `POST /v1/pulse`,
appends records to daily files on a PVC and mirrors every accepted line to stdout.

## Commands

| Step | Command |
|---|---|
| 1 · create the secret (once; never committed) | `kubectl create secret generic utter-pulse-key --from-literal=PULSE_KEY=<same key as the app>` |
| 2 · install the manifest | `sed "s/<stt-host>/<the private STT host>/" deploy/pulse/pulse.yaml \| kubectl apply -f -` — the host is a placeholder on purpose; the public-repo hygiene gate (spec §10) keeps it out of tracked files |
| 3 · enable (collector up) | `kubectl scale deployment utter-pulse --replicas=1` |
| disable (collector off) | `kubectl scale deployment utter-pulse --replicas=0` |
| pull the data (from a machine with kubeconfig) | `kubectl --context pipeshift-k8s-v1-34-1-cad4c -n stt-lab port-forward deploy/utter-pulse 8907:8907` then `curl -H 'X-API-Key: <key>' http://localhost:8907/v1/pulse/logs -o pulse.ndjson` — add `?day=YYYYMMDD` for one day (404 when absent) |
| live stream (alternative) | `kubectl logs deploy/utter-pulse -f` — every accepted record as `[accepted] <record>` |
| fallback file copy | `kubectl cp stt-lab/$(kubectl get pod -l app=utter-pulse -o jsonpath='{.items[0].metadata.name}'):/data/logs ./pulse-logs` |
| health | `kubectl logs deploy/utter-pulse --tail=5` (or port-forward 8907 and `GET /health`) |

502 on the push path while scaled to 0 is expected: the phone queues locally and backfills on the next
trigger — nothing is lost (record ids dedupe, so overlap across pulls is free).

## Logs stay cluster-internal

| Rule | Detail |
|---|---|
| No external route for logs | The ingress carries only `/v1/pulse` (phone push). `/v1/pulse/logs` is routed nowhere externally — it lives only in-cluster, behind its own X-API-Key check, reachable via port-forward. No logs ingress will be created. |
| No kubeconfig on the pulling machine | A temporary logs ingress — apply a one-path `/v1/pulse/logs` rule, pull, then delete it — is the only sanctioned way to pull from a machine without kubeconfig. Nothing long-lived. |

## Integration points on your cluster

| Point | What to do |
|---|---|
| Ingress | Baked into the manifest: `ingressClassName: nginx`, TLS via secret `stt-lab-tls`, a single Prefix path `/v1/pulse` → Service `utter-pulse:8907` on the STT host. Only the `<stt-host>` placeholder needs substituting at apply time. |
| ConfigMap trap | The ConfigMap holds a **verbatim copy** of `server/pulse_server.py`, not a symlink: after any edit to that file, re-inline it into `deploy/pulse/pulse.yaml` and `kubectl apply` again — otherwise the pod keeps running stale code. |
| PVC is convenience, not backup | The cluster's default StorageClass `openebs-hostpath` is **node-local**: if the pod reschedules to another node, `/data/logs` starts fresh and previously pulled-from files are gone with the old node. `kubectl logs` remains the durable copy, and insights dedupe by record id — re-pulls and mixed sources never double-count. |
| Image pulls | `python:3.12-slim` comes from Docker Hub. On Neysa, an `ErrImagePull` means either pre-pull the image on the node or mirror it to ECR (ECR there needs `imagePullSecrets: aws-ecr-cred` on the pod spec). |
