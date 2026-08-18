# MAS — Mobile Automation System

A general-purpose automation workflow platform that runs **locally on Android**: visual flow editing + background virtual display + scheduled tasks + custom node extension.

No PC, no emulator. Full system-level automation via Shizuku/Root.

## Features

|  | Feature | Description |
|---|---|---|
| 🧩 | **Script Workshop** | Visual node-graph editor: execute / judge / control nodes + links (sequence/yes/no/and/or) + burst handling |
| 🎨 | **Custom Nodes** | Define your own nodes via JSON templates (execute/judge/control, with params & shell command), auto-registered |
| 🖥 | **Background Virtual Display** | Run target apps in a virtual display without disturbing foreground; live preview + touch mapping |
| ⏱ | **Scheduled Tasks** | Auto-start flows on a schedule |
| 🔗 | **Flow Binding** | Bind a config to a workshop flow; updates sync automatically |
| 📋 | **Run Logs** | Full execution logs, service/app status monitoring |

## Quick Start

1. **Workshop**: create flow → add nodes → link → save
2. **Background Tasks**: reference a flow (auto-creates config) or bind (auto-sync)
3. **Run**: FlowEngine executes on the virtual display with live preview & logs

See the ⓘ guide in Workshop for details (paged).

## Build

```bash
./gradlew assembleDebug          # normal build
bash scripts/mobile_build.sh     # on-device (aarch64) build
```

## License

Apache-2.0 etc., see [LICENSE](LICENSE).