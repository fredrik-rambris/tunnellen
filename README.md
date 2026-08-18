# Tunnellen

![Build Status](https://github.com/fredrik-rambris/tunnellen/actions/workflows/java-ci.yml/badge.svg)

Tunnellen is a tool to manage port forwards to Kubernetes resources. It allows you to easily set up and manage port forwarding configurations for different environments and services.

## Features

- Manage port forwards for multiple Kubernetes contexts
- Group port forwards by environment (e.g., dev, test, prod)
- Two connection modes per tunnel: a direct `kubectl port-forward` per tunnel, or a shared SOCKS5 proxy per cluster (one `kubectl port-forward` per cluster, all its tunnels relayed through it)
- Add new tunnels from the web UI by browsing real Kubernetes services in a cluster
- Edit or delete existing tunnels from the web UI, with staged changes you explicitly save or discard
- Bulk-convert existing direct-port-forward tunnels to SOCKS, from the CLI or the web UI
- Automatically start port forwards on startup
- Web UI with live cluster-tunnel status

## Requirements

- JDK 25. This repo ships an `.sdkmanrc` — if you use [sdkman](https://sdkman.io/), `sdk env install` picks up the right version automatically.
- `kubectl` on your `PATH`, configured with the contexts you want to forward to.

## Usage

1. Clone the repository:
    ```sh
    git clone https://github.com/fredrik-rambris/tunnellen.git
    cd tunnellen
    ```

2. Build the project:
    ```sh
    mvn clean package
    ```

3. Run the tool:
    ```sh
    java -jar target/tunnellen-<version>.jar
    ```

4. Open the web UI at `http://127.0.0.1:3000/list` (or whatever port you've configured).

## Configuration

All options are read from `forwards.yaml` in the working directory by default. This can be overridden by
using the command line option `--config=/path/to/forwards.yaml`.

Top-level keys:

| Key                  | Meaning                                                                                      |
|-----------------------|-----------------------------------------------------------------------------------------------|
| `port`               | Port the web UI listens on (default `3000`)                                                   |
| `killProc`           | Windows only: kill whatever process is already using `port` before binding                    |
| `keepAliveInterval`  | How often tunnels are health-checked and restarted if down                                     |
| `refreshInterval`    | Auto-refresh interval for the `/list` page (`0` disables it)                                   |
| `groups`             | Named environments (e.g. `prod`, `test`, `dev`) — these become the columns in the tunnel grid  |
| `socksPodSuffix`     | Identifies you to cluster SOCKS proxy pods; see [SOCKS mode](#socks-mode-shared-cluster-proxy) |
| `portForwards`       | The list of tunnels — see below                                                                |

### Each tunnel (`portForwards` entry)

| Key                          | Meaning                                                                                                    |
|-------------------------------|--------------------------------------------------------------------------------------------------------------|
| `context`                    | The `kubectl` context to forward through                                                                     |
| `target`                     | What to forward, e.g. `service/my-service` (omit for `mode: socks` entries — see below)                     |
| `namespace`                  | Kubernetes namespace of the target (default `default`)                                                       |
| `localPort` / `remotePort`   | Local port to listen on and the remote port on the target                                                    |
| `startOnStartup`             | Whether to start this tunnel automatically when tunnellen starts                                              |
| `type`                       | `http` or `database` — purely a presentation hint (which icon/link the web UI shows), no effect on transport |
| `group`                      | Which `groups` column this tunnel belongs to                                                                  |
| `mode`                       | `port-forward` (default), `socks`, or `service` — see below                                                  |
| `dependsOn`                  | For `mode: service` only: the context of the `mode: socks` cluster tunnel it rides on                        |
| `database`                   | For `type: database`: `kind` (`postgresql`/`mysql`), `name`, `username` — used to generate an IntelliJ datasource |
| `socksUsername` / `socksPassword` | For `mode: socks` only: static credential override (see below); omit to auto-generate per run          |

## SOCKS mode (shared cluster proxy)

Instead of one `kubectl port-forward` process per tunnel, a cluster can run a single shared SOCKS5 proxy
(an ephemeral `serjs/go-socks5-proxy` pod), and every tunnel for that cluster relays through it via one
`kubectl port-forward` to that pod. This means N tunnels into a cluster cost one forwarded connection, not N.

A cluster's proxy is a `mode: socks` entry — it has no `target`, just a `context`, `group`, and (optionally)
a `localPort` to pin which local port the SOCKS proxy itself listens on:

```yaml
socksPodSuffix: yourname   # see below

portForwards:
  - context: bhg-prod
    mode: socks
    group: prod
    localPort: 9990         # optional; auto-assigned from 19000 upward if omitted

  - context: bhg-prod
    mode: service            # rides the bhg-prod cluster tunnel above
    dependsOn: bhg-prod
    target: service/my-service
    namespace: default
    localPort: 11400
    remotePort: 8080
    type: http
    group: prod
    startOnStartup: true
```

A `mode: service` tunnel with no matching `mode: socks` entry for its `dependsOn` context falls back to a
direct `kubectl port-forward` automatically (with a warning logged) rather than failing outright.

**`socksPodSuffix`** identifies you to the cluster's SOCKS proxy pod, named `tunnellen-socks-<suffix>` —
this exists so multiple developers sharing a cluster each get their own pod instead of colliding on the same
name. If omitted, it falls back to the `user.name` system property; if neither is set, startup fails with a
clear error rather than silently picking something generic. Set it to `*` to get a fresh random suffix every
run instead of a stable one.

**Credentials**: by default, SOCKS5 username/password are generated randomly each run and held only in
memory. Set `socksUsername`/`socksPassword` on a `mode: socks` entry for a stable, static pair instead
(e.g. for scripted use).

**Cluster tunnels only start at tunnellen startup** — adding a new `mode: socks` entry (via config edit, the
migration helper, or the web UI) takes effect after a restart, not immediately.

## Web UI

- **`/list`** — the tunnel grid, one row per service, one column per group/environment. Services present in
  2+ environments get the main grid; single-environment services are listed separately below it.
- **`+ Add service`** — browses real Kubernetes services in a chosen context (via `kubectl get service -o
  json`) and creates the prod/test/dev triad of tunnels for the one you pick, choosing SOCKS or direct
  port-forward.
- **`Edit tunnels`** toggles edit mode: every tunnel gets an edit (✏️) and delete (🗑) icon. Edits/deletes take
  effect immediately (the tunnel actually restarts/stops) but are **staged** — nothing is written to
  `forwards.yaml` until you click **Save changes**, or **Undo** to discard everything and reload from disk.
- **Migrate `<context>`** buttons (edit mode) convert every direct-port-forward tunnel in that context to
  SOCKS in one click — the web UI counterpart to `--migrate-to-socks` below.
- **`/q/<service>`** and **`/q/<service>/<context>`** — plain-text port lookup, handy for scripting:
  `curl localhost:3000/q/my-service` lists `<context>/<port>` per environment;
  `curl localhost:3000/q/my-service/bhg-dev` returns just the port number.

## Migrating existing tunnels to SOCKS

To bulk-convert `mode: port-forward` tunnels to SOCKS without going through the web UI one at a time:

```sh
# Preview only, writes nothing:
java -jar tunnellen.jar --migrate-to-socks bhg-dev --migrate-dry-run

# Convert everything in bhg-dev:
java -jar tunnellen.jar --migrate-to-socks bhg-dev

# Convert only services matching "campaigntool" in bhg-dev and bhg-test:
java -jar tunnellen.jar --migrate-to-socks bhg-dev,bhg-test --migrate-service campaigntool

# Convert every context that has at least one tunnel:
java -jar tunnellen.jar --migrate-to-socks all
```

This creates the `mode: socks` cluster-tunnel entry for a context if it doesn't have one yet, converts the
matched tunnels to `mode: service`, and writes the result back to your config file — then exits without
starting the server. Safe to re-run: an already-migrated tunnel or a filter that matches nothing is reported
plainly, not treated as an error. As with any config change, a new cluster tunnel doesn't actually start
until tunnellen is next started.

## IntelliJ database tunnels

For `type: database` tunnels, the web UI's 🗄 icon generates an IntelliJ Database datasource definition
(paste into IntelliJ's "New > Data Source > paste") pointed at the local forwarded port.
