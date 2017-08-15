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

package org.platanios.tensorflow.api.ops

import org.platanios.tensorflow.api.core.{Graph, Shape}
import org.platanios.tensorflow.api.ops.variables.Saver.{V2, WriterVersion}
import org.platanios.tensorflow.api.tensors.Tensor
import org.platanios.tensorflow.api.types.{DataType, FLOAT32}

/**
  * @author Emmanouil Antonios Platanios
  */
package object variables {
  private[api] trait API {
    type Variable = variables.Variable
    type PartitionedVariable = variables.PartitionedVariable
    type VariableReuse = variables.Reuse
    type VariableReuseAllowed = variables.ReuseAllowed
    type VariableGetter = variables.Variable.VariableGetter
    type VariableInitializer = variables.Initializer
    type VariableRegularizer = variables.Regularizer
    type VariablePartitioner = variables.Partitioner
    type VariableStore = variables.VariableStore
    type VariableScope = variables.VariableScope

    val Variable                 : variables.Variable.type          = variables.Variable
    val ReuseExistingVariableOnly: variables.ReuseExistingOnly.type = variables.ReuseExistingOnly
    val CreateNewVariableOnly    : variables.CreateNewOnly.type     = variables.CreateNewOnly
    val ReuseOrCreateNewVariable : variables.ReuseExistingOnly.type = variables.ReuseExistingOnly
    val VariableStore            : variables.VariableStore.type     = variables.VariableStore
    val VariableScope            : variables.VariableScope.type     = variables.VariableScope

    val zerosInitializer: Initializer = variables.ZerosInitializer
    val onesInitializer : Initializer = variables.OnesInitializer

    def constantInitializer(value: Tensor): Initializer = variables.ConstantInitializer(value)
    def constantInitializer(value: Output): Initializer = variables.DynamicConstantInitializer(value)

    def randomUniformInitializer(
        minValue: Tensor = 0.0, maxValue: Tensor = 1.0, seed: Option[Int] = None): Initializer = {
      variables.RandomUniformInitializer(minValue = minValue, maxValue = maxValue, seed = seed)
    }

    def randomNormalInitializer(
        mean: Tensor = 0.0, standardDeviation: Tensor = 1.0, seed: Option[Int] = None): Initializer = {
      variables.RandomNormalInitializer(mean = mean, standardDeviation = standardDeviation, seed = seed)
    }

    type Saver = variables.Saver
    val Saver: variables.Saver.type = variables.Saver

    def saver(
        saveables: Set[Saveable] = null, reshape: Boolean = false, sharded: Boolean = false, maxToKeep: Int = 5,
        keepCheckpointEveryNHours: Float = 10000.0f, restoreSequentially: Boolean = false, filename: String = "model",
        builder: SaverDefBuilder = DefaultSaverDefBuilder, allowEmpty: Boolean = false,
        writerVersion: WriterVersion = V2, saveRelativePaths: Boolean = false, padGlobalStep: Boolean = false,
        name: String = "Saver"): Saver = {
      Saver(
        saveables, reshape, sharded, maxToKeep, keepCheckpointEveryNHours, restoreSequentially, filename, builder,
        allowEmpty, writerVersion, saveRelativePaths, padGlobalStep, name)
    }

    def variable(
        name: String, dataType: DataType = FLOAT32, shape: Shape = null, initializer: VariableInitializer = null,
        regularizer: VariableRegularizer = null, trainable: Boolean = true, reuse: Reuse = ReuseOrCreateNew,
        collections: Set[Graph.Key[Variable]] = Set.empty,
        cachingDevice: OpSpecification => String = null): Variable = {
      Variable.getVariable(
        name, dataType, shape, initializer, regularizer, trainable, reuse, collections, cachingDevice)
    }

    def partitionedVariable(
        name: String, dataType: DataType = FLOAT32, shape: Shape = null, initializer: VariableInitializer = null,
        regularizer: VariableRegularizer = null, partitioner: VariablePartitioner, trainable: Boolean = true,
        reuse: Reuse = ReuseOrCreateNew, collections: Set[Graph.Key[Variable]] = Set.empty,
        cachingDevice: OpSpecification => String = null): PartitionedVariable = {
      Variable.getPartitionedVariable(
        name, dataType, shape, initializer, regularizer, partitioner, trainable, reuse, collections, cachingDevice)
    }

    def localVariable(
        name: String, dataType: DataType = FLOAT32, shape: Shape = null, initializer: VariableInitializer = null,
        regularizer: VariableRegularizer = null, reuse: Reuse = ReuseOrCreateNew,
        collections: Set[Graph.Key[Variable]] = Set.empty,
        cachingDevice: OpSpecification => String = null): Variable = {
      Variable.getLocalVariable(name, dataType, shape, initializer, regularizer, reuse, collections, cachingDevice)
    }

    def localPartitionedVariable(
        name: String, dataType: DataType = FLOAT32, shape: Shape = null, initializer: VariableInitializer = null,
        regularizer: VariableRegularizer = null, partitioner: VariablePartitioner, reuse: Reuse = ReuseOrCreateNew,
        collections: Set[Graph.Key[Variable]] = Set.empty,
        cachingDevice: OpSpecification => String = null): PartitionedVariable = {
      Variable.getLocalPartitionedVariable(
        name, dataType, shape, initializer, regularizer, partitioner, reuse, collections, cachingDevice)
    }
  }

  private[api] object API extends API
}
