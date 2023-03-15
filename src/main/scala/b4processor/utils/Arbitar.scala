// Copied from chisel source
package b4processor.utils

import chisel3._

/** IO bundle definition for an Arbiter, which takes some number of ready-valid
  * inputs and outputs (selects) at most one.
  * @groupdesc Signals
  *   The actual hardware fields of the Bundle
  *
  * @param gen
  *   data type
  * @param n
  *   number of inputs
  */
class B4ArbiterIO[T <: Data](private val gen: T, val n: Int) extends Bundle {
  // See github.com/freechipsproject/chisel3/issues/765 for why gen is a private val and proposed replacement APIs.

  /** Input data, one per potential sender
    *
    * @group Signals
    */
  val in = Flipped(Vec(n, util.Decoupled(gen)))

  /** Output data after arbitration
    *
    * @group Signals
    */
  val out = util.Decoupled(gen)

  /** One-Hot vector indicating which output was chosen
    *
    * @group Signals
    */
  val chosen = Output(UInt(util.log2Ceil(n).W))
}

/** Arbiter Control determining which producer has access
  */
private object B4ArbiterCtrl {
  def apply(request: Seq[Bool]): Seq[Bool] = request.length match {
    case 0 => Seq()
    case 1 => Seq(true.B)
    case _ => true.B +: request.tail.init.scanLeft(request.head)(_ || _).map(!_)
  }
}

abstract class B4LockingArbiterLike[T <: Data](
  gen: T,
  n: Int,
  count: Int,
  needsLock: Option[T => Bool]
) extends Module {
  def grant: Seq[Bool]
  def choice: UInt
  val io = IO(new B4ArbiterIO(gen, n))

  io.chosen := choice
  io.out.valid := io.in(io.chosen).valid
  io.out.bits := io.in(io.chosen).bits

  if (count > 1) {
    val lockCount = util.Counter(count)
    val lockIdx = Reg(UInt())
    val locked = lockCount.value =/= 0.U
    val wantsLock = needsLock.map(_(io.out.bits)).getOrElse(true.B)

    when(io.out.fire && wantsLock) {
      lockIdx := io.chosen
      lockCount.inc()
    }

    when(locked) { io.chosen := lockIdx }
    for ((in, (g, i)) <- io.in.zip(grant.zipWithIndex))
      in.ready := Mux(locked, lockIdx === i.asUInt, g) && io.out.ready
  } else {
    for ((in, g) <- io.in.zip(grant))
      in.ready := g && io.out.ready
  }
}

class B4LockingRRArbiter[T <: Data](
  gen: T,
  n: Int,
  count: Int,
  needsLock: Option[T => Bool] = None
) extends B4LockingArbiterLike[T](gen, n, count, needsLock) {
  lazy val lastGrant = util.RegEnable(io.chosen, 0.U, io.out.fire)
  lazy val grantMask = (0 until n).map(_.asUInt > lastGrant)
  lazy val validMask =
    io.in.zip(grantMask).map { case (in, g) => in.valid && g }

  override def grant: Seq[Bool] = {
    val ctrl = B4ArbiterCtrl(
      (0 until n).map(i => validMask(i)) ++ io.in.map(_.valid)
    )
    (0 until n).map(i => ctrl(i) && grantMask(i) || ctrl(i + n))
  }

  override lazy val choice = WireDefault((n - 1).asUInt)
  for (i <- n - 2 to 0 by -1)
    when(io.in(i).valid) { choice := i.asUInt }
  for (i <- n - 1 to 1 by -1)
    when(validMask(i)) { choice := i.asUInt }
}

class B4LockingArbiter[T <: Data](
  gen: T,
  n: Int,
  count: Int,
  needsLock: Option[T => Bool] = None
) extends B4LockingArbiterLike[T](gen, n, count, needsLock) {
  def grant: Seq[Bool] = B4ArbiterCtrl(io.in.map(_.valid))

  override lazy val choice = WireDefault((n - 1).asUInt)
  for (i <- n - 2 to 0 by -1)
    when(io.in(i).valid) { choice := i.asUInt }
}

/** Hardware module that is used to sequence n producers into 1 consumer.
  * Producers are chosen in round robin order.
  *
  * @param gen
  *   data type
  * @param n
  *   number of inputs
  * @example
  *   {{{ val arb = Module(new RRArbiter(UInt(), 2)) arb.io.in(0) <>
  *   producer0.io.out arb.io.in(1) <> producer1.io.out consumer.io.in <>
  *   arb.io.out }}}
  */
class B4RRArbiter[T <: Data](val gen: T, val n: Int)
    extends B4LockingRRArbiter[T](gen, n, 1)

/** Hardware module that is used to sequence n producers into 1 consumer.
  * Priority is given to lower producer.
  *
  * @param gen
  *   data type
  * @param n
  *   number of inputs
  *
  * @example
  *   {{{ val arb = Module(new Arbiter(UInt(), 2)) arb.io.in(0) <>
  *   producer0.io.out arb.io.in(1) <> producer1.io.out consumer.io.in <>
  *   arb.io.out }}}
  */
class B4Arbiter[T <: Data](val gen: T, val n: Int) extends Module {
  val io = IO(new B4ArbiterIO(gen, n))

  io.chosen := (n - 1).asUInt
  io.out.bits := io.in(n - 1).bits
  for (i <- n - 2 to 0 by -1) {
    when(io.in(i).valid) {
      io.chosen := i.asUInt
      io.out.bits := io.in(i).bits
    }
  }

  val grant = B4ArbiterCtrl(io.in.map(_.valid))
  for ((in, g) <- io.in.zip(grant))
    in.ready := g && io.out.ready
  io.out.valid := !grant.last || io.in.last.valid
}