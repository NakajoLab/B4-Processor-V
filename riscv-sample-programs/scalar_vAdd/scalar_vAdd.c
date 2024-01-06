const int array0[19] = {83, 59, 131, 81, 92, 236, 96, 39, 108, 28, 231, 225, 195, 227, 133, 237, 185, 243, 133};
const int array1[19] = {104, 6, 151, 221, 46, 35, 201, 226, 213, 95, 103, 216, 68, 55, 103, 8, 142, 205, 143};
int destArray[19] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
const int answerArray[19] = {187, 65, 282, 302, 138, 271, 297, 265, 321, 123, 334, 441, 263, 282, 236, 245, 327, 448, 276};

#define PERFORMANCE_COUNT() do { asm volatile ("rdcycle x5; rdinstret x6"); } while(0)

long main(long loop_count) {
  int i;
  // ここにarray0を直接入れるとメモリの権限関係でコンパイルできない
  int *ptr0 = (int*)0x80100190;
  int *ptr1 = (int*)0x80100140;
  int *dest = (int*)0x80100208;
  int *ans =  (int*)0x801000f0;
  PERFORMANCE_COUNT();
  asm volatile ("fence");
  for(i=0; i<19; i++) {
    dest[i] = ptr0[i] + ptr1[i];
  }
  asm volatile ("fence");
  PERFORMANCE_COUNT();
  dest = (int*)0x80100208;
  _Bool correct = 1;
  for(i=0; i<19; i++) {
    if(ans[i] != dest[i]) {
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