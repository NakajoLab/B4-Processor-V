int array0[29] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};

long main(long loop_count) {
  int i = 0;
  long vl, avl = 29;
  // ここにarray0を直接入れるとメモリの権限関係でコンパイルできない
  int *ptr = (int*)0x80100100;
  while(avl != 0) {
    // 256bit -> 32 * 8 elements
    // i:0 -> avl = 29
    // i:1 -> avl = 21
    // i:2 -> avl = 13
    // i:3 -> avl = 5
    asm volatile ("vsetvli %0, %1, e32, m1, ta, ma"
    : "=r"(vl)
    : "r"(avl));
    asm volatile ("vle32.v v10, (%0)"
    :
    : "r"(ptr));
    ptr += vl;
    avl -= vl;
    i++;
  }
  return i;
}