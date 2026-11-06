# Utter Pulse collector — owner runbook

A one-replica pod running `server/pulse_server.py` (docs/spec.md §18.3): receives the phone's gzip NDJSON
batches on `POST /v1/pulse`, appends records to daily files on a PVC and mirrors every accepted line to stdout.

## Commands

| Step | Command |
|---|---|
| 1 · create the secret (once; never committed) | `kubectl create secret generic utter-pulse-key --from-literal=PULSE_KEY=<same key as the app>` |
| 2 · install the manifest | `kubectl apply -f deploy/pulse/pulse.yaml` |
| 3 · enable (collector up) | `kubectl scale deployment utter-pulse --replicas=1` |
| disable (collector off) | `kubectl scale deployment utter-pulse --replicas=0` |
| pull the data (laptop, no kubectl) | `curl -H 'X-API-Key: <key>' https://<stt-host>/v1/pulse/logs -o pulse.ndjson` — add `?day=YYYYMMDD` for one day (404 when absent) |
| live stream (alternative) | `kubectl logs deploy/utter-pulse -f` — every accepted record as `[accepted] <record>` |
| fallback file copy | `kubectl cp <namespace>/$(kubectl get pod -l app=utter-pulse -o jsonpath='{.items[0].metadata.name}'):/data/logs ./pulse-logs` |
| health | `kubectl logs deploy/utter-pulse --tail=5` (or port-forward 8907 and `GET /health`) |

Down-state is normal: scaled to 0 the ingress answers 502, the phone queues locally and backfills on the
next trigger — nothing is lost (record ids dedupe, so overlap across pulls is free).

## Integration points on your cluster (not in this manifest)

| Point | What to do |
|---|---|
| Ingress | Add path rules to the ingress that fronts the STT domain: `/v1/pulse` (push) and `/v1/pulse/logs` (pull) → Service `utter-pulse:8907`. nginx is a dumb path router here — no rewrite, TLS as-is for that host. |
| ConfigMap trap | The ConfigMap holds a **verbatim copy** of `server/pulse_server.py`, not a symlink: after any edit to that file, re-inline it into `deploy/pulse/pulse.yaml` and `kubectl apply` again — otherwise the pod keeps running stale code. |
| Namespace | The manifests are namespace-less on purpose. Once the owner names the namespace, pin `namespace: <stt-namespace>` on every `metadata:` block (and use it in `kubectl cp`); the secret must be created in that same namespace. |
| Log durability | Logs live on the `utter-pulse-logs` PVC (1 Gi, cluster-default storage class), so the pulled-from files survive reschedules. The stdout stream stays on as the second copy (`PULSE_LOG_STDOUT=1`); insights dedupe by record id, so re-pulls or mixed sources never double-count. |
