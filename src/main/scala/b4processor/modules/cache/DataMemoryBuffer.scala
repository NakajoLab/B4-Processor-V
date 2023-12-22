package b4processor.modules.cache

import circt.stage.ChiselStage
import b4processor.Parameters
import b4processor.connections.{LoadStoreQueue2Memory, OutputValue}
import b4processor.modules.memory.{MemoryAccessChannels, MemoryReadRequest, MemoryWriteRequest, MemoryWriteRequestData}
import b4processor.modules.vector._
import b4processor.structures.memoryAccess.MemoryAccessWidth
import b4processor.utils.operations.{LoadStoreOperation, LoadStoreWidth, MopOperation}
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
    val vectorInput = Vec(params.threads, Flipped(new VecRegFileReadIO()))
    val vectorOutput = Vec(params.threads, ValidIO(new VecRegFileWriteReq()))
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

  val vecIdx = RegInit(0.U(log2Up(params.vlen / 8).W))
  // val executedNum = RegInit(0.U(log2Up(params.vlen / 8).W))
  // val vecIdxToVrfWrite = RegNext(vecIdx)
  // val accumulator = RegInit(0.U(params.xprlen.W))
  val vecMemExecuting = RegInit(false.B)

  io.vectorOutput.foreach(o => {
    o.valid := false.B
    o.bits := DontCare
    o.bits.last := false.B
  })
  io.vectorInput := DontCare

  // val vecMemAccessLast = vecIdx === (io.vCsr(buffer.output.bits.tag.threadId).vl - 1.U)

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
      io.memory.read.request.bits := 0.U.asTypeOf(new MemoryReadRequest)
      when(entry.mopOperation === MopOperation.None) {
        io.memory.read.request.valid := true.B
        io.memory.read.request.bits := MemoryReadRequest.ReadToTag(
          entry.address,
          size,
          signed,
          entry.tag,
        )
      } .elsewhen(entry.mopOperation === MopOperation.UnitStride) {
        when(io.vCsr(entry.tag.threadId).vl === 0.U) {
          printf("vector load inst when vl=0, tag: %x, addr: %x\n", entry.tag.asUInt, entry.address)
        }
        io.memory.read.request.valid := !vecMemExecuting
        io.memory.read.request.bits := MemoryReadRequest.ReadToVector(
          baseAddress = entry.address,
          size = MemoryAccessWidth.DoubleWord,
          // sew=64 -> vl-1 -> vl(,0)-1
          // sew=32 -> vl=2*n => vl/2 - 1
          //           vl=2*n+1 => vl/2
          //           -> vl(,1) + vl(0)
          // sew=16 -> vl=4*n => vl/4 - 1
          //           vl=4*n+(1,2,3) => vl/4
          //           -> vl(,2) + vl(1,0).orR - 1
          // sew=8 -> vl=8*n => vl/8 - 1
          //          vl=8*n+(1,...7) => vl/8
          //          -> vl(,3) + vl(2,0).orR - 1
          vl = io.vCsr(entry.tag.threadId).getBurstLength,
          outputTag = entry.tag,
        )
        // ベクトルメモリアクセスのresp
        when(io.memory.read.response.valid) {
          io.vectorOutput(entry.tag.threadId).valid := true.B
          io.vectorOutput(entry.tag.threadId).bits.vd := entry.destVecReg.bits
          io.vectorOutput(entry.tag.threadId).bits.vtype := io.vCsr(entry.tag.threadId).vtype
          io.vectorOutput(entry.tag.threadId).bits.index := io.memory.read.response.bits.burstIndex
          io.vectorOutput(entry.tag.threadId).bits.last := (io.memory.read.response.bits.burstIndex === io.vCsr(entry.tag.threadId).getBurstLength)
          io.vectorOutput(entry.tag.threadId).bits.data := io.memory.read.response.bits.value
          io.vectorOutput(entry.tag.threadId).bits.vm := false.B

          // sew=64 -> Seq.fill(8, true.B)
          // sew=32 -> if(vl(0)) Seq(4*false.B, 4*true.B)
          // sew=16 -> if(vl(1,0)=1) 2*true.B else if(2) 4*true.B else if(3) 6*true.B
          val toWriteStrb = Wire(Vec(8, Bool()))
          toWriteStrb := MuxLookup(io.vCsr(entry.tag.threadId).vtype.vsew, VecInit(Seq.fill(8)(true.B)))(
            (0 until 4).map(
              i => i.U -> (if (i == 3) {
                // e64
                VecInit(Seq.fill(8)(true.B))
              } else if(i == 2) {
                // e32
                val __internal = VecInit(Seq.fill(8)(false.B))
                when(io.vCsr(entry.tag.threadId).vl(0)) {
                  __internal := VecInit(Seq.fill(4)(true.B) ++ Seq.fill(4)(false.B))
                } .otherwise {
                  __internal := VecInit(Seq.fill(8)(true.B))
                }
                __internal
              } else if(i==1) {
                // e16
                val __internal = VecInit(Seq.fill(8)(false.B))

                __internal := MuxLookup(io.vCsr(entry.tag.threadId).vl(1,0), VecInit(Seq.fill(8)(true.B)))(
                  (0 until 4).map(
                    j => j.U -> (if(j==0) {
                      VecInit(Seq.fill(8)(true.B))
                    } else {
                      VecInit(Seq.fill(j*2)(true.B) ++ Seq.fill((4-j)*2)(false.B))
                    })
                  )
                )
                __internal
              } else {
                // e8
                val __internal = VecInit(Seq.fill(8)(false.B))

                __internal := MuxLookup(io.vCsr(entry.tag.threadId).vl(2,0), VecInit(Seq.fill(8)(true.B)))(
                  (0 until 8).map(
                    j => j.U -> (if(j==0) {
                      VecInit(Seq.fill(8)(true.B))
                    } else {
                      VecInit(Seq.fill(j)(true.B) ++ Seq.fill(8-j)(false.B))
                    })
                  )
                )
                __internal
              })
            )
          )
          io.vectorOutput(entry.tag.threadId).bits.writeStrb := Mux(io.vectorOutput(entry.tag.threadId).bits.last, toWriteStrb, VecInit(Seq.fill(8)(true.B)))
        }
        6.toHexString
      }
      // ベクトルメモリアクセスの際に最終要素まで待つ
      when(io.memory.read.response.ready) {
        when(entry.mopOperation === MopOperation.UnitStride) {
          buffer.output.ready := io.vectorOutput(entry.tag.threadId).bits.last
          vecMemExecuting := !buffer.output.ready
        } .otherwise {
          buffer.output.ready := true.B
        }
      }
    }.elsewhen(operationIsStore) {
      def alignData(data: UInt, addrLower: UInt, width: LoadStoreWidth.Type): UInt = {
        require(addrLower.getWidth == 3)
        MuxLookup(
          width, 0.U,
        )(
          Seq(
            LoadStoreWidth.Byte -> Mux1H(
              (0 until 8).map(i =>
                (addrLower === i.U) -> (data(7, 0) << i * 8).asUInt,
              ),
            ),
            LoadStoreWidth.HalfWord -> Mux1H(
              (0 until 4).map(i =>
                (addrLower(2, 1) === i.U) -> (data(15, 0) << i * 16).asUInt,
              ),
            ),
            LoadStoreWidth.Word -> Mux1H(
              (0 until 2).map(i =>
                (addrLower(2) === i.U) -> (data(31, 0) << i * 32).asUInt,
              ),
            ),
            LoadStoreWidth.DoubleWord -> data,
          ),
        )
      }
      // TODO: ベクトルストア追加
      val addressUpper = entry.address(63, 3)
      val addressLower = entry.address(2, 0)
      when(!writeRequestDone) {
        io.memory.write.request.valid := true.B
        io.memory.write.request.bits.address := addressUpper ## 0.U(3.W)
        io.memory.write.request.bits.outputTag := entry.tag
        io.memory.write.request.bits.burstLen := Mux(entry.mopOperation === MopOperation.None, 0.U, io.vCsr(entry.tag.threadId).getBurstLength)
      }

      when(!writeRequestDataDone) {
        io.memory.write.requestData.valid := true.B
        when(entry.mopOperation === MopOperation.None) {
          io.memory.write.requestData.bits.data := alignData(entry.data, addressLower, entry.operationWidth)
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
        } .elsewhen(entry.mopOperation === MopOperation.UnitStride) {
          io.vectorInput(entry.tag.threadId).req.vd := entry.srcVecReg.bits
          io.vectorInput(entry.tag.threadId).req.sew := 3.U
          io.vectorInput(entry.tag.threadId).req.idx := vecIdx
          io.memory.write.requestData.bits.data := io.vectorInput(entry.tag.threadId).resp.vdOut
          // TODO: vlとsewに応じて変える
          val rawMask = Wire(UInt(8.W))
          rawMask := MuxLookup(io.vCsr(entry.tag.threadId).vtype.vsew, "hFF".U)(
            (0 until 4).map(
              i => i.U -> (if (i==3) {
                // e64
                "hFF".U
              } else if(i==2) {
                // e32
                Mux(io.vCsr(entry.tag.threadId).vl(0), "h0F".U, "hFF".U)
              } else if(i==1) {
                // e16
                MuxLookup(io.vCsr(entry.tag.threadId).vl(1,0), "hFF".U)(
                  (0 until 4).map(
                    j => j.U -> (if(j==0) {
                      "hFF".U
                    } else {
                      // Cat(Fill((4-j)*2, false.B), Fill(j*2, true.B))
                      Fill(j*2, true.B)
                    })
                  )
                )
              } else {
                // e8
                MuxLookup(io.vCsr(entry.tag.threadId).vl(2,0), "hFF".U)(
                  (0 until 8).map(
                    j => j.U -> (if(j==0) {
                      "hFF".U
                    } else {
                      // Cat(Fill(8-j, false.B), Fill(j, true.B))
                      Fill(j, true.B)
                    })
                  )
                )
              })
            )
          )
          io.memory.write.requestData.bits.mask := Mux(vecIdx === io.vCsr(entry.tag.threadId).getBurstLength, rawMask, "hFF".U)
          vecIdx := vecIdx + !vecMemExecuting.asUInt
        }
      }

      // 条件は真理値表を用いて作成
      val RD = writeRequestDone
      val DD = writeRequestDataDone
      val RR = io.memory.write.request.ready
      val DR = io.memory.write.requestData.ready
      // ベクトルメモリアクセスならばio_memory_write_request_valid && readyの時点でRDをtrueにする
      // また、RDかつDRかつ最終要素のときに下げる
      RD := (!RD && !DD && RR && !DR) || (RD && !DD && !DR) || (((!RD && RR) || (RD && !(DD || (vecIdx === io.vCsr(entry.tag.threadId).getBurstLength)))) && (entry.mopOperation =/= MopOperation.None))
      DD := ((!RD && !DD && !RR && DR) || (!RD && DD && !RR)) && ((entry.mopOperation === MopOperation.None) || vecIdx === io.vCsr(entry.tag.threadId).getBurstLength)
      // TODO: bufOutputReadyの条件を確認する
      val bufOutputReady = ((!RD && !DD && RR && DR) || (!RD && DD && RR) || (RD && !DD && DR)) && ((entry.mopOperation === MopOperation.None) || vecIdx === io.vCsr(entry.tag.threadId).getBurstLength)

      when(bufOutputReady) {
        buffer.output.ready := true.B
        when(entry.mopOperation === MopOperation.UnitStride) {
          vecMemExecuting := !buffer.output.ready
          vecIdx := 0.U
        }
      }
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
  // ここのvalidとタグが怪しい
  when(io.memory.read.response.valid) {
    val arbIn = outputArbiter.io.in(1)
    // ベクトルの場合，最終要素の時にvalidにする if(vectorOutput.valid) arbIn.valid when last
    // ベクトルをやってなければ無条件でtrue，そうでなければlastの時のみtrue
    arbIn.valid := !io.vectorOutput.map(_.valid).reduce(_ || _) || io.vectorOutput(buffer.output.bits.tag.threadId).bits.last
    // arbIn.valid := true.B
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
