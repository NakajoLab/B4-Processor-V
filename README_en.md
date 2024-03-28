[![Scala CI](https://github.com/NakajoLab/B4-Processor/actions/workflows/scala.yml/badge.svg)](https://github.com/NakajoLab/B4-Processor/actions/workflows/scala.yml)

# B4SMT-V
OoO Processor

# Prepare
The following tools are required to build.

## Package Manager
You can learn about Nix through reading [Zero to Nix] and [Nix]. 
* [Nix][Nix download]

## Development Tools
* [IntelliJ IDEA IDEA][IntelliJ IDEA]
* java
* [sbt]
* [LLVM circt] (Circuit IR compilers and tools for generating System Verilog)

## HDL Simulator
* Verilator
* Ivarus Verilog

# Generating 
The following command generates a System Verilog file in `./processor`.

```sh
make processor
```

# Building Tests
All tests are managed by [Nix].
The following command compiled test files and [riscv-tests].
The compiled files are placed in `./processor`.

```sh
make programs
```

# Running Tests
The following command executes tests except for some heavy tests.
```shell
$ sbt "testOnly * -- -l org.scalatest.tags.Slow"
```

You can execute all tests by the following command, but it takes time.
```shell
$ sbt test
```

If you want to choose which test to run, you should open this project in [IntelliJ IDEA] and execute the test you choose.

# Troubleshooting
## Failed to generate the processor after changing dependencies.
There may be a problem with Nix's dependency cache.
Then you should edit `./flake.nix` like a following.
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

When you run make command after editing, the following error occurs.
The new hash key is shown in the message.
Finally, you should replace `depsSha256` on `./flake.nix` by the key.
```shell
error: hash mismatch in fixed-output derivation '/nix/store/01ghymlaf8f1r9ssqvdhn4j5kz3gk153-B4Processor-sbt-dependencies.tar.zst.drv':
         specified: sha256-0000000000000000000000000000000000000000000=
            got:    sha256-W1Kgoc58kruhLW0CDzvuUgAjuRZbT4QqStJLAAnPuhc=
```

## Failed to run `make`
```text
error: opening file '/nix/store/d8bkk6vlxa010q9wc2qis4a2rkcz55w6-processor-test-programs.drv': No such file or directory
```

Please run this.
```sh
$ nix-store --verify
```

<!-- --------------------------- -->
<!-- URKS -->
[riscv-tests]: https://github.com/riscv-software-src/riscv-tests
[Nix download]: https://zero-to-nix.com/start/install
[Nix]: https://nixos.org/
[Zero to Nix]: https://zero-to-nix.com/
[IntelliJ IDEA]: https://www.jetbrains.com/idea/
[sbt]: https://www.scala-sbt.org/
[LLVM circt]: https://circt.llvm.org/