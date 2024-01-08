package b4processor.modules.vector

import b4processor.Parameters
import b4processor.connections._
import b4processor.utils.operations._
import chisel3._
import chisel3.experimental.BundleLiterals._
import circt.stage.ChiselStage
import chisel3.util._

class VectorExecUnitIO(implicit params: Parameters) extends Bundle {
  val reservationStation = Flipped(Decoupled(new ReservationStation2VExtExecutor()))
  val vCsr = Vec(params.threads, Input(new VCsrBundle()))
  val output = Irrevocable(new OutputValue())
  val vectorInput = Flipped(new VecRegFileReadIO())
  val vectorOutput = ValidIO(new VecRegFileWriteReq())
}

/**
 * combinational execution unit (arithmetic/logical)
 * @param params
 */
abstract class VectorExecUnit(implicit params: Parameters) extends Module {
  def exec(reservation: ReservationStation2VExtExecutor, values: VecRegFileReadResp, vsew: UInt): Seq[UInt]

  val io = IO(new VectorExecUnitIO())

  val instInfoReg = RegInit(Valid(new ReservationStation2VExtExecutor()).Lit(
    _.valid -> false.B,
  ))

  val idx = RegInit(0.U(log2Up(params.vlen/8).W))
  val reductionAccumulator = RegInit(0.U(params.xprlen.W))
  // we don't measure performance by number of elements now
  // val executedNum = RegInit(0.U(log2Up(params.vlen/8).W))
  when((io.reservationStation.valid && io.reservationStation.ready) || io.vectorOutput.bits.last) {
    idx := 0.U
    // executedNum := 0.U
  } .elsewhen(instInfoReg.valid) {
    idx := idx + 1.U
    // executedNum := executedNum + (io.dataOut.toVRF.valid && io.dataOut.toVRF.bits.writeReq).asUInt
  } .otherwise {
    idx := 0.U
    // executedNum := 0.U
  }

  when(io.reservationStation.valid && io.reservationStation.ready) {
    instInfoReg.valid := true.B
    instInfoReg.bits := io.reservationStation.bits
  } .elsewhen(io.vectorOutput.bits.last) {
    instInfoReg.valid := false.B
  } .otherwise {
    instInfoReg := instInfoReg
  }

  assert(!(instInfoReg.valid && io.vCsr(instInfoReg.bits.destinationTag.threadId).vl === 0.U), "Zero vl instruction in VectorExecUnit")

  // TODO: vs1Outの判定はMVVではなくベクトルマスク命令か否かで
  io.vectorInput.req.idx := idx
  io.vectorInput.req.sew := 3.U
  io.vectorInput.req.vs1 := instInfoReg.bits.srcVecReg1
  io.vectorInput.req.vs2 := instInfoReg.bits.srcVecReg2
  io.vectorInput.req.vd := instInfoReg.bits.destVecReg
  // マスクは今回は考えない
  io.vectorInput.req.readVdAsMaskSource := false.B

  val execValue1 = Mux(VectorOperands.readVs1(instInfoReg.bits.vecOperand), io.vectorInput.resp.vs1Out, instInfoReg.bits.scalarVal)
  val execValue2 = io.vectorInput.resp.vs2Out
  val execValue3 = io.vectorInput.resp.vdOut
  val execValueVM = io.vectorInput.resp.vm

  val valueToExec = Wire(new VecRegFileReadResp())
  valueToExec.vs1Out := execValue1
  valueToExec.vs2Out := execValue2
  valueToExec.vdOut := execValue3
  valueToExec.vm := execValueVM

  io.vectorOutput.bits.last := (idx === io.vCsr(instInfoReg.bits.destinationTag.threadId).getBurstLength) && instInfoReg.valid
  io.vectorOutput.bits.index := idx
  io.vectorOutput.valid := instInfoReg.valid
  io.vectorOutput.bits.vtype := io.vCsr(instInfoReg.bits.destinationTag.threadId).vtype
  io.vectorOutput.bits.vd := instInfoReg.bits.destVecReg
  io.vectorOutput.bits.vm := false.B

