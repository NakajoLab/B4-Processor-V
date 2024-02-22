SUBDIRS := fibonacci \
			branch \
			call_ret \
			many_add \
			many_add_out_of_order \
			load_store \
			fibonacci_c \
			many_load_store \
			load_plus_arithmetic \
			load_after_store \
			enter_c \
			calculation_c \
			loop_c \
			load_store_cross \
			led_show \
			csrtest \
			simple_loader_hello \
			illegal_inst \
			io_test \
			pext_test \
			vecLoad \
			vecLoad_e8 \
			vecStore \
			memcpyVec \
			memcpyScalar \
			memcpyVec_mt \
			memcpyScalar_mt \
			vAddTest \
			scalar_vAdd \
			vRedSumTest \
			vecInnerProdTest \
			vecMatMulTest \
			scalarMatMulTest \
			vecMatMulTest_mt \
			scalarMatMulTest_mt \
			vecMatMulTest_vlen256_mt \
			vecMatMulTest_vlen128 \
			vsetvl \
#      vectorBranch
#			simple_loader_uart \

all: $(SUBDIRS)

$(SUBDIRS):
	$(MAKE) -C $@

SUBDIRS_CLEAN = $(addsuffix .clean, $(SUBDIRS))
$(SUBDIRS_CLEAN):
	$(MAKE) -C $(basename $@) clean


.PHONY: all clean $(SUBDIRS) $(SUBDIRS_CLEAN)

clean: $(SUBDIRS_CLEAN)
