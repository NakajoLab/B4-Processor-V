const long charArray[10]   = {6150393738698145096, -2778327846724662266, -8467406063169314668, 3640924259398909674, -6424446445769269196, -5266053904934278905, -7022886145829593499, -8266817571845076872, -7546117979205758930, 6825206634430467364};
long targetArray[10]       = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
const long answerArray[10] = {6150393738698145096, -2778327846724662266, -8467406063169314668, 3640924259398909674, -6424446445769269196, -5266053904934278905, -7022886145829593499, -8266817571845076872, -1, -1};
char thread1Finished = 0xFF;

#define PERFORMANCE_COUNT() do { asm volatile ("rdcycle x30; rdinstret x31"); } while(0)

long* _memcpyScalarE64_internal(long* dest, const long* src, int n) {
  long* originalDest = dest;
  while(n > 0) {
    *(dest++) = *(src++);
    n--;
  }
  return originalDest;
}

long* memcpyScalarE64(long* dest, const long* src, int n, int hartid) {
  int half = n >> 1;
  // スレッド0では前半のみ
  if(hartid == 0) {
    _memcpyScalarE64_internal(dest, src, half);
  } else if(hartid == 1) {
    // 偶数なら後半半分，奇数なら後半半分+1
    _memcpyScalarE64_internal(dest+half, src+half, half + (n & 0x1));
  }
  return dest;
}

long main(long loop_count) {
  const long* src = (const long*)0x80100210;
  long* dest = (long*)0x80100300;
  const long* ans = (const long*)0x801001c0;
  int i, hartid;
  asm volatile ("csrr %0, mhartid":"=r"(hartid));
  if(hartid == 0) {
    PERFORMANCE_COUNT();
    asm volatile ("fence");
  }
  memcpyScalarE64(dest, src, 8, hartid);
  if(hartid == 0) {
    char lock;
    do {
      asm volatile ("lb %0, 0(%1)"
      : "=r"(lock)
      : "r"((char*)0x80100401));
    } while (lock != 1);
    asm volatile ("fence");
    PERFORMANCE_COUNT();
    for(i=0; i<10; i++) if(*(dest+i) != *(ans+i)) return 1;
    return 1919;
  } else if(hartid == 1) {
    *((char*)0x80100401) = 0x1;
    asm volatile ("wfi");
  } else {
    return 0;
  }
}