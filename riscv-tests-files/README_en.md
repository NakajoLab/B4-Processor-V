# RISC-V Tests

# Hierarchy
`riscv-tests` have been cloned as submodule and Makefile was modified from `riscv-tests/isa/Makefile`.


You can clone `riscv-tests` through the following.

```sh
$ git submodule update --init --recursive
```

# How to Generate a Hex File
```shell
$ make
```

# Hex Files
* `.text.hex`: It includes only instruction lines extracted from `.text` segment. It will be used to initialize an instruction memory.
* `.data.hex`: It includes only data lines extracted from `.data` segment.It will be used to initialize an data memory.

# Flow of the tests
Tests will be run in the following steps.
* Some test cases is executed.
* A test number is assigned in `x2`.
* Checking a result 
  * If passing all test cases, a next test will be started.
  * If failing, it jumps to `fail` label.