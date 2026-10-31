# Towny Bridge

Towny Bridge connects a Velocity proxy to a Paper backend running Towny. It supports town information and bank commands from other backend servers, and routes town spawn commands to the Towny server.

## Features

- `/t`, `/town`, and `/town <name>` show town information.
- `/t residents [town]` and `/t online [town]` show resident lists.
- `/t deposit <whole number|all>` and `/t withdraw <whole number|all>` run town bank transactions.
- `/t spawn` routes the player to their own town spawn; `/t spawn <town>` routes them to the named town's spawn. Towny applies its access rules.
- On the Towny backend, Towny's native commands handle requests.

## Requirements

- Java 21 or newer.
- Velocity 3.5.x and Paper 1.21.11.
- Towny installed on the Paper backend registered as `Towny` in Velocity.
- Towny's settings must permit remote bank actions away from town or bank plots.

## Setup

Install the Velocity JAR from `velocity/build/libs/` into Velocity's `plugins/` directory. Install the Paper JAR from `paper/build/libs/` into the Towny backend's `plugins/` directory, then restart both services.

Build both JARs with Gradle 8.7 or newer and a Java 21 JDK:

```sh
gradle build
```

The Velocity and Paper plugins communicate through an HTTP endpoint on `127.0.0.1:28645`. Run both processes on the same host and make sure the port is available. The endpoint only listens on the loopback interface.

Town bank actions use Towny's native transaction methods, including its economy checks and transaction events. Withdrawals require mayor status or the `towny.command.town.withdraw` permission. Amounts must be whole numbers; `all` uses the available balance.

## Modules

- `velocity/`: Velocity command bridge.
- `paper/`: Paper and Towny integration.

This repository does not include a license file.
