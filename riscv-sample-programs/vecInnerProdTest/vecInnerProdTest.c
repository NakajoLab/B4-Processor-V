const int array0[16] = {83, 59, 131, 81, 92, 236, 96, 39, 108, 28, 231, 225, 195, 227, 133, 237};
const int array1[16] = {104, 6, 151, 221, 46, 35, 201, 226, 213, 95, 103, 216, 68, 55, 103, 8};

#define PERFORMANCE_COUNT() do { asm volatile ("rdcycle x5; rdinstret x6"); } while(0)

long main(long loop_count) {
  long vl, avl = 16;
  // ここにarray0を直接入れるとメモリの権限関係でコンパイルできない
  int *ptr0 = (int*)0x80100138;
  int *ptr1 = (int*)0x801000f8;
  int sum = 0;
  PERFORMANCE_COUNT();
  asm volatile ("fence");
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
    : "r"(ptr0));
    asm volatile ("vle32.v v11, (%0)"
    :
    : "r"(ptr1));
    asm volatile ("vmv.s.x v12, %0"::"r"(sum));
    asm volatile ("vmul.vv v10, v10, v11");
    asm volatile ("vredsum.vs v12, v10, v12");
    asm volatile ("vmv.x.s %0, v12":"=r"(sum));
    ptr0 += vl;
    ptr1 += vl;
    avl -= vl;
  }
  asm volatile ("fence");
  PERFORMANCE_COUNT();
  if(sum == 226667) {
    return 1919;
  } else {
    return 0xFFFFFFFF;
  }
}