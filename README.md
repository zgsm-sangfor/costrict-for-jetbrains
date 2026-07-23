# Costrict

English | [简体中文](README_zh.md)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Node.js](https://img.shields.io/badge/Node.js-18%2B-green.svg)](https://nodejs.org/)
[![JetBrains](https://img.shields.io/badge/JetBrains-IntelliJ%20Platform-orange.svg)](https://www.jetbrains.com/)

> **Run VSCode-based Coding Agents in Other IDE platforms**

Costrict is an innovative cross-platform development tool that enables developers to run VSCode-based coding agents and extensions within JetBrains IDEs (IntelliJ IDEA, WebStorm, PyCharm, etc.) or other IDE platforms.

## 📸 Screenshot

![Costrict Screenshot](docs/screenshot.jpg)

## 🚀 Core Features

- **VSCode Agent Compatibility**: Seamlessly run VSCode-based coding agents in JetBrains IDEs
- **Cross-IDE Development**: Unified agent experience across different IDE platforms

## 🤖 Supported Agents

- **[Roo Code](https://roocode.com)**: Advanced AI-powered coding assistant with intelligent code generation and refactoring capabilities
- **[Cline](https://cline.bot)**: Autonomous coding agent right in your IDE, capable of creating/editing files, executing commands, using the browser, and more with your permission every step of the way.
- **[Kilo Code](https://kilocode.ai)**: Open-source VS Code AI agent

## 🔧 Supported IDEs

### Jetbrains IDEs

Costrict currently supports the following JetBrains IDE series:

- **IntelliJ IDEA** (Ultimate & Community)
- **WebStorm** - JavaScript and TypeScript development
- **PyCharm** (Professional & Community) - Python development
- **PhpStorm** - PHP development
- **RubyMine** - Ruby development
- **CLion** - C/C++ development
- **GoLand** - Go development
- **DataGrip** - Database development
- **Rider** - .NET development
- **Android Studio** - Android development

> **Note**: Requires JetBrains IDE version 2023.3 or later for optimal compatibility.

## 🏗️ Architecture

```mermaid
graph TB
    subgraph "JetBrains IDE"
        A[JetBrains Plugin<br/>Kotlin]
        B[UI Integration]
        C[Editor Bridge]
    end

    subgraph "Extension Host"
        D[Node.js Runtime]
        E[VSCode API Layer]
        F[Agent Manager]
    end

    subgraph "VSCode Agents"
        G[Coding Agent]
    end

    A <-->|RPC Communication| D
    B --> A
    C --> A

    E --> D
    F --> D

    G --> E
```

**Architecture Components**:

- **JetBrains Plugin**: Kotlin-based IDE plugin for JetBrains IDE integration
- **Extension Host**: Node.js runtime environment providing VSCode API compatibility layer
- **RPC Communication**: High-performance inter-process communication for real-time data exchange
- **VSCode Agents**: Various coding agents and extensions developed for the VSCode platform

## 📦 Installation

### [Download from JetBrains Marketplace](https://plugins.jetbrains.com/plugin/28068-Costrict) (Recommended)

**Recommended Method**: We recommend downloading and installing the plugin from JetBrains Marketplace first, as this is the most convenient and secure installation method.

1. **Online Installation**:

   - Open your JetBrains IDE (IntelliJ IDEA, WebStorm, PyCharm, etc.)
   - Go to `Settings/Preferences` → `Plugins`
   - Search for "Costrict" in the `Marketplace` tab
   - Click the `Install` button
   - Restart your IDE when prompted

2. **Verify Installation**: After restart, you should see the Costrict plugin in your IDE's plugin list

### Download from GitHub Releases

You can download the pre-built plugin from our GitHub releases page:

1. **Download Plugin**: Visit the [GitHub Releases](https://github.com/wecode-ai/Costrict/releases) page and download the latest plugin file (`.zip` format)

2. **Install in JetBrains IDE**:

   - Open your JetBrains IDE (IntelliJ IDEA, WebStorm, PyCharm, etc.)
   - Go to `Settings/Preferences` → `Plugins`
   - Click the gear icon ⚙️ and select `Install Plugin from Disk...`
   - Select the downloaded `.zip` file
   - Restart your IDE when prompted

3. **Verify Installation**: After restart, you should see the Costrict plugin in your IDE's plugin list

### Enable Auto-Update (Recommended for Internet Users)

If you can reach the public internet, configure a custom plugin repository once. After that, Costrict checks for and prompts upgrades inside the IDE just like a Marketplace plugin — no manual zip download needed.

1. **Add the update channel**:

   - Open your JetBrains IDE, go to `Settings/Preferences` → `Plugins`
   - Click the gear icon ⚙️ → `Manage Plugin Repositories...`
   - Click `+`, paste the URL below and save:

     ```
     https://zgsm-sangfor.github.io/costrict-for-jetbrains/updatePlugins.xml
     ```

2. **Check for updates**: Restart the IDE or click `Check for updates` on the Plugins page. When a newer version is available, the IDE shows an upgrade prompt.

> Intranet / offline environments do not need this channel — keep installing the zip manually via "Download from GitHub Releases" above.

### Build from Source

#### Prerequisites

- Node.js v20+
- JetBrains IDE 2023.3+
- Git
- JDK 17+

#### Build Steps

```bash
# 1. Clone the repository
git clone https://github.com/your-org/Costrict.git
cd Costrict

# 2. Setup development environment
./scripts/setup.sh

# 3. Build the project
./scripts/build.sh

# 4. Install plugin
# Plugin file located at: jetbrains_plugin/build/distributions/
# In IDE: Settings → Plugins → Install Plugin from Disk
```

#### Development Mode

```bash
# Start extension host in development mode
cd extension_host
npm install
npm run dev

# Run JetBrains plugin in development mode
cd jetbrains_plugin
./gradlew runIde
```

## 👥 Developer Information

### Project Structure

```
Costrict/
├── extension_host/          # Node.js Extension Host
│   ├── src/                # TypeScript source code
│   │   ├── main.ts         # Main entry point
│   │   ├── extensionManager.ts  # Extension lifecycle management
│   │   ├── rpcManager.ts   # RPC communication layer
│   │   └── webViewManager.ts    # WebView support
│   └── package.json        # Node.js dependencies
├── jetbrains_plugin/       # JetBrains Plugin
│   ├── src/main/kotlin/    # Kotlin source code
│   │   └── com/sina/weibo/agent/
│   │       ├── core/       # Core plugin functionality
│   │       ├── actions/    # IDE actions and commands
│   │       ├── editor/     # Editor integration
│   │       └── webview/    # WebView support
│   └── build.gradle.kts    # Gradle build configuration
└── scripts/                # Build and utility scripts
```

### Technology Stack

- **Extension Host**: Node.js 18+, TypeScript 5.0+
- **JetBrains Plugin**: Kotlin 1.8+, IntelliJ Platform 2023.3+
- **Communication**: RPC over Unix Domain Sockets/Named Pipes
- **Build Tools**: npm/pnpm, Gradle, Shell scripts

### Known Issues

For a list of known issues and common problems, please see [Known Issues](docs/KNOWN_ISSUES.md).

### Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Make your changes and add tests
4. Run tests: `./scripts/test.sh`
5. Submit a pull request

## 👥 Contributors

We thank all the contributors who have helped make this project better:

### 🌟 Core Contributors

- **[Naituw](https://github.com/Naituw)** - _Project Architect_
- [wayu002](https://github.com/wayu002)
- [joker535](https://github.com/joker535)
- [andrewzq777](https://github.com/andrewzq777)
- [debugmm](https://github.com/debugmm)
- [Micro66](https://github.com/Micro66)
- [qdaxb](https://github.com/qdaxb)

### 🚀 Contributors

- [junbaor](https://github.com/junbaor)
- [aheizi](https://github.com/aheizi)
- [Adam Hill](https://github.com/adamhill)

### License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

### Maintainers

- **Organization**: WeCode-AI Team, Weibo Inc.
- **Contact**: [GitHub Issues](https://github.com/wecode-ai/Costrict/issues)
- **Website**: [https://weibo.com](https://weibo.com)

---

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=wecode-ai/Costrict&type=Date)](https://www.star-history.com/#wecode-ai/Costrict&Date)
**Made with ❤️ by WeCode-AI Team**
