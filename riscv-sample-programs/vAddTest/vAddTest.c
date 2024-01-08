const int array0[19] = {83, 59, 131, 81, 92, 236, 96, 39, 108, 28, 231, 225, 195, 227, 133, 237, 185, 243, 133};
const int array1[19] = {104, 6, 151, 221, 46, 35, 201, 226, 213, 95, 103, 216, 68, 55, 103, 8, 142, 205, 143};
int destArray[19] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
const int answerArray[19] = {187, 65, 282, 302, 138, 271, 297, 265, 321, 123, 334, 441, 263, 282, 236, 245, 327, 448, 276};

#define PERFORMANCE_COUNT() do { asm volatile ("rdcycle x5; rdinstret x6"); } while(0)

long main(long loop_count) {
  long vl, avl = 19;
  // ここにarray0を直接入れるとメモリの権限関係でコンパイルできない
  int *ptr0 = (int*)0x801001a8;
  int *ptr1 = (int*)0x80100158;
  int *dest = (int*)0x80100208;
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
    asm volatile ("vadd.vv v12, v10, v11");
    asm volatile ("vse32.v v12, (%0)"
    :
    : "r"(dest));
    ptr0 += vl;
    ptr1 += vl;
    dest += vl;
    avl -= vl;
  }
  asm volatile ("fence");
  PERFORMANCE_COUNT();
  int* ans = (int*)0x80100108;
  dest = (int*)0x80100208;
  _Bool correct = 1;
  int i;
  for(i=0; i<19; i++) {
    if(*(ans+i) != *(dest+i)) {
      correct = 0;
      break;
    }
  }
  if(correct) {
    return 1919;
  } else {
    return 0xFFFFFFFF;
  }
}