package b4processor.modules.lsq

import b4processor.Parameters
import b4processor.connections.{
  CollectedOutput,
  Decoder2LoadStoreQueue,
  LoadStoreQueue2Memory,
  LoadStoreQueue2ReorderBuffer,
}
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import b4processor.utils.{FormalTools, Tag}
import b4processor.utils.operations.LoadStoreOperation

class LoadStoreQueue(implicit params: Parameters)
    extends Module
    with FormalTools {
  val io = IO(new Bundle {
    val decoders =
      Vec(
        params.decoderPerThread,
        Flipped(Decoupled(new Decoder2LoadStoreQueue())),
      )
    val outputCollector = Flipped(new CollectedOutput)
    // val vCsrOutput = Input(new VCsrBundle())
    val reorderBuffer = Flipped(
      Vec(
        params.maxRegisterFileCommitCount,
        Valid(new LoadStoreQueue2ReorderBuffer),
      ),
    )
    val memory = Decoupled(new LoadStoreQueue2Memory)
    val empty = Output(Bool())
    val full = Output(Bool())

    val head =
      if (params.debug) Some(Output(UInt(params.loadStoreQueueIndexWidth.W)))
      else None
    val tail =
      if (params.debug) Some(Output(UInt(params.loadStoreQueueIndexWidth.W)))
      else None
    // LSQのエントリ数はこのままでいいのか
  })

  val defaultEntry = LoadStoreQueueEntry.default

  val head = RegInit(0.U(params.loadStoreQueueIndexWidth.W))
  val tail = RegInit(0.U(params.loadStoreQueueIndexWidth.W))
  io.empty := head === tail
  io.full := head + 1.U === tail

  val buffer = RegInit(
    VecInit(
      Seq.fill(math.pow(2, params.loadStoreQueueIndexWidth).toInt)(defaultEntry),
    ),
  )
  var insertIndex = head

  /** デコードした命令をLSQに加えるかどうか確認し，l or s 命令ならばエンキュー */
  for (i <- 0 until params.decoderPerThread) {
    val decoder = io.decoders(i)
    decoder.ready := tail =/= insertIndex + 1.U
    val entryValid = decoder.ready && decoder.valid

    /** 現状，(LSQの最大エントリ数 = リオーダバッファの最大エントリ数)であり，
      * プロセッサで同時実行可能な最大命令数がリオーダバッファのエントリ番号数(dtag数)であることから，
      * エンキュー時の命令待機は必要ないが，LSQのエントリ数を減らした場合，必要
      */
    when(entryValid) {
      //      printf("isLoad = %d\n", decoder.bits.opcode === LOAD)
      buffer(insertIndex) := LoadStoreQueueEntry.validEntry(
        // opcode = 1(load), 0(store) (bit数削減)
        operation = decoder.bits.operation,
        operationWidth = decoder.bits.operationWidth,
        destinationTag = decoder.bits.destinationTag,
        address = decoder.bits.address,
        addressValid = decoder.bits.addressValid,
        addressOffset = decoder.bits.addressOffset,
        addressTag = decoder.bits.addressTag,
        storeDataTag = decoder.bits.storeDataTag,
        storeData = decoder.bits.storeData,
        storeDataValid = decoder.bits.storeDataValid,
        destVecReg = decoder.bits.destVecReg,
        srcVecReg = decoder.bits.srcVecReg,
        mOpOperation = decoder.bits.mopOperation,
        umopOperation = decoder.bits.umopOperation,
      )
    }
    insertIndex = insertIndex + entryValid.asUInt
  }

  head := insertIndex

  /** オペランドバイパスのタグorPCが対応していた場合は，ALUを読み込む */

  private val output = io.outputCollector.outputs
  for (buf <- buffer) {
    for (o <- output) {
      when(o.valid && buf.valid) {
        when(
          buf.storeDataTag === o.bits.tag && !buf.storeDataValid && buf.operation === LoadStoreOperation.Store,
        ) {
          buf.storeDataTag := Tag(0, 0)
          buf.storeData := o.bits.value
          buf.storeDataValid := true.B
        }
        when(buf.addressTag === o.bits.tag && !buf.addressValid) {
          buf.addressTag := Tag(0, 0)
          buf.address := o.bits.value
          buf.addressValid := true.B
        }
      }
    }
  }

  for (rb <- io.reorderBuffer) {
    when(rb.valid) {
      for (buf <- buffer) {
        when(buf.valid && (rb.bits.destinationTag === buf.destinationTag)) {
          buf.readyReorderSign := true.B
        }
      }
    }
  }

  /** Ra = 1 && R=0の先行するストア命令と実効アドレスが被っていなければ ロード命令をメモリに送出 送出後, R=1
    */

  /** Ra = Rd = 1 && リオーダバッファから命令送出信号が来れば ストア命令をメモリに送出 送出後, R=1
    */
  // Overlap : 先行する命令の実効アドレスとの被りがあるかのフラグ (T:あり　F:なし)
  // Address : 送出対象の命令のアドレスを格納
  val Overlap = WireInit(
    VecInit(Seq.fill(params.loadStoreQueueCheckLength)(false.B)),
  )
  val Address = WireInit(
    VecInit(Seq.fill(params.loadStoreQueueCheckLength)(0.U(64.W))),
  )
  val AddressValid = WireInit(
    VecInit(Seq.fill(params.loadStoreQueueCheckLength)(false.B)),
  )
  val EntryValid = WireInit(
    VecInit(Seq.fill(params.loadStoreQueueCheckLength)(false.B)),
  )

  // emissionindex : 送出可能か調べるエントリを指すindex
  // nexttail      : 1クロック分の送出確認後，動かすtailのエントリを指すindex
  var nextTail = tail
  var isSet = false.B

  io.memory.bits := 0.U.asTypeOf(new LoadStoreQueue2Memory)
  io.memory.valid := false.B
  val toMemoryIndex = WireDefault(0.U(head.getWidth.W))
  for (i <- 0 until params.loadStoreQueueCheckLength) {
    val checkIndex = tail + i.U
    val checkOk = WireDefault(false.B)
    when(buffer(checkIndex).valid) {
      val buf = buffer(checkIndex)
      Address(i) := (buf.address.asSInt + buf.addressOffset).asUInt
      AddressValid(i) := buf.addressValid
      EntryValid(i) := buf.valid
      //      Overlap(i) := false.B
      // 先行する命令が持つアドレスの中に被りがある場合，Overlap(i) := true.B
      if (i != 0) {
        for (j <- 0 until i) {
          when(EntryValid(j)) {
            when(
              (AddressValid(j) && Address(j) === Address(i)) || !AddressValid(j),
            ) {
              Overlap(i) := true.B
            }
          }
        }
      }

      val operationIsStore = {
        LoadStoreOperation.Store === buf.operation
      }
      val operationIsLoadOrStore = operationIsStore || (LoadStoreOperation.Load === buf.operation) || (LoadStoreOperation.LoadUnsigned === buf.operation)

      // io.memory(i).valid :=  io.memory(i).ready && (head =/= tail) && ("loadの送出条件" || "storeの送出条件")
      checkOk := (head =/= tail) && buf.valid &&
        buf.addressValid && operationIsLoadOrStore && buf.storeDataValid && buf.readyReorderSign

      // ベクトルCSRを読み、ベクトルが有効ならば上、無効なら下を選ぶようにするべき
      /*
       * checkOk := (head =/= tail) && buf.valid &&
           buf.addressValid &&
           ((!operationIsStore && !Overlap(i)) ||
             (operationIsStore && buf.storeDataValid &&
               buf.readyReorderSign))
       */
      io.memory.valid := checkOk | isSet
      // 送出実行
      when(checkOk && !isSet) {
        toMemoryIndex := checkIndex
        io.memory.bits.tag := buf.destinationTag
        io.memory.bits.data := buf.storeData
        io.memory.bits.address := Address(i)
        io.memory.bits.operation := buf.operation
        io.memory.bits.operationWidth := buf.operationWidth
        io.memory.bits.destVecReg := buf.destVecReg
        io.memory.bits.srcVecReg := buf.srcVecReg
        io.memory.bits.mopOperation := buf.mopOperation
        io.memory.bits.umopOperation := buf.umopOperation
        when(io.memory.ready) {
          buf := LoadStoreQueueEntry.default
        }
      }
    }

    isSet = isSet | checkOk
  }
  when(!buffer(tail).valid && head =/= tail) {
    tail := tail + 1.U
  }

  // デバッグ
  if (params.debug) {
    io.head.get := head
    io.tail.get := tail
  }

  cover(io.full, "could be full at some point")
  cover(io.empty, "could be empty at some point")
//  cover(io.full && io.empty, "this should not happen only test perpose")

  // both full and empty should not be high at the same time
  assert(!(io.full && io.empty))

  when(pastValid) {
    for (i <- 0 to params.decoderPerThread) {
      cover(
        head === past(head) + i.U,
        "there should ba a time where head increments by $i",
      )
    }

    // head should move at maximum the number of decoders valid
    val valid_count =
      io.decoders
        .map(i => {
          val w = Wire(UInt(head.getWidth.W))
          w := i.valid.asUInt
          w
        })
        .reduce(_ + _)
    assert((head - past(head)) <= past(valid_count), "head is moving too fast?")

    // when full decoders ready should not be asserted
    when(io.full) {
      for ((d, i) <- io.decoders.zipWithIndex)
        assert(!d.ready, s"decoder $i should not be ready")
    }

    // when empty at least 1 decoder is asserted ready
    when(io.empty) {
      assert(
        io.decoders.map(_.ready).reduce(_ || _),
        "no decoders are ready when empty",
      )
    }

    // output to memory should be true at some point
    cover(io.memory.valid, "memory should be valid at some point")

    for ((b, i) <- buffer.zipWithIndex) {
      cover(b.valid, s"buffer $i valid should be valid at some point")
    }

    when(past(io.memory.valid && !io.memory.ready)) {
      assert(stable(io.memory.valid))
      assert(
        stable(io.memory.bits) || past(toMemoryIndex) - toMemoryIndex > 0.U,
      )
    }
  }

  var wasHead = false.B
  for (i <- buffer.indices) {
    val idx = tail + i.U
    val ishead = idx === head
    wasHead = wasHead | ishead
    when(wasHead) {
      assume(!buffer(idx).valid, s"$i")
    }
  }

  takesEveryValue(head)
  takesEveryValue(tail)

  val wasFull = RegInit(false.B)
  when(io.full) {
    wasFull := true.B
  }
  cover(wasFull && io.empty)
}

object LoadStoreQueue extends App {
  implicit val params =
    Parameters(
      maxRegisterFileCommitCount = 2,
      tagWidth = 4,
      loadStoreQueueIndexWidth = 3,
    )

//  println(ChiselStage.emitCHIRRTL(new LoadStoreQueue))
//  println(ChiselStage.emitFIRRTLDialect(new LoadStoreQueue))
//  println(ChiselStage.emitHWDialect(new LoadStoreQueue))

  ChiselStage.emitSystemVerilogFile(
    new LoadStoreQueue,
    firtoolOpts = Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,noAlwaysComb,verifLabels",
//      "--emit-chisel-asserts-as-sva",
      "--dedup",
      "--mlir-pass-statistics",
    ),
  )

  var s = ChiselStage.emitSystemVerilog(
    new LoadStoreQueue,
    firtoolOpts = Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays,noAlwaysComb,verifLabels",
//      "--emit-chisel-asserts-as-sva",
      "--dedup",
    ),
  )
}
