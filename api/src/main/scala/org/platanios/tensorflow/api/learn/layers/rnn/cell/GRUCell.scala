/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api.learn.layers.rnn.cell

import org.platanios.tensorflow.api.core.Shape
import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api.ops
import org.platanios.tensorflow.api.ops.Output
import org.platanios.tensorflow.api.ops.variables.{Initializer, ZerosInitializer}

/** $OpDocRNNCellGRUCell
  *
  * @param  numUnits          Number of units in the GRU cell.
  * @param  activation        Activation function used by this GRU cell.
  * @param  kernelInitializer Variable initializer for kernel matrices.
  * @param  biasInitializer   Variable initializer for the bias vectors.
  * @param  name              Desired name for this layer (note that this name will be made unique by potentially
  *                           appending a number to it, if it has been used before for another layer).
  *
  * @author Emmanouil Antonios Platanios
  */
class GRUCell private[cell] (
    val numUnits: Int,
    val activation: Output => Output = ops.Math.tanh(_),
    val kernelInitializer: Initializer = null,
    val biasInitializer: Initializer = ZerosInitializer,
    override protected val name: String = "GRUCell"
) extends RNNCell.BasicCell(name) {
  override val layerType: String = "GRUCell"

  override def createCell(input: Output, mode: Mode): RNNCell.BasicCellInstance = {
    val gateKernel = variable(
      s"Gate/$KERNEL_NAME",
      input.dataType,
      Shape(input.shape(-1) + numUnits, 2 * numUnits),
      kernelInitializer)
    val gateBias = variable(
      s"Gate/$BIAS_NAME",
      input.dataType, Shape(2 * numUnits),
      biasInitializer)
    val candidateKernel = variable(
      s"Candidate/$KERNEL_NAME",
      input.dataType,
      Shape(input.shape(-1) + numUnits, numUnits),
      kernelInitializer)
    val candidateBias = variable(
      s"Candidate/$BIAS_NAME",
      input.dataType,
      Shape(numUnits),
      biasInitializer)
    val cell = ops.rnn.cell.GRUCell(gateKernel, gateBias, candidateKernel, candidateBias, activation, name)
    RNNCell.CellInstance(cell, Set(gateKernel, gateBias, candidateKernel, candidateBias))
  }
}

object GRUCell {
  def apply(
      numUnits: Int,
      activation: Output => Output = ops.Math.tanh(_),
      kernelInitializer: Initializer = null,
      biasInitializer: Initializer = ZerosInitializer,
      name: String = "GRUCell"): GRUCell = {
    new GRUCell(numUnits, activation, kernelInitializer, biasInitializer, name)
  }
}
