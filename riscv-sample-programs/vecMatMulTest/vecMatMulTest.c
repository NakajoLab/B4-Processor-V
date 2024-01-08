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
int answerArray[8][8] = {{0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0}};

#define PERFORMANCE_COUNT() do { asm volatile ("rdcycle x20; rdinstret x21"); } while(0)

// array1は転置行列
void _e32_8x8_matmul(const int array0[8][8], const int array1[8][8], int target[8][8]) {
  asm volatile ("vsetvli zero, %0, e32, m1, ta, ma"::"r"(8));
  asm volatile ("vmv.s.x v12, zero");
  int i=0, j=0;
  for(i=0; i<8; i++) {
    asm volatile ("vle32.v v10, (%0)"::"r"(&(array0[i][0])));
    for(j=0; j<8; j++) {
      asm volatile ("vle32.v v11, (%0)"::"r"(&(array1[j][0])));
      asm volatile ("vmul.vv v11, v10, v11");
      asm volatile ("vredsum.vs v11, v11, v12");
      asm volatile ("vmv.x.s %0, v11":"=r"(target[i][j]));
    }
  }
  return;
}

// scalar
void _e32_8x8_scalarMatmul(const int array0[8][8], const int array1[8][8], int target[8][8]) {
  int i=0, j=0, k=0;
  for(i=0; i<8; i++) {
    for(j=0; j<8; j++) {
      int sum = 0;
      for(k=0; k<8; k++) {
        sum += array0[i][k] * array1[j][k];
      }
      target[i][j] = sum;
    }
  }
  return;
}

long main(long loop_count) {
  long vl, avl = 16;
  // ここにarray0を直接入れるとメモリの権限関係でコンパイルできない
  int *ptr0 = (int*)0x801002c0;
  int *ptr1 = (int*)0x801001c0;
  int *vecDestPtr = (int*)0x80100508;
  int *scalarDestPtr = (int*)0x80100408;

  PERFORMANCE_COUNT();
  asm volatile ("fence");
  _e32_8x8_matmul(ptr0, ptr1, vecDestPtr);
  asm volatile ("fence");
  PERFORMANCE_COUNT();
  asm volatile ("fence");
  _e32_8x8_scalarMatmul(ptr0, ptr1, scalarDestPtr);
  asm volatile ("fence");
  PERFORMANCE_COUNT();

  int i=0, j=0;
  _Bool correct = 1;
  for(i=0; i<8; i++) {
    for(j=0; j<8; j++) {
      if(vecDestPtr[i*8+j] != scalarDestPtr[i*8+j]) {
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