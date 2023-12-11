int array0[6] = {0, 1, 2, 3, 4, 5};
int array1[6] = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
int verify[6] = {0, 1, 2, 3, 4, 0xFFFFFFFF};

long main(long loop_count) {
  int* a0 = (int*)0x80100130;
  int* a1 = (int*)0x80100118;
  int* v = (int*)0x80100100;
  asm volatile ("vsetvli zero, %0, e32, m1, ta, ma"::"r"(5));
  // load array0[0:4]
  asm volatile ("vle32.v v10, (%0)"::"r"(a0));
  // store array0[0:4] to array1[0:4]
  asm volatile ("vse32.v v10, (%0)"::"r"(a1));
  int i;
  // lw x13, a1+i
  // lw x14, v+i
  for(i=0; i<6; i++) if(*(a1+i) != *(v+i)) return 1;
  return 1919;
}