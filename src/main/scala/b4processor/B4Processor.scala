package b4processor

import b4processor.modules.AtomicLSU
import circt.stage.ChiselStage
import b4processor.modules.branch_output_collector.BranchOutputCollector
import b4processor.modules.cache.{DataMemoryBuffer, InstructionMemoryCache}
import b4processor.modules.csr.{CSR, CSRReservationStation}
import b4processor.modules.decoder.{Decoder, Uncompresser}
import b4processor.modules.executor.Executor
import b4processor.modules.fetch.{Fetch, FetchBuffer}
import b4processor.modules.lsq.LoadStoreQueue
import b4processor.modules.memory.ExternalMemoryInterface
import b4processor.modules.outputcollector.{OutputCollector, OutputCollector2}
import b4processor.modules.registerfile.{RegisterFile, RegisterFileMem}
import b4processor.modules.reorderbuffer.ReorderBuffer
import b4processor.modules.reservationstation.{IssueBuffer, ReservationStation}
import b4processor.utils.axi.{ChiselAXI, VerilogAXI}
import chisel3._
import chisel3.experimental.dataview.DataViewable

class B4Processor(implicit params: Parameters) extends Module {
  override val desiredName = "B4ProcessorInternal"
  val axi = IO(new ChiselAXI(64, 64))

  val registerFileContents =
    if (params.debug) Some(IO(Output(Vec(params.threads, Vec(32, UInt(64.W))))))
    else None

  require(params.decoderPerThread >= 1, "スレッド毎にデコーダは1以上必要です。")
  require(params.threads >= 1, "スレッド数は1以上です。")
  require(params.tagWidth >= 1, "タグ幅は1以上である必要があります。")
  require(
    params.maxRegisterFileCommitCount >= 1,
    "レジスタファイルへのコミット数は1以上である必要があります。"
  )

  private val instructionCache =
    Seq.fill(params.threads)(Module(new InstructionMemoryCache))
  private val fetch = Seq.fill(params.threads)(Module(new Fetch))
  private val fetchBuffer = Seq.fill(params.threads)(Module(new FetchBuffer))
  private val reorderBuffer =
    Seq.fill(params.threads)(Module(new ReorderBuffer))
  private val registerFile =
    Seq.fill(params.threads)(Module(new RegisterFileMem))
  private val loadStoreQueue =
    Seq.fill(params.threads)(Module(new LoadStoreQueue))
  private val dataMemoryBuffer = Module(new DataMemoryBuffer)

  private val outputCollector = Module(new OutputCollector2)
  private val branchAddressCollector = Module(new BranchOutputCollector())

  private val uncompresser = Seq.fill(params.threads)(
    Seq.fill(params.decoderPerThread)(Module(new Uncompresser))
  )
  private val decoders = Seq.fill(params.threads)(
    (0 until params.decoderPerThread).map(n => Module(new Decoder))
  )
  private val reservationStation =
    Seq.fill(params.threads)(Module(new ReservationStation))
  private val issueBuffer = Module(new IssueBuffer)
  private val executors = Seq.fill(params.executors)(Module(new Executor))

  private val externalMemoryInterface = Module(new ExternalMemoryInterface)

  private val csrReservationStation =
    Seq.fill(params.threads)(Module(new CSRReservationStation))
  private val csr = Seq.fill(params.threads)(Module(new CSR))
  private val amo = Module(new AtomicLSU)

  axi <> externalMemoryInterface.io.coordinator

  /** 出力コレクタとデータメモリ */
  outputCollector.io.memoryReadResult <> externalMemoryInterface.io.dataReadOut
  outputCollector.io.memoryWriteResult <> externalMemoryInterface.io.dataWriteOut

  /** レジスタのコンテンツをデバッグ時に接続 */
  if (params.debug) {
    for (tid <- 0 until params.threads)
      for (i <- 0 until 32)
        registerFileContents.get(tid)(i) <> registerFile(tid).io.values.get(i)
  }

  for (e <- 0 until params.executors) {

    /** リザベーションステーションと実行ユニットを接続 */
    issueBuffer.io.executors(e) <> executors(e).io.reservationStation

    /** 出力コレクタと実行ユニットの接続 */
    outputCollector.io.executor(e) <> executors(e).io.out

    /** 分岐結果コレクタと実行ユニットの接続 */
    branchAddressCollector.io.executor(e) <> executors(e).io.fetch
  }

