# EC2 Secure Connect

EC2 Secure Connectは、AWS Systems Manager Session Managerを使用して、EC2インスタンスへの安全なリモートアクセスを提供する **Android用アプリ** です。SSHやHTTP用のポート開放不要で、Android端末からEC2インスタンスに **セキュア** に接続できます。<br>
アプリのバックグラウンドでは、AWS公式の [session-manager-plugin](https://github.com/aws/session-manager-plugin) が動作しています。

<br>
<br>
    
<div align="center">

HTTPの転送の様子

<video controls src="https://github.com/user-attachments/assets/a5e929b8-d9fa-434d-b91a-1f95e5b54cf0" width="320" muted="false"></video>

<br>

SSHの転送の様子

<video controls src="https://github.com/user-attachments/assets/065621b2-366b-4e56-b048-07befad8c9e7" width="320" muted="false"></video>

</div>

## 要件

- EC2インスタンス上にSSM Agentが動作しており、Systems Managerからインスタンスにアクセスできる必要があります。
- `arm64-v8a`（64ビットアーキテクチャ）に対応したAndroid端末が必要です。

## ビルド方法

- `ssm-client` をビルドするためにGoが必要です。Goをインストール後、`./gradle.properties` の `goBinary` をGoのバイナリのパスに設定してください。
- `ssm-client` をAndroid向けにビルドするために、Android NDKが必要です。Android Studioでプロジェクトをビルド前にAndroid NDKをインストールしてください。

## その他

Issueへの投稿、PullRequest大歓迎です。<br>
バグや改善点を見つけた場合は、遠慮なくPullRequestを送ってください。

## ライセンス

The MIT License (MIT)<br>
Copyright (c) 2026 - Created by Shogo Fukushima