  val toWriteStrb = Wire(Vec(8, Bool()))
  toWriteStrb := MuxLookup(io.vCsr(instInfoReg.bits.destinationTag.threadId).vtype.vsew, VecInit(Seq.fill(8)(true.B)))(
    (0 until 4).map(
      i => i.U -> (if (i == 3) {
        // e64
        VecInit(Seq.fill(8)(true.B))
      } else if(i == 2) {
        // e32
        val __internal = VecInit(Seq.fill(8)(false.B))
        when(io.vCsr(instInfoReg.bits.destinationTag.threadId).vl(0)) {
          __internal := VecInit(Seq.fill(4)(true.B) ++ Seq.fill(4)(false.B))
        } .otherwise {
          __internal := VecInit(Seq.fill(8)(true.B))
        }
        __internal
      } else if(i==1) {
        // e16
        val __internal = VecInit(Seq.fill(8)(false.B))

        __internal := MuxLookup(io.vCsr(instInfoReg.bits.destinationTag.threadId).vl(1,0), VecInit(Seq.fill(8)(true.B)))(
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

        __internal := MuxLookup(io.vCsr(instInfoReg.bits.destinationTag.threadId).vl(2,0), VecInit(Seq.fill(8)(true.B)))(
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

  io.vectorOutput.bits.writeStrb := Mux(io.vectorOutput.bits.last, toWriteStrb, VecInit(Seq.fill(8)(true.B)))

  io.reservationStation.ready := (!instInfoReg.valid || io.vectorOutput.bits.last) && io.output.ready

  val execResult = exec(instInfoReg.bits, valueToExec, io.vCsr(instInfoReg.bits.destinationTag.threadId).vtype.vsew)

  io.output.valid := io.vectorOutput.bits.last
  io.output.bits.value := 0.U
  io.output.bits.isError := false.B
  io.output.bits.tag := instInfoReg.bits.destinationTag
}

class IntegerAluExecUnit(implicit params: Parameters) extends VectorExecUnit {
  require(params.xprlen == 64, "Only 64bit xprlen is supported. Please refer to 16bit Sensation anime for more details.")
  override def exec(reservation: ReservationStation2VExtExecutor, values: VecRegFileReadResp, vsew: UInt): Seq[UInt] = {
    val vadd = Wire(UInt(params.xprlen.W))
    vadd := 0.U(params.xprlen.W)
    for(i <- 0 until 4) {
      val elen = 8 << i
      switch(vsew) {
        is(i.U) {
          val res = Wire(Vec(params.xprlen/elen, UInt(elen.W)))
          for(j <- res.indices) {
            res(j) := values.vs2Out(j*elen+elen-1, j*elen) + values.vs1Out(j*elen+elen-1, j*elen)
          }
          vadd := Cat(res.reverse)
        }
      }
    }

    val vmul = Wire(UInt(params.xprlen.W))
    vmul := 0.U(params.xprlen.W)
    for(i <- 0 until 4) {
      val elen = 8 << i
      switch(vsew) {
        is(i.U) {
          val res = Wire(Vec(params.xprlen/elen, UInt(elen.W)))
          for(j <- res.indices) {
            res(j) := values.vs2Out(j*elen+elen-1, j*elen) * values.vs1Out(j*elen+elen-1, j*elen)
          }
          vmul := Cat(res.reverse)
        }
      }
    }

    val vredsum = Wire(UInt(params.xprlen.W))
    vredsum := 0.U(params.xprlen.W)
    for(i <- 0 until 4) {
      val elen = 8 << i
      switch(vsew) {
        is(i.U) {
          val res = Wire(Vec(params.xprlen/elen, UInt(elen.W)))
          res(0) := values.vs2Out(elen-1, 0) + values.vs1Out(elen-1, 0)
          for(j <- 1 until res.length) {
            res(j) := values.vs2Out(j*elen+elen-1, j*elen) + res(j-1)
          }
          vredsum := res.last
        }
      }
    }

    // VADD, VMUL, VREDSUM
    vadd :: vmul :: vredsum :: Nil
  }
  import VectorOperation._
  valueToExec.vs1Out := Mux(opIsRedsum(instInfoReg.bits.vecOperation) && (idx =/= 0.U),
    reductionAccumulator, execValue1)
  // reductionの場合，末尾要素には0を入れる
  when(opIsRedsum(instInfoReg.bits.vecOperation)) {
    // writeStrbがfalseの部分は0で無効化
    val internalMask = Wire(Vec(8, UInt(8.W)))
    for((d, i) <- internalMask.zipWithIndex) {
      d := Mux(io.vectorOutput.bits.writeStrb(i), "hFF".U, "h00".U)
    }
    valueToExec.vs2Out := execValue2 & Cat(internalMask.reverse)
  }

  val rawResult = MuxLookup(instInfoReg.bits.vecOperation, 0.U)(
    Seq(ADD, MUL, REDSUM).zipWithIndex.map(
      x => x._1 -> execResult(x._2)
    )
  )
  reductionAccumulator := rawResult

  io.vectorOutput.bits.data := rawResult

  // reductionならば最後の要素のみ書き，かつidxは0
  when(opIsRedsum(instInfoReg.bits.vecOperation)) {
    // io.vectorOutput.bits.writeStrb.foreach(_ := io.vectorOutput.bits.last)
    io.vectorOutput.valid := io.vectorOutput.bits.last
    io.vectorOutput.bits.index := 0.U
  }

  when(instInfoReg.valid) {
    when(instInfoReg.bits.vecOperation === VectorOperation.MV_X_S) {
      // vmv.x.s
      io.vectorOutput.valid := true.B
      io.vectorOutput.bits.last := true.B
      io.vectorOutput.bits.writeStrb.foreach(_ := false.B)
      io.output.bits.value := MuxLookup(io.vCsr(instInfoReg.bits.destinationTag.threadId).vtype.vsew, io.vectorInput.resp.vs2Out(63, 0))(
        (0 until 4).map(
          i => i.U -> io.vectorInput.resp.vs2Out((8 << i) - 1, 0)
        )
      )
    } .elsewhen(instInfoReg.bits.vecOperation === VectorOperation.MV_S_X) {
      // vmv.s.x
      io.vectorOutput.valid := true.B
      io.vectorOutput.bits.last := true.B
      io.vectorOutput.bits.writeStrb := MuxLookup(io.vCsr(instInfoReg.bits.destinationTag.threadId).vtype.vsew, VecInit(Seq.fill(8)(true.B)))(Seq(
        0.U -> VecInit(Seq.fill(1)(true.B) ++ Seq.fill(7)(false.B)),
        1.U -> VecInit(Seq.fill(2)(true.B) ++ Seq.fill(6)(false.B)),
        2.U -> VecInit(Seq.fill(4)(true.B) ++ Seq.fill(4)(false.B)),
        3.U -> VecInit(Seq.fill(8)(true.B))
      ))
      io.vectorOutput.bits.data := instInfoReg.bits.scalarVal
    }
  }
}

object IntegerAluExecUnit extends App {
  implicit val params: Parameters = Parameters()
  def apply(implicit params: Parameters): IntegerAluExecUnit = new IntegerAluExecUnit()
  ChiselStage.emitSystemVerilogFile(new IntegerAluExecUnit(), firtoolOpts =  Array("-disable-all-randomization", "-strip-debug-info", "-add-vivado-ram-address-conflict-synthesis-bug-workaround"))
}
