# Solar Engine
![GitHub](https://img.shields.io/github/license/Solar-Tweaks/Solar-Engine?style=for-the-badge)
![Maintenance](https://img.shields.io/maintenance/yes/2023?style=for-the-badge)

Engine designed to modify Lunar Client on runtime, easier and faster.
This is intended to be used by/with [Solar Tweaks](https://github.com/Solar-Tweaks/),
however you can use it by yourself using the `-javaagent` [flag](https://docs.oracle.com/javase/7/docs/api/java/lang/instrument/package-summary.html).

## Downloading
You can download a prebuilt artifact from the [releases page](https://github.com/Solar-Tweaks/Solar-Engine/releases).

## Usage
Meant to be used by [our launcher](https://github.com/Solar-Tweaks/Solar-Tweaks). However, manual usage is also possible. Add `-javaagent:/path/to/Solar-Engine.jar=/path/to/config.json` to the JVM arguments of a third party launcher or a self-made one.  

## Features
All features are listed [here](Features.md).

## Building from source
As is standard for a Gradle project, artifacts can be generated with:
```shell
./gradlew build
```
The agent artifact will be generated in the `agent/build/libs` directory.
