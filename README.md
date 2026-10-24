# ModernMC Towny Bridge

ModernMC Towny Bridge has two parts: a Velocity plugin that intercepts Towny commands while a player is on another backend, and a Paper plugin on the Towny backend that queries Towny and performs town bank transactions.

## Features

- `/t`, `/town`, `/town <name>`: show town information.
- `/t residents [town]` and `/t online [town]`: show resident lists.
- `/t deposit <whole number|all>` and `/t withdraw <whole number|all>`: use the player's town bank while connected to another backend.
- On the Towny backend, Towny's own commands handle requests normally.

## Requirements and setup

- Java 21 or newer.
- Velocity 3.5.x and Paper 1.21.11.
- Towny installed on the Paper backend named `Towny` in the Velocity configuration.
- The Paper plugin's Towny setting must allow bank actions away from town or bank plots for remote transactions.

Install the Velocity JAR from `velocity/build/libs/` into the Velocity `plugins/` directory. Install the Paper JAR from `paper/build/libs/` into the Towny backend's `plugins/` directory. Restart both services after installing or upgrading.

Build both JARs with Gradle 8.7 or newer and a Java 21 JDK:

```sh
gradle build
```

The bridge uses a local HTTP endpoint on `127.0.0.1:28645` between the Velocity process and Towny backend plugin. Keep both processes on the same host; the endpoint is intentionally not exposed on a network interface. The fixed port must be available on the Towny backend host.

Town bank actions are handled by Towny's native transaction methods, including its economy checks and transaction events. Withdrawals also require Towny mayor status or the `towny.command.town.withdraw` permission as seen by Velocity. Amounts are whole numbers; `all` uses the available balance.

## Modules

- `velocity/`: Velocity command bridge.
- `paper/`: Paper and Towny integration.

This repository does not currently include a license file.
# Velocity-Towny-Bridge
