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

/** $OpDocRNNCellBasicLSTMCell
  *
  * @param  numUnits          Number of units in the LSTM cell.
  * @param  forgetBias        Forget bias added to the forget gate.
  * @param  activation        Activation function used by this GRU cell.
  * @param  kernelInitializer Variable initializer for kernel matrices.
  * @param  biasInitializer   Variable initializer for the bias vectors.
  * @param  name              Desired name for this layer (note that this name will be made unique by potentially
  *                           appending a number to it, if it has been used before for another layer).
  *
  * @author Emmanouil Antonios Platanios
  */
class BasicLSTMCell private[cell] (
    val numUnits: Int,
    val forgetBias: Float = 1.0f,
    val activation: Output => Output = ops.Math.tanh(_),
    val kernelInitializer: Initializer = null,
    val biasInitializer: Initializer = ZerosInitializer,
    override protected val name: String = "BasicLSTMCell"
) extends RNNCell.LSTMCell(name) {
  override val layerType: String = "BasicLSTMCell"

  override def createCell(input: Output, mode: Mode): RNNCell.LSTMCellInstance = {
    val kernel = variable(
      KERNEL_NAME, input.dataType, Shape(input.shape(-1) + numUnits, 4 * numUnits), kernelInitializer)
    val bias = variable(BIAS_NAME, input.dataType, Shape(4 * numUnits), biasInitializer)
    val cell = ops.rnn.cell.BasicLSTMCell(kernel, bias, activation, forgetBias, name)
    RNNCell.LSTMCellInstance(cell, Set(kernel, bias))
  }
}

object BasicLSTMCell {
  def apply(
      numUnits: Int,
      forgetBias: Float = 1.0f,
      activation: Output => Output = ops.Math.tanh(_),
      kernelInitializer: Initializer = null,
      biasInitializer: Initializer = ZerosInitializer,
      name: String = "BasicLSTMCell"): BasicLSTMCell = {
    new BasicLSTMCell(numUnits, forgetBias, activation, kernelInitializer, biasInitializer, name)
  }
}
