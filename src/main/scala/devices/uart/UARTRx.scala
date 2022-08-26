// See LICENSE for license details.
package sifive.blocks.devices.uart

import Chisel.{defaultCompileOptions => _, _}
import freechips.rocketchip.util.CompileOptions.NotStrictInferReset

import freechips.rocketchip.util._


/**
  * prescalaer: 3->2->1->0 => pulse
  * for each pulse, sample_count(init = 14) - 1, when sample_count == 0, data_count(init = databit) - 1
  *   clk : T
  *   sample : div/16 T
  *   data : div/16 x 16 T
  *
  *
  */
class UARTRx(c: UARTParams) extends Module {
  val io = new Bundle {
    val en = Bool(INPUT)
    val in = Bits(INPUT, 1)
    val out = Valid(Bits(width = c.dataBits))
    val div = UInt(INPUT, c.divisorBits)
    /** parity enable */
    val enparity = c.includeParity.option(Bool(INPUT))
    /** parity select
      *
      * 0 -> even parity
      * 1 -> odd parity
      */
    val parity = c.includeParity.option(Bool(INPUT))
    val errorparity = c.includeParity.option(Bool(OUTPUT))
    val data8or9 = (c.dataBits == 9).option(Bool(INPUT))
  }

  if (c.includeParity)
    io.errorparity.get := false.B

  val debounce = Reg(init = UInt(0, 2))
  val debounce_max = (debounce === UInt(3))
  val debounce_min = (debounce === UInt(0))

  val prescaler = Reg(UInt(width = c.divisorBits - c.oversample + 1))
  val start = Wire(init = Bool(false))
  /** when true, start reciving a bit*/
  val pulse = (prescaler === UInt(0))

  private val dataCountBits = log2Floor(c.dataBits+c.includeParity.toInt) + 1
  /** init = data bits(8,9) + parity bit(0,1) + start bit(1) */
  val data_count = Reg(UInt(width = dataCountBits))
  val data_last = (data_count === UInt(0))
  val parity_bit = (data_count === UInt(1)) && io.enparity.getOrElse(false.B)
  // init sample_count = 15
  val sample_count = Reg(UInt(width = c.oversample))
  /** sample_mid == 7.U */
  val sample_mid = (sample_count === UInt((c.oversampleFactor - c.nSamples + 1) >> 1))
  // todo unused
  val sample_last = (sample_count === UInt(0))
  /** countdown
    *
    * {{{
    * |    data_count    |   sample_count  |
    * }}}
    * */
  val countdown = Cat(data_count, sample_count) - UInt(1)

  // Compensate for the divisor not being a multiple of the oversampling period.
  // Let remainder k = (io.div % c.oversampleFactor).
  // For the last k samples, extend the sampling delay by 1 cycle.
  val remainder = io.div(c.oversample-1, 0)
  val extend = (sample_count < remainder) // Pad head: (sample_count > ~remainder)
  /** returns true if
    * {{{
    * transmisson starts or
    * transmisson proceeds}}}
    *
    * when true, init prescaler = io.div >> c.oversample
    *
    * */
  val restore = start || pulse
  val prescaler_in = Mux(restore, io.div >> c.oversample, prescaler)
  val prescaler_next = prescaler_in - Mux(restore && extend, UInt(0), UInt(1))
  // todo only take in sample when sample_count==4,5,6
  val sample = Reg(Bits(width = c.nSamples))
  // take the majority bit of sample
  val voter = Majority(sample.asBools.toSet)
  //shifter output
  val shifter = Reg(Bits(width = c.dataBits))

  val valid = Reg(init = Bool(false))
  valid := Bool(false)
  io.out.valid := valid
  io.out.bits := (if (c.dataBits == 8) shifter else Mux(io.data8or9.get, Cat(0.U, shifter(8,1)), shifter))

  val (s_idle :: s_data :: Nil) = Enum(UInt(), 2)
  val state = Reg(init = s_idle)

  switch (state) {
    is (s_idle) {
      when (!(!io.in) && !debounce_min) {
        debounce := debounce - UInt(1)
      }
      when (!io.in) {
        debounce := debounce + UInt(1)
        when (debounce_max) {
          state := s_data
          start := Bool(true)
          prescaler := prescaler_next
          // init data_count
          data_count := UInt(c.dataBits+1) + (if (c.includeParity) io.enparity.get else 0.U) - io.data8or9.getOrElse(false.B).asUInt
          // init sample_count = 15
          sample_count := UInt(c.oversampleFactor - 1)
        }
      }
    }

    is (s_data) {
      prescaler := prescaler_next
      when (pulse) {
        // sample scan in
        sample := Cat(sample, io.in)
        data_count := countdown >> c.oversample
        sample_count := countdown(c.oversample-1, 0)

        when (sample_mid) {
          if (c.includeParity) {
            // act according to frame bit stage at its respective sampling point
            // check parity bit for error
            when (parity_bit) {
              io.errorparity.get := (shifter.asBools.reduce(_ ^ _) ^ voter ^ io.parity.get)
            }
            when (data_last) {
              state := s_idle
              valid := Bool(true)
            } .elsewhen (!parity_bit) {
              // do not add parity bit to final rx data
              shifter := Cat(voter, shifter >> 1)
            }
          } else {
            when (data_last) {
              state := s_idle
              valid := Bool(true)
            } .otherwise {
              shifter := Cat(voter, shifter >> 1)
            }
          }
        }
      }
    }
  }

  when (!io.en) {
    debounce := UInt(0)
  }
}