  for (tid <- 0 until params.threads) {
    reservationStation(tid).io.issue <> issueBuffer.io.reservationStations(tid)

    amo.io.collectedOutput := outputCollector.io.outputs
    amo.io.readRequest <> externalMemoryInterface.io.amoReadRequests
    amo.io.writeRequest <> externalMemoryInterface.io.amoWriteRequests
    amo.io.readResponse <> externalMemoryInterface.io.amoReadOut
    amo.io.writeResponse <> externalMemoryInterface.io.amoWriteOut
    amo.io.output <> outputCollector.io.amo
    amo.io.reorderBuffer(tid) <> reorderBuffer(tid).io.loadStoreQueue

    /** リザベーションステーションと実行ユニットの接続 */
    reservationStation(tid).io.collectedOutput :=
      outputCollector.io.outputs(tid)

    csr(tid).io.threadId := tid.U
    instructionCache(tid).io.threadId := tid.U
    reorderBuffer(tid).io.threadId := tid.U
    registerFile(tid).io.threadId := tid.U

    /** 命令キャッシュとフェッチを接続 */
    instructionCache(tid).io.fetch <> fetch(tid).io.cache

    /** フェッチとフェッチバッファの接続 */
    fetch(tid).io.fetchBuffer <> fetchBuffer(tid).io.input

    fetch(tid).io.threadId := tid.U

    csrReservationStation(tid).io.toCSR <> csr(tid).io.decoderInput

    csr(tid).io.CSROutput <> outputCollector.io.csr(tid)

    csrReservationStation(tid).io.output <> outputCollector.io.outputs(tid)

    reorderBuffer(tid).io.csr <> csr(tid).io.reorderBuffer

    fetch(tid).io.csr <> csr(tid).io.fetch

    fetch(tid).io.csrReservationStationEmpty :=
      csrReservationStation(tid).io.empty

    branchAddressCollector.io.isError(tid) := reorderBuffer(tid).io.isError
    fetch(tid).io.isError := reorderBuffer(tid).io.isError

    /** フェッチとデコーダの接続 */
    for (d <- 0 until params.decoderPerThread) {
      amo.io.decoders(tid)(d) <> decoders(tid)(d).io.amo

      decoders(tid)(d).io.csr <> csrReservationStation(tid).io.decoderInput(d)

      decoders(tid)(d).io.threadId := tid.U

      uncompresser(tid)(d).io.fetch <> fetchBuffer(tid).io.output(d)

      /** デコーダとフェッチバッファ */
      decoders(tid)(d).io.instructionFetch <> uncompresser(tid)(d).io.decoder

      /** デコーダとリオーダバッファを接続 */
      decoders(tid)(d).io.reorderBuffer <> reorderBuffer(tid).io.decoders(d)

      /** デコーダとリザベーションステーションを接続 */
      reservationStation(tid).io.decoder(d) <>
        decoders(tid)(d).io.reservationStation

      /** デコーダとレジスタファイルの接続 */
      decoders(tid)(d).io.registerFile <> registerFile(tid).io.decoders(d)

      /** デコーダとLSQの接続 */
      decoders(tid)(d).io.loadStoreQueue <> loadStoreQueue(tid).io.decoders(d)

      /** デコーダと出力コレクタ */
      decoders(tid)(d).io.outputCollector := outputCollector.io.outputs(tid)

      /** デコーダとLSQの接続 */
      loadStoreQueue(tid).io.decoders(d) <> decoders(tid)(d).io.loadStoreQueue
    }

    /** フェッチと分岐結果の接続 */
    fetch(tid).io.collectedBranchAddresses :=
      branchAddressCollector.io.fetch(tid)

    /** LSQと出力コレクタ */
    loadStoreQueue(tid).io.outputCollector := outputCollector.io.outputs(tid)

    /** レジスタファイルとリオーダバッファ */
    registerFile(tid).io.reorderBuffer <> reorderBuffer(tid).io.registerFile

    /** フェッチとLSQの接続 */
    fetch(tid).io.loadStoreQueueEmpty := loadStoreQueue(tid).io.isEmpty

    /** フェッチとリオーダバッファの接続 */
    fetch(tid).io.reorderBufferEmpty := reorderBuffer(tid).io.isEmpty

    /** データメモリバッファとLSQ */
    dataMemoryBuffer.io.dataIn(tid) <> loadStoreQueue(tid).io.memory

    /** リオーダバッファと出力コレクタ */
    reorderBuffer(tid).io.collectedOutputs := outputCollector.io.outputs(tid)

    /** リオーダバッファとLSQ */
    reorderBuffer(tid).io.loadStoreQueue <> loadStoreQueue(tid).io.reorderBuffer

    /** 命令メモリと命令キャッシュを接続 */
    externalMemoryInterface.io.instructionFetchRequest(tid) <>
      instructionCache(tid).io.memory.request
    externalMemoryInterface.io.instructionOut(tid) <>
      instructionCache(tid).io.memory.response

    /** フェッチと分岐予測 TODO */
    fetch(tid).io.prediction <> DontCare
  }

  /** メモリとデータメモリバッファ */
  externalMemoryInterface.io.dataReadRequests <> dataMemoryBuffer.io.dataReadRequest
  externalMemoryInterface.io.dataWriteRequests <> dataMemoryBuffer.io.dataWriteRequest
}

class B4ProcessorFixedPorts(implicit params: Parameters) extends RawModule {
  override val desiredName = "B4Processor"
  val AXI_MM = IO(new VerilogAXI(64, 64))
  val axi = AXI_MM.viewAs[ChiselAXI]

  val aclk = IO(Input(Bool()))
  val aresetn = IO(Input(Bool()))

  withClockAndReset(aclk.asClock, (!aresetn).asAsyncReset) {
    val processor = Module(new B4Processor())
    processor.axi <> axi
  }
}

object B4Processor extends App {
  implicit val params = Parameters(
    threads = 2,
    executors = 2,
    decoderPerThread = 2,
    maxRegisterFileCommitCount = 1,
    tagWidth = 4,
    parallelOutput = 1,
    instructionStart = 0x2000_0000L
  )

  ChiselStage.emitSystemVerilogFile(
    new B4ProcessorFixedPorts(),
    Array.empty,
    Array(
//      "--disable-mem-randomization",
//      "--disable-reg-randomization",
      "--disable-all-randomization",
      "--add-vivado-ram-address-conflict-synthesis-bug-workaround"
    )
  )
}
