# EC2 Secure Connect

EC2 Secure Connect is an **Android application** that provides secure remote access to EC2 instances using AWS Systems Manager Session Manager. You can **securely** connect to EC2 instances from your Android device without opening ports for SSH or HTTP.<br>
Behind the scenes, the app runs AWS's official [session-manager-plugin](https://github.com/aws/session-manager-plugin).

<br>
<br>

<div align="center">

HTTP forwarding demonstration

<video controls src="./assets/http_test.mp4" width="320" muted="false"></video>

<br>

SSH forwarding demonstration

<video controls src="./assets/ssh_test.mp4" width="320" muted="false"></video>

</div>

## Requirements

- The EC2 instance must have the SSM Agent running and be accessible from Systems Manager.
- You need an Android device that supports `arm64-v8a` (64-bit architecture).

## Build Instructions

- Go is required to build `ssm-client`. After installing Go, set the `goBinary` path in `./gradle.properties` to your Go binary location.
- Android NDK is required to build `ssm-client` for Android. Install the Android NDK through Android Studio before building the project.

## Others

We welcome issues and pull requests!<br>
If you find any bugs or improvement opportunities, please feel free to submit a pull request.

## License

The MIT License (MIT)<br>
Copyright (c) 2026 - Created by Shogo Fukushima
