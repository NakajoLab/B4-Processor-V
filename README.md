[![Scala CI](https://github.com/NakajoLab/B4-Processor/actions/workflows/scala.yml/badge.svg)](https://github.com/NakajoLab/B4-Processor/actions/workflows/scala.yml)

# B4-Processor

OoOのプロセッサ

## 準備
これらのソフトウェアをインストールしておく必要があります。

### プロセッサの生成・テストコードの生成
* [Nix][Nix download]

Nixについては[Nix]の公式サイトや[Zero to Nix]を参考にしてみてください。

[Nix download]: https://zero-to-nix.com/start/install
[Nix]: https://nixos.org/
[Zero to Nix]: https://zero-to-nix.com/

開発
* [intellij IDEA][intellij]
* java
* [sbt]
* [LLVM circt] (System Verilog生成用のfirtoolコマンドラインツール)

[intellij]: https://www.jetbrains.com/idea/
[sbt]: https://www.scala-sbt.org/
[LLVM circt]: https://circt.llvm.org/

テスト
* Verilator
* Ivarus Verilog

## プロセッサの生成
次のコマンドで`./processor`にSystem Verilogのソースが出力されます。

```console
$ make processor
```

## テストコードの生成
次のコマンドで`./programs`にいくつかテスト用のプログラムと[riscv-tests]のコンパイル結果が出力されます。

```console
$ make programs
```

[riscv-tests]: https://github.com/riscv-software-src/riscv-tests

## テスト用のプログラムの生成
テスト用プログラムは`nix`を使って管理しています。

## テスト方法
いくつかの時間のかかるテストを除いたテストは次のように行います。
```shell
$ sbt "testOnly * -- -l org.scalatest.tags.Slow"
```

すべてのテストを実行するには次のコマンドを実行します。（とても時間がかかります）
```shell
$ sbt test
```

細かく実行するテストを決めたい場合はIntellij IDEAでプロジェクトを開き、各テストを実行してください。

## トラブルシューティング
### 依存関係周りを編集したらプロセッサが生成できない

コンパイル中に依存関係周りで問題が発生していれば、Nixの依存関係のキャッシュに問題がある場合があります。
その場合は`./flake.nix`を次のように編集します。
```diff
diff --git a/flake.nix b/flake.nix
index 726feaa..478b09e 100644
--- a/flake.nix
+++ b/flake.nix
@@ -37,7 +37,7 @@
             ];
           };
           buildInputs = with pkgs; [ circt ];
-          depsSha256 = "sha256-W1Kgoc58kruhLW0CDzvuUgAjuRZbT4QqStJLAAnPuhc=";
+          depsSha256 = "sha256-0000000000000000000000000000000000000000000=";
           buildPhase = ''
             sbt "runMain b4processor.B4Processor"
           '';
```
また一度makeすると次のエラーメッセージが出てきます。
```shell
error: hash mismatch in fixed-output derivation '/nix/store/01ghymlaf8f1r9ssqvdhn4j5kz3gk153-B4Processor-sbt-dependencies.tar.zst.drv':
         specified: sha256-0000000000000000000000000000000000000000000=
            got:    sha256-W1Kgoc58kruhLW0CDzvuUgAjuRZbT4QqStJLAAnPuhc=
```
ここで出てきたハッシュに置き換えてmakeするとうまくビルドされると思います。

### makeが通らない
次のようなエラーが出た際は，
```text
error: opening file '/nix/store/d8bkk6vlxa010q9wc2qis4a2rkcz55w6-processor-test-programs.drv': No such file or directory
```

次を実行するとnixのなんやかんやを修復してくれる
```sh
$ nix-store --verify
```