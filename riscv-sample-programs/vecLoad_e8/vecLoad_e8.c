char array0[41] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40};

long main(long loop_count) {
  int i = 0;
  long vl, avl = 41;
  // ここにarray0を直接入れるとメモリの権限関係でコンパイルできない
  char *ptr = (char*)0x80100100;
  while(avl != 0) {
    asm volatile ("vsetvli %0, %1, e8, m1, ta, ma"
    : "=r"(vl)
    : "r"(avl));
    asm volatile ("vle8.v v10, (%0)"
    :
    : "r"(ptr));
    ptr += vl;
    avl -= vl;
    i++;
  }
  return i;
}