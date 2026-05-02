日本語版は[こちら](./README-ja.md)<br><br>

# EC2 Secure Connect

EC2 Secure Connect is an **Android application** that provides secure remote access to EC2 instances using AWS Systems Manager Session Manager. You can **securely** connect to EC2 instances from your Android device without opening ports for SSH or HTTP.<br>
Behind the scenes, the app runs AWS's official [session-manager-plugin](https://github.com/aws/session-manager-plugin).

<br>

<div align="center">

HTTP forwarding demonstration

<video controls src="https://github.com/user-attachments/assets/a5e929b8-d9fa-434d-b91a-1f95e5b54cf0" width="320" muted="false"></video>

<br>

SSH forwarding demonstration

<video controls src="https://github.com/user-attachments/assets/065621b2-366b-4e56-b048-07befad8c9e7" width="320" muted="false"></video>

</div>

## Requirements

- The EC2 instance must have the SSM Agent running and be accessible from Systems Manager.
- You need an Android device that supports `arm64-v8a` (64-bit architecture).

## Build Instructions

To build `ssm-client` for Android, Go and Android NDK are required. Install the Android NDK through Android Studio before building the project. After installing Go, add the following to `./local.properties`:

Configuration examples:

- For Debug and Release builds without a certificate
```
go.binary=<Path to your Go executable>
```

- For Release builds with a certificate
```
go.binary=<Path to your Go executable>
release.keystore.base64=<Base64 encoded Keystore>
release.keystore.password=<Keystore password>
release.key.alias=<Keystore alias>
release.key.password=<Keystore key password>
```

## Others

We welcome issues and pull requests!<br>
If you find any bugs or improvement opportunities, please feel free to submit a pull request.

## License

The MIT License (MIT)<br>
Copyright (c) 2026 - Created by Shogo Fukushima
