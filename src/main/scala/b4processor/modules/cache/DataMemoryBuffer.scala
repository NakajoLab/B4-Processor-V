package b4processor.modules.cache

import circt.stage.ChiselStage
import b4processor.Parameters
import b4processor.connections.{LoadStoreQueue2Memory, OutputValue}
import b4processor.modules.memory.{MemoryAccessChannels, MemoryReadRequest, MemoryWriteRequest, MemoryWriteRequestData}
import b4processor.modules.vector.VCsrBundle
import b4processor.structures.memoryAccess.MemoryAccessWidth
import b4processor.utils.operations.{LoadStoreOperation, LoadStoreWidth}
import b4processor.utils.{B4RRArbiter, FIFO, FormalTools}
import chisel3._
import chisel3.util._

/** from LSQ toDataMemory のためのバッファ
  *
  * @param params
  *   パラメータ
  */
class DataMemoryBuffer(implicit params: Parameters)
    extends Module
    with FormalTools {
  val io = IO(new Bundle {
    val dataIn =
      Vec(params.threads, Flipped(Irrevocable(new LoadStoreQueue2Memory)))
    val vCsr = Vec(params.threads, Input(new VCsrBundle()))
    val memory = new MemoryAccessChannels()
    val output = Irrevocable(new OutputValue())
  })

  private val inputArbiter = Module(
    new B4RRArbiter(new DataMemoryBufferEntry, params.threads),
  )
  for (tid <- 0 until params.threads)
    inputArbiter.io.in(tid) <> io.dataIn(tid)

  private val buffer = Module(
    new FIFO(params.loadStoreQueueIndexWidth)(new DataMemoryBufferEntry),
  )
  buffer.input <> inputArbiter.io.out
  buffer.output.ready := false.B
//  buffer.flush := false.B

  io.memory.read.request.bits := 0.U.asTypeOf(new MemoryReadRequest)
  io.memory.read.request.valid := false.B

  io.memory.write.request.bits := 0.U.asTypeOf(new MemoryWriteRequest)
  io.memory.write.requestData.bits := 0.U.asTypeOf(new MemoryWriteRequestData())
  io.memory.write.request.valid := false.B
  io.memory.write.requestData.valid := false.B

  io.memory.read.response.ready := false.B
  io.memory.write.response.ready := false.B

  private val writeRequestDone = RegInit(false.B)
  private val writeRequestDataDone = RegInit(false.B)

  when(!buffer.empty) {
    val entry = buffer.output.bits

    val operationIsStore = LoadStoreOperation.Store === entry.operation

    when(!operationIsStore) {
      // TODO: ベクトルメモリロード追加
      io.memory.read.request.valid := true.B
      val size = MuxLookup(entry.operationWidth, MemoryAccessWidth.DoubleWord)(
        Seq(
          LoadStoreWidth.Byte -> MemoryAccessWidth.Byte,
          LoadStoreWidth.HalfWord -> MemoryAccessWidth.HalfWord,
          LoadStoreWidth.Word -> MemoryAccessWidth.Word,
          LoadStoreWidth.DoubleWord -> MemoryAccessWidth.DoubleWord,
        ),
      )
      val signed = LoadStoreOperation.Load === entry.operation
      io.memory.read.request.bits := MemoryReadRequest.ReadToTag(
        entry.address,
        size,
        signed,
        entry.tag,
      )
      when(io.memory.read.request.ready) {
        buffer.output.ready := true.B
      }
    }.elsewhen(operationIsStore) {
      val addressUpper = entry.address(63, 3)
      val addressLower = entry.address(2, 0)
      when(!writeRequestDone) {
        io.memory.write.request.valid := true.B
        io.memory.write.request.bits.address := addressUpper ## 0.U(3.W)
        io.memory.write.request.bits.outputTag := entry.tag
      }

      when(!writeRequestDataDone) {
        io.memory.write.requestData.valid := true.B
        io.memory.write.requestData.bits.data := MuxLookup(
          entry.operationWidth,
          0.U,
        )(
          Seq(
            LoadStoreWidth.Byte -> Mux1H(
              (0 until 8).map(i =>
                (addressLower === i.U) -> (entry.data(7, 0) << i * 8).asUInt,
              ),
            ),
            LoadStoreWidth.HalfWord -> Mux1H(
              (0 until 4).map(i =>
                (addressLower(2, 1) === i.U) -> (entry
                  .data(15, 0) << i * 16).asUInt,
              ),
            ),
            LoadStoreWidth.Word -> Mux1H(
              (0 until 2).map(i =>
                (addressLower(2) === i.U) -> (entry
                  .data(31, 0) << i * 32).asUInt,
              ),
            ),
            LoadStoreWidth.DoubleWord -> entry.data,
          ),
        )
        io.memory.write.requestData.bits.mask := MuxLookup(
          entry.operationWidth,
          0.U,
        )(
          Seq(
            LoadStoreWidth.Byte -> Mux1H(
              (0 until 8).map(i => (addressLower === i.U) -> (1 << i).U),
            ),
            LoadStoreWidth.HalfWord -> Mux1H(
              (0 until 4).map(i =>
                (addressLower(2, 1) === i.U) -> (3 << i * 2).U,
              ),
            ),
            LoadStoreWidth.Word -> Mux1H(
              (0 until 2).map(i => (addressLower(2) === i.U) -> (15 << i * 4).U),
            ),
            LoadStoreWidth.DoubleWord -> "b11111111".U,
          ),
        )
      }

      // 条件は真理値表を用いて作成
      val RD = writeRequestDone
      val DD = writeRequestDataDone
      val RR = io.memory.write.request.ready
      val DR = io.memory.write.requestData.ready
      RD := (!RD && !DD && RR && !DR) || (RD && !DD && !DR)
      DD := (!RD && !DD && !RR && DR) || (!RD && DD && !RR)
      buffer.output.ready := (!RD && !DD && RR && DR) || (!RD && DD && RR) || (RD && !DD && DR)

      cover(RD)
      cover(DD)

      assert(!(writeRequestDone && writeRequestDataDone))
    }
  }

  val outputArbiter = Module(new Arbiter(new OutputValue(), 2))
  io.output <> outputArbiter.io.out
  outputArbiter.io.in foreach (v => {
    v.bits := 0.U.asTypeOf(new OutputValue())
    v.valid := false.B
  })

  when(io.memory.write.response.valid) {
    val arbIn = outputArbiter.io.in(0)
    arbIn.valid := true.B
    arbIn.bits.value := io.memory.write.response.bits.value
    arbIn.bits.isError := io.memory.write.response.bits.isError
    arbIn.bits.tag := io.memory.write.response.bits.tag
    io.memory.write.response.ready := arbIn.ready
  }

  when(io.memory.read.response.valid) {
    val arbIn = outputArbiter.io.in(1)
    arbIn.valid := true.B
    arbIn.bits.value := io.memory.read.response.bits.value
    arbIn.bits.isError := io.memory.read.response.bits.isError
    arbIn.bits.tag := io.memory.read.response.bits.tag
    io.memory.read.response.ready := arbIn.ready
  }
}

object DataMemoryBuffer extends App {
  implicit val params = Parameters(tagWidth = 4, maxRegisterFileCommitCount = 2)
  ChiselStage.emitSystemVerilogFile(new DataMemoryBuffer)
}
