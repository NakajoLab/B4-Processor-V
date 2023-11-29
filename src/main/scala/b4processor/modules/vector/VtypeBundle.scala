package b4processor.modules.vector


import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import b4processor._

class VtypeBundle(implicit params: Parameters) extends Bundle {
  val vill = Bool()
  val vma = Bool()
  val vta = Bool()
  val vsew = UInt(3.W)
  val vlmul = UInt(3.W)
  def getBits: UInt = Mux(vill, Cat(true.B, 0.U((params.xprlen-1).W)), Cat(0.U((params.xprlen-8).W), vma, vta, vsew, vlmul))
  def setBits(vtypei: UInt): Unit = {
    vill := vtypei(params.xprlen-1) || (vtypei(params.xprlen-2, 8) =/= 0.U) || vtypei(5) || (vtypei(2,0) =/= 0.U)
    // villが1ならばこれらは0にすべき？
    vma := vtypei(7)
    vta := vtypei(6)
    vsew := vtypei(5,3)
    vlmul := vtypei(2,0)
  }
}

class VCsrBundle(implicit params: Parameters) extends Bundle {
  val vtype = new VtypeBundle()
  val vl = UInt((log2Up(params.vlenb) + 1).W)
  def getBurstLength: UInt = {
    MuxLookup(vtype.vsew, vl-1.U)(
      (0 until 4).map(
        i => i.U -> {
          vl(vl.getWidth-1, 3-i) - 1.U + {
            i match {
              case 3 => 0.U
              case 2 => vl(0).asUInt
              case _ => vl(2-i,0).orR.asUInt
            }
          }
        }
      )
    )
  }
}