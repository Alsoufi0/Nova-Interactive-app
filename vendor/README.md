# Vendor Downloads

Downloaded project support files for Nova People Messenger.

## OrionStar AgentOS SDK

Source archive:

```text
vendor/agentos-sdk-main.zip
vendor/agentos-sdk-main/
```

Important files:

```text
vendor/agentos-sdk-main/Agent/v0.4.5/AgentOS_SDK_Doc_v0.4.5_en.md
vendor/agentos-sdk-main/Agent/v0.4.5/API_Reference.md
vendor/agentos-sdk-main/Agent/v0.4.5/SampleCodes.md
vendor/agentos-sdk-main/Robot/v11.3C/RobotAPI_en.md
vendor/agentos-sdk-main/Robot/v11.3C/robotservice_11.3.jar
```

Copied into the Android project:

```text
app/libs/robotservice_11.3.jar
```

## Local Orion Maven Mirror

Downloaded OrionStar artifacts used by Gradle:

```text
vendor/maven/com/orionstar/agent/sdk/0.4.5-SNAPSHOT/
vendor/maven/com/orionstar/agent/base/0.2.10-SNAPSHOT/
vendor/maven/com/orionstar/robot/core/1.0.20250821-SNAPSHOT/
```

The root `settings.gradle.kts` checks `vendor/maven` before using OrionStar's remote Maven repository.

## OrionStar End-to-End Sample

Reference archive and extracted sample:

```text
vendor/end2end_sample-main.zip
vendor/end2end_sample-main/
```

Useful Android reference:

```text
vendor/end2end_sample-main/e2e_android/
```
