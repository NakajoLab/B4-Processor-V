# File Types
* Assembly file(.S)
* ELF file(.o)
* Binary file(.binary)
* Binary file written by hex(.hex)
* linker script(.ld)

# How to make a new program file
1. Copy a directory already existing.
2. Edit a program name written on `Makefile` (`riscv-sample-programs/***/Makefile`).
3. Rename `***.S` file to a new name.
4. Add the program to `Makefile` on the parent directory (`riscv-sample-programs/Makefile`). 
