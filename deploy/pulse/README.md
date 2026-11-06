# Utter Pulse collector — owner runbook

A one-replica pod running `server/pulse_server.py` (docs/spec.md §18.3): receives the phone's gzip NDJSON
batches on `POST /v1/pulse`, appends records to daily files and mirrors every accepted line to stdout.

## Commands

| Step | Command |
|---|---|
| 1 · create the secret (once; never committed) | `kubectl create secret generic utter-pulse-key --from-literal=PULSE_KEY=<same key as the app>` |
| 2 · install the manifest | `kubectl apply -f deploy/pulse/pulse.yaml` |
| 3 · enable (collector up) | `kubectl scale deployment utter-pulse --replicas=1` |
| disable (collector off) | `kubectl scale deployment utter-pulse --replicas=0` |
| stream records | `kubectl logs deploy/utter-pulse -f` |
| pull the daily files | `kubectl cp <namespace>/$(kubectl get pod -l app=utter-pulse -o jsonpath='{.items[0].metadata.name}'):/data/logs ./pulse-logs` |
| health | `kubectl logs deploy/utter-pulse --tail=5` (or port-forward 8907 and `GET /health`) |

Down-state is normal: scaled to 0 the ingress answers 502, the phone queues locally and backfills on the
next trigger — nothing is lost (record ids dedupe, so overlap across pulls is free).

## Integration points on your cluster (not in this manifest)

| Point | What to do |
|---|---|
| Ingress | Add one path rule to the ingress that fronts the STT domain: `/v1/pulse` → Service `utter-pulse:8907`. nginx is a dumb path router here — no rewrite, TLS as-is for that host. |
| Log durability | `/data` is an `emptyDir`, so the on-disk `.ndjson` files die with the pod. The durable copy is the **stdout stream**: `PULSE_LOG_STDOUT=1` is set, every accepted record prints as `[accepted] <record>`, and `kubectl logs` history (or a collector your cluster already runs) is the archive. Insights dedupe by record id, so re-pulling or mixed sources never double-count. |
