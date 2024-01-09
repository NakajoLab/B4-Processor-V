const int array0[8][8] = {{77, 82, 146, 42, 104, 84, 7, 126},
                         {105, 73, 40, 123, 169, 80, 121, 22},
                         {15, 108, 218, 37, 252, 211, 138, 208},
                         {20, 53, 158, 239, 181, 65, 47, 252},
                         {81, 79, 98, 126, 134, 222, 53, 31},
                         {214, 252, 53, 106, 0, 173, 233, 103},
                         {82, 254, 68, 154, 5, 21, 197, 107},
                         {164, 209, 100, 128, 110, 253, 13, 123}};
const int array1[8][8] = {{90, 22, 165, 103, 80, 225, 248, 252},
                         {68, 232, 176, 43, 186, 119, 62, 150},
                         {135, 66, 159, 53, 72, 1, 202, 182},
                         {62, 131, 68, 195, 19, 191, 211, 146},
                         {157, 119, 189, 124, 180, 161, 58, 114},
                         {230, 127, 92, 209, 15, 249, 222, 165},
                         {116, 69, 252, 226, 43, 222, 132, 194},
                         {184, 73, 85, 8, 66, 246, 114, 245}};
int destArray[8][8] = {{0, 0, 0, 0, 0, 0, 0, 0},
                      {0, 0, 0, 0, 0, 0, 0, 0},
                      {0, 0, 0, 0, 0, 0, 0, 0},
                      {0, 0, 0, 0, 0, 0, 0, 0},
                      {0, 0, 0, 0, 0, 0, 0, 0},
                      {0, 0, 0, 0, 0, 0, 0, 0},
                      {0, 0, 0, 0, 0, 0, 0, 0},
                      {0, 0, 0, 0, 0, 0, 0, 0}};
int answerArray[8][8] = {{97858, 100436, 73165, 71527, 101663, 95154, 109362, 92096},
                        {97397, 88161, 72566, 90012, 100810, 115755, 100362, 79051},
                        {197782, 177772, 129863, 141692, 172044, 166230, 188736, 164700},
                        {157918, 133856, 112442, 128095, 143444, 146732, 177112, 117925},
                        {119802, 105780, 64627, 107262, 122734, 138182, 136075, 107102},
                        {167132, 137385, 125552, 167798, 139856, 220052, 168668, 157480},
                        {120995, 114787, 106457, 134307, 102953, 156253, 130617, 94811},
                        {148987, 152567, 91803, 140421, 160700, 188043, 174047, 156072}};

char lock = 0xFF;

#define PERFORMANCE_COUNT() do { asm volatile ("rdcycle x20; rdinstret x21"); } while(0)

// 8x8行列乗算の半分
void _internal_e32_8x8_half_matmul(const int array0[4][8], const int array1[8][8], int target[4][8]) {
  int i=0, j=0, k = 0;
  for(i=0; i<4; i++) {
    for(j=0; j<8; j++) {
      int sum = 0;
      for(k=0; k<8; k++) {
        int temp;
        asm volatile ("mulw %0, %1, %2":"=r"(temp):"r"(array0[i][k]), "r"(array1[j][k]));
        asm volatile ("addw %0, %1, %2":"=r"(sum):"r"(sum), "r"(temp));
      }
      target[i][j] = sum;
    }
  }
  return;
}

// array1は転置行列
void _e32_8x8_matmul_mt(const int array0[8][8], const int array1[8][8], int target[8][8], int hartid) {
  if(hartid == 0) {
    // スレッド0で前半
    _internal_e32_8x8_half_matmul(array0, array1, target);
  } else if(hartid == 1) {
    // スレッド1で後半
    _internal_e32_8x8_half_matmul(&(array0[4][0]), array1, &(target[4][0]));
  }
  return;
}

long main(long loop_count) {
  // ここにarray0を直接入れるとメモリの権限関係でコンパイルできない
  int *ptr0 = (int*)0x801002b0;
  int *ptr1 = (int*)0x801001b0;
  int *vecDestPtr = (int*)0x80100508;
  int *ansPtr = (int*)0x80100400;

  int hartid;
  asm volatile ("csrr %0, mhartid":"=r"(hartid));
  if(hartid == 0) {
    PERFORMANCE_COUNT();
    asm volatile ("fence");
  }
  _e32_8x8_matmul_mt(ptr0, ptr1, vecDestPtr, hartid);
  if(hartid == 0) {
    char loadedLock;
    do {
      asm volatile ("lb %0, 0(%1)"
      : "=r"(loadedLock)
      : "r"((char*)0x80100501));
    } while (loadedLock != 1);
    asm volatile ("fence");
    PERFORMANCE_COUNT();
  } else if(hartid == 1) {
    *((char*)0x80100501) = 0x1;
    asm volatile ("wfi");
  }

  int i=0, j=0;
  _Bool correct = 1;
  for(i=0; i<8; i++) {
    for(j=0; j<8; j++) {
      if(vecDestPtr[i*8+j] != ansPtr[i*8+j]) {
        correct = 0;
      }
    }
  }

  if(correct) {
    return 1919;
  } else {
    return 114514;
  }
}