# EC2 Secure Connect

EC2 Secure Connectは、AWS Systems Manager Session Managerを使用して、EC2インスタンスへの安全なリモートアクセスを提供する **Android用アプリ** です。SSHやHTTP用のポート開放不要で、Android端末からEC2インスタンスに **セキュア** に接続できます。<br>
アプリのバックグラウンドでは、AWS公式の [session-manager-plugin](https://github.com/aws/session-manager-plugin) が動作しています。

<br>
    
<div align="center">

HTTPの転送の様子

<img src="./assets/http_test.gif" alt="HTTP forwarding demonstration">

<br>

SSHの転送の様子

<img src="./assets/ssh_test.gif" alt="SSH forwarding demonstration">

</div>

## 要件

- EC2インスタンス上にSSM Agentが動作しており、Systems Managerからインスタンスにアクセスできる必要があります。
- `arm64-v8a`（64ビットアーキテクチャ）に対応したAndroid端末が必要です。

## ビルド方法

### ローカルでのビルド

`ssm-client` をAndroid向けにビルドするために、GoとAndroid NDKが必要です。Android Studioでプロジェクトをビルド前にAndroid NDKをインストールしてください。また、Goをインストール後、`./local.properties` に以下を追記してください。

設定例：

- 証明書無しでDebug,Releaseビルドを行う場合
```
go.binary=<Goの実行ファイルへのパス>
```

- 証明書を使用してReleaseビルドを行う場合
```
go.binary=<Goの実行ファイルへのパス>
release.keystore.base64=<KeystoreのBase64エンコード値>
release.keystore.password=<Keystoreのパスワード>
release.key.alias=<Keystoreのエイリアス>
release.key.password=<Keystoreのキーのパスワード>
```

### GitHub Actionsでのビルド

`build.yml` を使用して、GitHub Actions上で自動的にビルドを行うことができます。
ビルド前に `Repository secrets` に以下の環境変数を設定してください。
ビルド完了後は、Artifactsから成果物（APK/AAB）をダウンロードできます。

```
RELEASE_KEYSTORE_BASE64=<KeystoreのBase64エンコード値>
RELEASE_KEYSTORE_PASSWORD=<Keystoreのパスワード>
RELEASE_KEY_ALIAS=<Keystoreのエイリアス>
RELEASE_KEY_PASSWORD=<Keystoreのキーのパスワード>
```

## その他

Issueへの投稿、PullRequest大歓迎です。<br>
バグや改善点を見つけた場合は、遠慮なくPullRequestを送ってください。

## ライセンス

The MIT License (MIT)<br>
Copyright (c) 2026 - Created by Shogo Fukushima
