const long charArray[10]   = {6150393738698145096, -2778327846724662266, -8467406063169314668, 3640924259398909674, -6424446445769269196, -5266053904934278905, -7022886145829593499, -8266817571845076872, -7546117979205758930, 6825206634430467364};
long targetArray[10]       = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
const long answerArray[10] = {6150393738698145096, -2778327846724662266, -8467406063169314668, 3640924259398909674, -6424446445769269196, -5266053904934278905, -7022886145829593499, -8266817571845076872, -1, -1};

#define PERFORMANCE_COUNT() do { asm volatile ("rdcycle x30; rdinstret x31"); } while(0)

long* memcpyScalar(long* dest, const long* src, long n) {
  long* originalDest = dest;
  while(n > 0) {
    *(dest++) = *(src++);
    n--;
  }
  return originalDest;
}

long main(long loop_count) {
  const long* src = (const long*)0x80100168;
  long* dest = (long*)0x80100200;
  const long* ans = (const long*)0x80100118;
  int i;
  PERFORMANCE_COUNT();
  asm volatile ("fence");
  memcpyScalar(dest, src, 8);
  asm volatile ("fence");
  PERFORMANCE_COUNT();
  for(i=0; i<10; i++) if(*(dest+i) != *(ans+i)) return 1;
  return 1919;
}