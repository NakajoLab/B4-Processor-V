const long charArray[10]   = {6150393738698145096, -2778327846724662266, -8467406063169314668, 3640924259398909674, -6424446445769269196, -5266053904934278905, -7022886145829593499, -8266817571845076872, -7546117979205758930, 6825206634430467364};
long targetArray[10]       = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
const long answerArray[10] = {6150393738698145096, -2778327846724662266, -8467406063169314668, 3640924259398909674, -6424446445769269196, -5266053904934278905, -7022886145829593499, -8266817571845076872, -1, -1};
char thread1Finished = 0xFF;

#define PERFORMANCE_COUNT() do { asm volatile ("rdcycle x5; rdinstret x6"); } while(0)

long* _memcpyVecE64_internal(long* dest, const long* src, int n) {
  int i, vl, avl=n;
  long* originalDest = dest;
  while(avl != 0) {
    asm volatile ("vsetvli %0, %1, e64, m1, ta, ma"
    : "=r"(vl)
    : "r"(avl));
    // load src
    asm volatile ("vle8.v v10, (%0)"::"r"(src));
    // store to dest
    asm volatile ("vse8.v v10, (%0)"::"r"(dest));
    // increment pointers
    src += vl;
    dest += vl;
    // strip mining
    avl -= vl;
  }
  return originalDest;
}

long* memcpyVecE64(long* dest, const long* src, int n, int hartid) {
  int half = n >> 1;
  // スレッド0では前半のみ
  if(hartid == 0) {
    _memcpyVecE64_internal(dest, src, half);
  } else if(hartid == 1) {
    // 偶数なら後半半分，奇数なら後半半分+1
    _memcpyVecE64_internal(dest+half, src+half, half + (n & 0x1));
  }
  return dest;
}

long main(long loop_count) {
  const char* src = (const char*)0x80100218;
  char* dest = (char*)0x80100300;
  const char* ans = (const char*)0x801001c8;
  int i, hartid;
  asm volatile ("csrr %0, mhartid":"=r"(hartid));
  if(hartid == 0) {
    PERFORMANCE_COUNT();
  }
  asm volatile ("fence");
  memcpyVecE64(dest, src, 8, hartid);
  asm volatile ("fence");
  if(hartid == 0) {
    char lock;
    while(1) {
      asm volatile ("lb %0, 0(%1)"
      : "=r"(lock)
      : "r"((char*)0x80100401));
      if(lock == 1) break;
    }
  } else if(hartid == 1) {
    *((char*)0x80100401) = 0x1;
    return 0;
  }
  PERFORMANCE_COUNT();
  for(i=0; i<48; i++) if(*(dest+i) != *(ans+i)) return 1;
  return 1919;
}