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

package org.platanios.tensorflow.api.ops.control_flow

import org.platanios.tensorflow.api.core.Shape
import org.platanios.tensorflow.api.core.exception.{InvalidDataTypeException, UnimplementedException}
import org.platanios.tensorflow.api.ops._
import org.platanios.tensorflow.api.ops.Gradients.{Registry => GradientsRegistry}
import org.platanios.tensorflow.api.tensors.Tensor
import org.platanios.tensorflow.api.types.INT32
import org.platanios.tensorflow.api.utilities.using
import org.platanios.tensorflow.jni.{TensorFlow => NativeLibrary}

import scala.reflect.ClassTag

/** Contains functions for constructing ops related to control flow.
  *
  * @author Emmanouil Antonios Platanios
  */
private[api] trait ControlFlow {
  /** Creates an op that produces the content of `input` only after all ops in `dependencies` have finished executing.
    *
    * In some cases, a user may want the output of an op to be consumed externally only after some other dependencies
    * have run first. This function ensures returns `input`, but only after all ops in `dependencies` have run. Note
    * that this means that there is no guarantee that `input` will be evaluated after any `dependencies` have run.
    *
    * @group ControlFlowOps
    * @param  dependencies Set of ops to be executed before `input`.
    * @param  input        Op output to be computed after all ops in `dependencies` have finished executing.
    * @param  name         Name for the created op (used mainly as a name scope).
    * @return Created op output.
    */
  private[api] def withControlDependencies[T <: OutputLike](
      dependencies: Set[Op], input: T, name: String = "WithControlDependencies"): T = {
    Op.createWithNameScope(name, dependencies + input.op) {
      Op.colocateWith(Set[Op](input.op)) {
        Op.createWith(controlDependencies = dependencies) {
          Basic.identity(input)
        }
      }
    }
  }

  /** $OpDocControlFlowGroup
    *
    * @group ControlFlowOps
    * @param  inputs Ops to group.
    * @param  name   Name for the created op (used mainly as a name scope).
    * @return Created op output, which in this case is the result of a `noOp`.
    */
  def group(inputs: Set[Op], name: String = "Group"): Op = Op.createWith(Op.getGraphFromInputs(inputs)) {
    val inputsByDevice = inputs.groupBy(_.device)
    if (inputsByDevice.size == 1) {
      // 1-level tree. The root node is the returned no-op node.
      val (device, ops) = inputsByDevice.head
      if (device != null && device != "")
        Op.createWith(device = device, controlDependencies = ops)(noOp(name))
      else
        Op.createWith(controlDependencies = ops)(noOp(name))
    } else {
      // 2-level tree. The root node is the returned no-op node. `dependencies` contains 1 NoOp node for each device.
      val dependencies = inputsByDevice.toSeq.sortBy(_._1).map {
        case (device, ops) =>
          if (device != null && device != "")
            Op.createWith(device = device, controlDependencies = ops)(noOp(name))
          else
            Op.createWith(controlDependencies = ops)(noOp(name))
      }
      Op.createWith(controlDependencies = dependencies.toSet)(noOp(name))
    }
  }

  /** $OpDocControlFlowTuple
    *
    * @group ControlFlowOps
    * @param  inputs        Op outputs being grouped.
    * @param  controlInputs Set of additional ops that have to finish before this op finishes, but whose outputs are not
    *                       returned.
    * @param  name          Name for the created ops (used mainly as a name scope).
    * @return Created op outputs, which in this case are the values of `inputs`.
    */
  def tuple[T <: OutputLike](
      inputs: Array[T], controlInputs: Set[Op] = Set.empty, name: String = "Tuple")
      (implicit tag: ClassTag[T]): Array[T] = {
    val gatingOps = inputs.map(_.op).toSet
    if (gatingOps.isEmpty) {
      inputs
    } else {
      Op.createWithNameScope(name, gatingOps) {
        val gate = group(gatingOps ++ controlInputs)
        inputs.map(withControlDependencies(Set[Op](gate), _))
      }
    }
  }

  /** $OpDocControlFlowNoOp
    *
    * @group ControlFlowOps
    * @param  name Name for the created op.
    * @return Created op output.
    */
  def noOp(name: String = "NoOp"): Op = {
    Op.Builder(opType = "NoOp", name = name).build()
  }

  /** Creates an op that raises an exception to abort the process when called.
    *
    * @group ControlFlowOps
    * @param  errorMessage     Error message associated with the exception.
    * @param  exitWithoutError If `true`, the process will exit normally. Otherwise, it will exit with a `SIGABORT`
    *                          signal.
    * @param  name             Name for the created op.
    * @return Created op output.
    */
  private[api] def abort(
      errorMessage: String = "", exitWithoutError: Boolean = false, name: String = "Abort"): Output = {
    Op.Builder(opType = "Abort", name = name)
        .setAttribute("error_message", errorMessage)
        .setAttribute("exit_without_error", exitWithoutError)
        .build().outputs(0)
  }

  /** $OpDocControlFlowCond
    *
    * @group ControlFlowOps
    * @param  predicate `BOOLEAN` scalar determining whether to return the result of `trueFn` or `falseFn`.
    * @param  trueFn    Function returning the computation to be performed if `predicate` is `true`.
    * @param  falseFn   Function returning the computation to be performed if `predicate` is `false`.
    * @param  name      Name prefix for the created ops.
    * @return Created op output structure, mirroring the return structure of `trueFn` and `falseFn`.
    * @throws InvalidDataTypeException If the data types of the tensors returned by `trueFn` and `falseFn` do not match.
    */
  @throws[InvalidDataTypeException]
  def cond[T, R](predicate: Output, trueFn: () => T, falseFn: () => T, name: String = "Cond")(implicit
      ev: CondOutput.Aux[T, R]
  ): T = {
    Op.createWithNameScope(name) {
      // Add the switch to the graph.
      val (pFalse, pTrue) = ControlFlow.switch(predicate, predicate)
      val pivotTrue = Basic.identity(pTrue, "SwitchTrue")
      val pivotFalse = Basic.identity(pFalse, "SwitchFalse")
      val predicateId = Basic.identity(predicate, "PredicateIdentity")
      // Disable the fetching of tensors that are only on one branch of the cond.
      pTrue.op.graph.preventFetching(pTrue.op)
      pFalse.op.graph.preventFetching(pFalse.op)
      pivotTrue.op.graph.preventFetching(pivotTrue.op)
      pivotFalse.op.graph.preventFetching(pivotFalse.op)
      predicateId.op.graph.preventFetching(predicateId.op)

      // Build the graph for the true branch in a new context.
      val contextTrue = CondContext(predicateId, pivotTrue, TrueBranch)
      contextTrue.enter()
      val (originalResultTrue, resultTrue) = contextTrue.buildCondBranch(trueFn)
      contextTrue.exitResult(resultTrue)
      contextTrue.exit()

      // Build the graph for the false branch in a new context.
      val contextFalse = CondContext(predicateId, pivotFalse, FalseBranch)
      contextFalse.enter()
      val (_, resultFalse) = contextFalse.buildCondBranch(falseFn)
      contextFalse.exitResult(resultFalse)
      contextFalse.exit()

      // Check that the return values of the two branches have matching data types.
      resultTrue.zip(resultFalse).foreach(pair => {
        if (pair._1.dataType != pair._2.dataType)
          throw InvalidDataTypeException(
            s"The outputs of `trueFn` (dataType = ${pair._1.dataType}) and " +
                s"`falseFn` (dataType = ${pair._2.dataType}) must have the same data type.")
      })

      // Add to collections.
      Op.currentGraph.addToCollection(contextTrue, CondContext.COND_CONTEXTS)
      Op.currentGraph.addToCollection(contextFalse, CondContext.COND_CONTEXTS)

      // Add the final merge to the graph.
      val merges = resultFalse.zip(resultTrue).map(p => ControlFlow.merge(Seq(p._1, p._2))._1)
      ev.unflatten(originalResultTrue, merges)
    }
  }

  /** $OpDocControlFlowWhileLoop
    *
    * @group ControlFlowOps
    * @param  predicateFn           Function returning the computation to be performed to determine whether to continue
    *                               looping or terminate.
    * @param  bodyFn                Function returning the computation to be performed in the loop body.
    * @param  loopVariables         Loop variables (possibly a structure over tensors).
    * @param  shapeInvariants       Shape invariants for the loop variables.
    * @param  parallelIterations    Number of iterations allowed to run in parallel.
    * @param  enableBackPropagation If `true`, back-propagation support is enabled for this while-loop context.
    * @param  swapMemory            If `true`, GPU-CPU memory swapping support is enabled for this while-loop context.
    * @param  name                  Name prefix for the created ops.
    * @return Created op output structure containing the loop variables values after the loop finishes, mirroring the
    *         return structure of `bodyFn`.
    */
  def whileLoop[T, TS](
      predicateFn: T => Output, bodyFn: T => T, loopVariables: T, shapeInvariants: Option[TS] = None,
      parallelIterations: Int = 10, enableBackPropagation: Boolean = true, swapMemory: Boolean = false,
      name: String = "WhileLoop"
  )(implicit
      ev: WhileLoopVariable.Aux[T, TS]
  ): T = {
    require(parallelIterations > 0, "'parallelIterations' must be a positive integer.")
    Op.createWithNameScope(name) {
      val loopContext = WhileLoopContext(parallelIterations, enableBackPropagation, swapMemory)
      Op.currentGraph.addToCollection(loopContext, WhileLoopContext.WHILE_LOOP_CONTEXTS)
      loopContext.buildLoop(predicateFn, bodyFn, loopVariables, shapeInvariants)
    }
  }
}

private[api] object ControlFlow extends ControlFlow {
  /** Creates an op that does nothing and serves as a control trigger for scheduling. The created op is only useful as
    * a placeholder for control edges.
    *
    * @param  name Name for the created op.
    * @return Created op output.
    */
  private[control_flow] def controlTrigger(name: String = "ControlTrigger"): Op = {
    Op.Builder(opType = "ControlTrigger", name = name).build()
  }

  /** Creates an op that forwards its input to the output.
    *
    * The op represents the loop termination condition used by the "pivot" switches of a loop.
    *
    * @param  input Boolean scalar tensor, representing the branch predicate of the switch op.
    * @param  name  Name for the created op.
    * @return Created op output, which has the same value as the input tensor.
    */
  private[control_flow] def loopCond(input: Output, name: String = "LoopCond"): Output = {
    Op.Builder(opType = "LoopCond", name = name)
        .addInput(input)
        .build().outputs(0)
  }

  /** Returns `true` if and only if the provided op is a switch op. */
  private[ops] def isSwitch(op: Op): Boolean = op.opType == "Switch" || op.opType == "RefSwitch"

  /** Returns `true` if and only if the provided op is a loop invariant. */
  private[ops] def isLoopConstantEnter(op: Op): Boolean = {
    (op.opType == "Enter" || op.opType == "RefEnter") && op.booleanAttribute("is_constant")
  }

  /** Returns `true` if and only if the provided op is a loop exit op. */
  private[ops] def isLoopExit(op: Op): Boolean = op.opType == "Exit" || op.opType == "RefExit"

  /** Returns `true` if and only if the provided op is a switch op for a while loop. */
  private[ops] def isLoopSwitch(op: Op): Boolean = {
    isSwitch(op) && op.controlFlowContext.isDefined && op.controlFlowContext.get.isInstanceOf[WhileLoopContext]
  }

  /** Returns the enter op if we can infer `value` to be a loop invariant. Otherwise, returns [[None]]. */
  private[control_flow] def getLoopConstantEnter(value: Output): Option[Op] = {
    val identityOpTypes = Set("Identity", "RefIdentity", "Switch", "RefSwitch")
    var op = value.op
    while (identityOpTypes.contains(op.opType))
      op = op.inputs(0).op
    Some(op).filter(isLoopConstantEnter)
  }

  /** Returns the control flow context for the outputs of an op. */
  private[control_flow] def getOutputContext(op: Op): Option[Context] = {
    val context = op.controlFlowContext
    if (isLoopExit(op))
      context.flatMap(_.outerContext)
    else
      context
  }

  /** Creates an op that forwards `input` to the output port determined by `predicate`, while making sure the new op is
    * colocated with `input`.
    *
    * If `predicate` is `true`, then `input` is forwarded to `outputTrue`. Otherwise, it goes to `outputFalse`.
    *
    * @param  input     Tensor to be forwarded to the appropriate output.
    * @param  predicate Scalar boolean tensor that specifies which output port will receive `input`.
    * @param  name      Name for the created op.
    * @return Tuple containing `outputFalse` and `outputTrue`, in that order.
    */
  private[control_flow] def colocatedSwitch[T <: OutputLike](
      input: T, predicate: Output, name: String = "Switch"): (T, T) = {
    // The device colocation below addresses the following scenario:
    //
    // Assume you execute Optimizer.applyGradients() in a branch of a cond() and:
    //   1. The update op is created inside a `Op.colocateWith(Set(var.op)) { }` block.
    //   2. Some tensor `data` is captured and a switch is created in a `Op.colocateWith(Set(data.op)) { }` block.
    //
    // Op.colocateWith(Set(var.op)) {
    //   Op.colocateWith(Set(data.op)) {
    //     op = ...
    //   }
    // }
    //
    // `var` and `data` may be pinned to different devices and so we want the ops created within the
    // `Op.colocateWith(Set(data.op)) { }` block to ignore the existing stack.
    Op.colocateWith(Set(input.op), ignoreExisting = true)(switch(input, predicate, name))
  }

  /** Returns an `assert` op that checks that the provided predicates are exclusive (i.e., not more than one of them can
    * be `true` at the same time). */
  private[ControlFlow] def assertExclusive(predicates: Seq[Output]): Op = {
    val stacked = Basic.stack(predicates, name = "StackedPredicates")
    val numTrue = Math.sum(Math.cast(stacked, INT32), name = "NumTruePredicates")
    val atMostOneTrue = Math.less(numTrue, Basic.constant(2, INT32, name = "TwoTruePredicates"))
    val errorData =
      Seq(
        Basic.constant(Tensor(
          "More than one condition evaluated as 'true' but 'exclusive = true'. " +
              s"Conditions: (${predicates.map(_.name).mkString(", ")}), Values: ")),
        stacked)
    Checks.assert(atMostOneTrue, errorData, summarize = predicates.size)
  }

  //region Low Level Ops

  /** Creates an op that makes its input available to the next iteration.
    *
    * @param  input Tensor to make available to the next iteration.
    * @param  name  Name for the created op.
    * @return Created op output, which is the same as `input`.
    */
  private[control_flow] def nextIteration[T <: OutputLike](input: T, name: String = "NextIteration"): T = {
    val result = {
      input match {
        case i: Output =>
          Op.Builder("NextIteration", name)
              .addInput(i)
              .build().outputs(0)
        case i: OutputIndexedSlices => Op.createWithNameScope(name) {
          val values = nextIteration(i.values, "Values")
          val indices = nextIteration(i.indices, "Indices")
          val denseShape = {
            if (i.denseShape != null)
              nextIteration(i.denseShape, "DenseShape")
            else
              null
          }
          OutputIndexedSlices(indices = indices, values = values, denseShape = denseShape)
        }
        case i: SparseOutput => Op.createWithNameScope(name) {
          val values = nextIteration(i.values, "Values")
          val indices = nextIteration(i.indices, "Indices")
          val denseShape = nextIteration(i.denseShape, "DenseShape")
          SparseOutput(indices = indices, values = values, denseShape = denseShape)
        }
      }
    }
    result.asInstanceOf[T]
  }

  /** Creates an op that creates or finds a child frame, and makes `input` available to that child frame.
    *
    * The op is used together with `exit` to create loops in the graph. The unique `frameName` is used by the `Executor`
    * to identify frames. If `isConstant` is `true`, then the output is a constant in the child frame. Otherwise, it may
    * be changed in the child frame. At most `parallelIterations` iterations are run in parallel in the child frame.
    *
    * @param  input              Tensor to be made available to the child frame.
    * @param  frameName          Name of the child frame.
    * @param  isConstant         If `true`, the output is constant within the child frame.
    * @param  parallelIterations Number of iterations allowed to run in parallel.
    * @param  useInputShape      If `true`, the output tensor's shape is manually set to the input tensor's shape.
    * @param  name               Name for the created op.
    * @return Created op output, which is the same as `input`.
    */
  private[control_flow] def enter[T <: OutputLike](
      input: T, frameName: String, isConstant: Boolean = false, parallelIterations: Int = 10,
      useInputShape: Boolean = true, name: String = "Enter"): T = {
    val result = {
      input match {
        case i: Output =>
          val result = Op.Builder("Enter", name)
              .addInput(i)
              .setAttribute("frame_name", frameName)
              .setAttribute("is_constant", isConstant)
              .setAttribute("parallel_iterations", parallelIterations)
              .build().outputs(0)
          if (useInputShape)
            result.setShape(i.shape)
          result
        case i: OutputIndexedSlices => Op.createWithNameScope(name) {
          val values = enter(i.values, frameName, isConstant, parallelIterations, useInputShape, "Values")
          val indices = enter(i.indices, frameName, isConstant, parallelIterations, useInputShape, "Indices")
          val denseShape = {
            if (i.denseShape != null)
              enter(i.denseShape, frameName, isConstant, parallelIterations, useInputShape, "DenseShape")
            else
              null
          }
          OutputIndexedSlices(indices = indices, values = values, denseShape = denseShape)
        }
        case i: SparseOutput => Op.createWithNameScope(name) {
          val values = enter(i.values, frameName, isConstant, parallelIterations, useInputShape, "Values")
          val indices = enter(i.indices, frameName, isConstant, parallelIterations, useInputShape, "Indices")
          val denseShape = enter(
            i.denseShape, frameName, isConstant, parallelIterations, useInputShape, "DenseShape")
          SparseOutput(indices = indices, values = values, denseShape = denseShape)
        }
      }
    }
    result.asInstanceOf[T]
  }

  /** Creates an op that exits from the current frame to its parent frame.
    *
    * The op makes `input` available to the parent frame.
    *
    * @param  input Tensor to be made available to the parent frame.
    * @param  name  Name for the created op.
    * @return Created op output, which is the same as `input`.
    */
  private[control_flow] def exit[T <: OutputLike](input: T, name: String = "Exit"): T = {
    val result = {
      input match {
        case i: Output =>
          Op.Builder("Exit", name)
              .addInput(i)
              .build().outputs(0)
        case i: OutputIndexedSlices => Op.createWithNameScope(name) {
          val values = exit(i.values, "Values")
          val indices = exit(i.indices, "Indices")
          val denseShape = {
            if (i.denseShape != null)
              exit(i.denseShape, "DenseShape")
            else
              null
          }
          OutputIndexedSlices(indices = indices, values = values, denseShape = denseShape)
        }
        case i: SparseOutput => Op.createWithNameScope(name) {
          val values = exit(i.values, "Values")
          val indices = exit(i.indices, "Indices")
          val denseShape = {
            if (i.denseShape != null)
              exit(i.denseShape, "DenseShape")
            else
              null
          }
          SparseOutput(indices = indices, values = values, denseShape = denseShape)
        }
      }
    }
    result.asInstanceOf[T]
  }

  /** Creates an op that forwards `input` to the output port determined by `predicate`.
    *
    * If `predicate` is `true`, then `input` is forwarded to `outputTrue`. Otherwise, it goes to `outputFalse`.
    *
    * @param  input     Tensor to be forwarded to the appropriate output.
    * @param  predicate Scalar boolean tensor that specifies which output port will receive `input`.
    * @param  name      Name for the created op.
    * @return Tuple containing `outputFalse` and `outputTrue`, in that order.
    */
  private[control_flow] def switch[T <: OutputLike](input: T, predicate: Output, name: String = "Switch"): (T, T) = {
    val result = {
      input match {
        case i: Output =>
          val outputs = Op.Builder("Switch", name)
              .addInput(i)
              .addInput(predicate)
              .build().outputs
          (outputs(0), outputs(1))
        case i: OutputIndexedSlices => Op.createWithNameScope(name) {
          val (valuesFalse, valuesTrue) = switch(i.values, predicate, "Values")
          val (indicesFalse, indicesTrue) = switch(i.indices, predicate, "Indices")
          val (denseShapeFalse, denseShapeTrue) = {
            if (i.denseShape != null)
              switch(i.denseShape, predicate, "DenseShape")
            else
              (null, null)
          }
          (OutputIndexedSlices(indices = indicesFalse, values = valuesFalse, denseShape = denseShapeFalse),
              OutputIndexedSlices(indices = indicesTrue, values = valuesTrue, denseShape = denseShapeTrue))
        }
        case i: SparseOutput => Op.createWithNameScope(name) {
          val (valuesFalse, valuesTrue) = switch(i.values, predicate, "ValuesSwitch")
          val (indicesFalse, indicesTrue) = switch(i.indices, predicate, "IndicesSwitch")
          val (denseShapeFalse, denseShapeTrue) = {
            if (i.denseShape != null)
              switch(i.denseShape, predicate, "DenseShape")
            else
              (null, null)
          }
          (SparseOutput(indices = indicesFalse, values = valuesFalse, denseShape = denseShapeFalse),
              SparseOutput(indices = indicesTrue, values = valuesTrue, denseShape = denseShapeTrue))
        }
      }
    }
    result.asInstanceOf[(T, T)]
  }

  /** Creates an op that forwards the value of an available tensor from `inputs` to `output`.
    *
    * The op tests each of the tensors in `inputs` in turn to determine if any of them is available. If it finds an
    * available tensor, it returns it and its index, `outputIndex`, in `inputs`.
    *
    * No more than one tensor in `inputs` should be available. If no tensor in `inputs` is available, the returned
    * tensor and index are not set.
    *
    * This op is usually combined with `switch` to implement branching.
    *
    * IMPORTANT NOTE: The input tensors can either all be of type [[Output]] or [[SparseOutput]] or of mixed types that
    * extend [[OutputLike]]. If they are all of type [[Output]] or [[SparseOutput]], then that is also the return op
    * type. Otherwise, they will all be converted to [[OutputIndexedSlices]] first.
    *
    * @param  inputs Input tensors.
    * @param  name   Name for the created op.
    * @return Tuple containing `output` and `outputIndex`, in that order.
    */
  @throws[IllegalArgumentException]
  private[control_flow] def merge[T <: OutputLike](inputs: Seq[T], name: String = "Merge"): (T, Output) = {
    val result = {
      inputs match {
        case i if i.forall(_.isInstanceOf[Output]) =>
          val outputs = Op.Builder("Merge", name)
              .addInputList(i.map(_.asInstanceOf[Output]))
              .build().outputs
          (outputs(0), outputs(1))
        case i if i.forall(_.isInstanceOf[SparseOutput]) => Op.createWithNameScope(name) {
          val ii = i.map(_.asInstanceOf[SparseOutput])
          val (indices, chosenIndex) = merge(ii.map(_.indices), "Indices")
          val (values, _) = merge(ii.map(_.values), "Values")
          val (denseShape, _) = if (ii.map(_.denseShape).exists(_ != null)) {
            if (ii.map(_.denseShape).contains(null))
              throw new IllegalArgumentException(
                "Either all merged 'SparseOutput's must have a known dense shape, or none of them.")
            merge(ii.map(_.denseShape), "DenseShape")
          } else {
            null
          }
          (SparseOutput(indices = indices, values = values, denseShape = denseShape), chosenIndex)
        }
        case i => Op.createWithNameScope(name) {
          val ii = i.map(_.toOutputIndexedSlices(optimize = false))
          val (indices, chosenIndex) = merge(ii.map(_.indices), "Indices")
          val (values, _) = merge(ii.map(_.values), "Values")
          val (denseShape, _) = if (ii.map(_.denseShape).exists(_ != null)) {
            if (ii.map(_.denseShape).contains(null))
              throw new IllegalArgumentException(
                "Either all merged 'OutputIndexedSlices' must have a known dense shape, or none of them.")
            merge(ii.map(_.denseShape), "DenseShape")
          } else {
            null
          }
          (OutputIndexedSlices(indices = indices, values = values, denseShape = denseShape), chosenIndex)
        }
      }
    }
    result.asInstanceOf[(T, Output)]
  }

  //endregion Low Level Ops

  //region Native Library Functions

  /** Replaces the `index`th input of `op` with `newInput`. */
  private[control_flow] def updateInput(op: Op, index: Int, newInput: Output): Unit = {
    using(op.graph.reference)(r => {
      NativeLibrary.updateInput(r.nativeHandle, op.nativeHandle, index, newInput.op.nativeHandle, newInput.index)
    })
  }

  /** Adds `inputOp` as a control input of `op`. */
  private[control_flow] def addControlInput(op: Op, inputOp: Op): Unit = {
    using(op.graph.reference)(r => {
      NativeLibrary.addControlInput(r.nativeHandle, op.nativeHandle, inputOp.nativeHandle)
    })
  }

  /** Clears the control inputs of `op` (i.e., removes all of them). */
  private[control_flow] def clearControlInputs(op: Op): Unit = {
    using(op.graph.reference)(r => {
      NativeLibrary.clearControlInputs(r.nativeHandle, op.nativeHandle)
    })
  }

  //endregion Native Library Functions

  //region Gradients

  private[ops] object Gradients {
    GradientsRegistry.registerNonDifferentiable("ControlTrigger")

    GradientsRegistry.register("LoopCond", loopCondGradient)
    GradientsRegistry.register("NextIteration", nextIterationGradient)
    GradientsRegistry.register("RefNextIteration", nextIterationGradient)
    GradientsRegistry.register("Enter", enterGradient)
    GradientsRegistry.register("RefEnter", enterGradient)
    GradientsRegistry.register("Exit", exitGradient)
    GradientsRegistry.register("RefExit", exitGradient)
    GradientsRegistry.register("Switch", switchGradient)
    GradientsRegistry.register("RefSwitch", switchGradient)
    GradientsRegistry.register("Merge", mergeGradient)
    GradientsRegistry.register("RefMerge", mergeGradient)

    /** We stop back-propagation for the predicate of a while loop. */
    private[this] def loopCondGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      Seq(null)
    }

    /** A forward next-iteration op is translated into a back-propagation identity op. Note that the back-propagation
      * next-iteration op is added in switch op gradient. */
    private[this] def nextIterationGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      outputGradients
    }

    /** Gradients for an enter op are calculated using an exit op. For loop variables, `outputGradients` is the gradient
      * and so we just add an exit op. For loop invariants, we need to add an accumulator loop. */
    private[this] def enterGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      Op.currentControlFlowContext.map(gradientContext => {
        if (!gradientContext.backPropagate) {
          // We skip gradient computation in this case.
          outputGradients
        } else if (gradientContext.gradientLoopState.isEmpty) {
          // We pass the gradient through if we are not in a gradient while-loop context.
          outputGradients
        } else if (op.booleanAttribute("is_constant")) {
          // We add a gradient accumulator for each while-loop invariant.
          Seq(gradientContext.asInstanceOf[WhileLoopContext].addBackwardAccumulator(op, outputGradients.head))
        } else {
          val gradientWhileLoopContext = gradientContext.asInstanceOf[WhileLoopContext]
          val result = Seq(exit(outputGradients.head))
          result(0) match {
            case o: Output => gradientWhileLoopContext.loopExits += o
            case o: OutputIndexedSlices =>
              gradientWhileLoopContext.loopExits += o.indices
              gradientWhileLoopContext.loopExits += o.values
              if (o.denseShape != null)
                gradientWhileLoopContext.loopExits += o.denseShape
            case o: SparseOutput =>
              gradientWhileLoopContext.loopExits += o.indices
              gradientWhileLoopContext.loopExits += o.values
              if (o.denseShape != null)
                gradientWhileLoopContext.loopExits += o.denseShape
          }
          gradientContext.exitResult(result)
          result
        }
      }).getOrElse(outputGradients)
    }

    /** Gradients for an exit op are calculated using an enter op. */
    @throws[UnimplementedException]
    private[this] def exitGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      Op.currentControlFlowContext.map(gradientContext => {
        if (!gradientContext.backPropagate) {
          // We skip gradient computation in this case.
          Seq(null)
        } else if (op.controlFlowContext.flatMap(_.gradientLoopState).isDefined) {
          throw UnimplementedException("Second-order gradients are not supported for while loops.")
        } else {
          outputGradients.head match {
            case o: Output => gradientContext.values += o.name
            case o: OutputIndexedSlices =>
              gradientContext.values += o.indices.name
              gradientContext.values += o.values.name
              if (o.denseShape != null)
                gradientContext.values += o.denseShape.name
            case o: SparseOutput =>
              gradientContext.values += o.indices.name
              gradientContext.values += o.values.name
              if (o.denseShape != null)
                gradientContext.values += o.denseShape.name
          }
          val gradientWhileLoopContext = gradientContext.asInstanceOf[WhileLoopContext]
          gradientContext.enter()
          val result = Seq(enter(
            outputGradients.head,
            gradientContext.name,
            isConstant = false,
            parallelIterations = gradientWhileLoopContext.parallelIterations,
            name = "ExitGradient"))
          result(0) match {
            case o: Output => gradientWhileLoopContext.loopEnters += o
            case o: OutputIndexedSlices =>
              gradientWhileLoopContext.loopEnters += o.indices
              gradientWhileLoopContext.loopEnters += o.values
              if (o.denseShape != null)
                gradientWhileLoopContext.loopEnters += o.denseShape
            case o: SparseOutput =>
              gradientWhileLoopContext.loopEnters += o.indices
              gradientWhileLoopContext.loopEnters += o.values
              if (o.denseShape != null)
                gradientWhileLoopContext.loopEnters += o.denseShape
          }
          gradientContext.exit()
          result
        }
      }).getOrElse(outputGradients)
    }

    /** Gradients for a switch op are calculated using a merge op. If the switch is a loop switch, it will be visited
      * twice. We create the merge op on the first visit, and we update the second input of the merge on the second
      * visit. A next-iteration op is also added in the second visit. */
    private[this] def switchGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val gradientContext = Op.currentControlFlowContext
      op.controlFlowContext match {
        case Some(opContext: CondContext) =>
          val b = opContext.branch.value
          val goodGradient = outputGradients(b)
          var zeroGradient = outputGradients(1 - b)
          // At this point, we have created `zeroGradient` guarded by the right switch. Unfortunately, we may still get
          // `null` here for non-trainable data types or for some types of ops (e.g., `ResourceGather`) created within
          // only one branch.
          // TODO: !!! This may be inefficient. What if one branch of the switch is not differentiable?
          if (zeroGradient == null) {
            val zeros = goodGradient match {
              case o: Output => Basic.zerosLike(o)
              case o: OutputIndexedSlices =>
                OutputIndexedSlices(
                  Tensor(o.indices.dataType),
                  Tensor.allocate(o.values.dataType, Shape(0, o.values.shape(1))),
                  o.denseShape)
              case o: SparseOutput =>
                SparseOutput(
                  Tensor(o.indices.dataType),
                  Tensor(o.values.dataType, Shape(0, o.values.shape(1))),
                  o.denseShape)
            }
            zeroGradient = opContext.branch.other.selectSwitchResult(
              ControlFlow.colocatedSwitch(zeros, opContext.predicate))
          }
          Seq(merge(Seq(goodGradient, zeroGradient), name = "CondGradient")._1, null)
        case Some(_: WhileLoopContext) =>
          gradientContext.flatMap(_.gradientLoopState).flatMap(_.switchMap.get(op)) match {
            case Some(mergeGradient) =>
              // This is the second time this switch node is visited. It comes from the non-exit branch of the switch,
              // and so we update the second input to the merge node.
              if (outputGradients(1) != null)
                WhileLoopContext.addNextIterationAndBackEdge(mergeGradient, outputGradients(1))
              Seq(null, null)
            case None if outputGradients.head != null =>
              // This is the first time this switch node is visited. It comes from the exit branch of the switch, which
              // is `outputGradients(0)`. `outputGradients(1)` is empty at this point. We use `outputGradients(0)` for
              // both inputs to the merge for now, but we update the second input of the merge node when we visit this
              // switch node for a second time.
              val mergeGradient = merge(Seq(outputGradients(0), outputGradients(0)), name = "SwitchGradient")._1
              gradientContext.flatMap(_.gradientLoopState).map(_.switchMap).foreach(_ += op -> mergeGradient)
              Seq(mergeGradient, null)
            case _ =>
              // This is the first time this switch node is visited. It comes from the identity branch. Such a switch
              // has `null` gradient for the exit branch, meaning that the output is not differentiable.
              Seq(null, null)
          }
        case _ =>
          val falseGradient = switch(outputGradients(0), op.inputs(1))._1
          val trueGradient = switch(outputGradients(1), op.inputs(1))._2
          Seq(merge(Seq(falseGradient, trueGradient))._1, null)
      }
    }

    /** Gradients for a merge op are calculated using a switch op. */
    private[this] def mergeGradient(op: Op, outputGradients: Seq[OutputLike]): Seq[OutputLike] = {
      val gradientContext = Op.currentControlFlowContext
      ControlFlow.getOutputContext(op.inputs(0).op) match {
        case Some(opContext: CondContext) =>
          val predicate = gradientContext.flatMap(_.gradientLoopState).map(gradientLoopState => {
            // This merge node is part of a conditional structure within a loop. The back-propagation needs to have the
            // value of this predicate for every iteration and so, we must have its values accumulated in the forward,
            // and use the accumulated values as the predicate for this back-propagation switch.
            gradientLoopState.historyMap.getOrElse(opContext.predicate.name, {
              // We want to remember the value of the predicate for every iteration.
              gradientLoopState.backwardContext.exit()
              val historyPredicate = gradientLoopState.addForwardAccumulator(opContext.predicate)
              gradientLoopState.backwardContext.enter()
              // We now add the stack pop op. If `opContext.predicate.op` is in a (possibly outer) `CondContext`, then
              // the stack pop op will be guarded with a switch.
              val realPredicate = gradientLoopState.addBackwardAccumulatedValue(historyPredicate, opContext.predicate)
              gradientLoopState.historyMap += opContext.predicate.name -> realPredicate
              realPredicate
            })
          }).getOrElse(opContext.predicate)
          val switch = colocatedSwitch(outputGradients.head, predicate)
          Seq(switch._1, switch._2)
        case Some(_: WhileLoopContext) =>
          val switch = colocatedSwitch(outputGradients.head, gradientContext.get.asInstanceOf[WhileLoopContext].pivot)
          Seq(switch._1, switch._2)
        case _ =>
          (0 until op.numInputs).map(i => {
            colocatedSwitch(outputGradients.head, Math.equal(op.outputs(1), i))._2
          })
      }
    }
  }

  //endregion Gradients

  /** @define OpDocControlFlowGroup
    *   The `group` op groups multiple ops together.
    *
    *   When the op finishes, all ops in `inputs` have finished. The op has no output.
    *
    * @define OpDocControlFlowTuple
    *   The `tuple` op groups op outputs together.
    *
    *   The op creates a tuple of op outputs with the same values as `inputs`, except that the value of each output is
    *   only returned after the values of all outputs in `inputs` have been computed.
    *
    *   This op can be used as a "join" mechanism for parallel computations: all the argument tensors can be computed in
    *   parallel, but the values of any tensor returned by `tuple` are only available after all the parallel
    *   computations are done.
    *
    * @define OpDocControlFlowNoOp
    *   The `noOp` op does nothing. The created op is only useful as a placeholder for control edges.
    *
    * @define OpDocControlFlowCond
    *   The `cond` op returns `trueFn()` if the predicate `predicate` is true, else `falseFn()`.
    *
    *   `trueFn` and `falseFn` both return structures of tensors (e.g., lists of tensors). `trueFn` and `falseFn` must
    *   have the same non-zero number and type of outputs. Note that the conditional execution applies only to the ops
    *   defined in `trueFn` and `falseFn`.
    *
    *   For example, consider the following simple program:
    *   {{{
    *     val z = tf.multiply(a, b)
    *     val result = tf.cond(x < y, () => tf.add(x, z), () => tf.square(y))
    *   }}}
    *   If `x < y`, the `tf.add` operation will be executed and the `tf.square` operation will not be executed. Since
    *   `z` is needed for at least one branch of the `cond`, the `tf.multiply` operation is always executed,
    *   unconditionally. Although this behavior is consistent with the data-flow model of TensorFlow, it has
    *   occasionally surprised some users who expected lazier semantics.
    *
    *   Note that `cond` calls `trueFn` and `falseFn` *exactly once* (inside the call to `cond`, and not at all during
    *   `Session.run()`). `cond` stitches together the graph fragments created during the `trueFn` and `falseFn` calls
    *   with some additional graph nodes to ensure that the right branch gets executed depending on the value of
    *   `predicate`.
    *
    *   `cond` supports nested tensor structures, similar to `Session.run()`. Both `trueFn` and `falseFn` must return
    *   the same (possibly nested) value structure of sequences, tuples, and/or maps.
    *
    * @define OpDocControlFlowWhileLoop
    *   The `whileLoop` op repeats the result of `bodyFn` while the condition returned by `predicateFn` is `true`.
    *
    *   `predicateFn` is a function returning a `BOOLEAN` scalar tensor. `bodyFn` is a function returning a structure
    *   over tensors mirroring that of `loopVariables`. `loopVariables` is a structure over tensors that is passed to
    *   both `predicateFn` and `bodyFn`. `predicateFn` and `bodyFn` both take as many arguments as there are
    *   `loopVariables`.
    *
    *   In addition to regular tensors, indexed slices, or sparse tensors, the body function may accept and return
    *   tensor array objects. The flows of the tensor array objects will be appropriately forwarded between loops and
    *   during gradient calculations.
    *
    *   Note that `whileLoop()` calls `predicateFn` and `bodyFn` *exactly once* (inside the call to `whileLoop`, and not
    *   at all during `Session.run()`). `whileLoop()` stitches together the graph fragments created during the
    *   `predicateFn` and `bodyFn` calls with some additional graph nodes to create the graph flow that repeats `bodyFn`
    *   until `predicateFn` returns `false`.
    *
    *   For correctness, `whileLoop()` strictly enforces shape invariants for the loop variables. A shape invariant is a
    *   (possibly partial) shape that is unchanged across the iterations of the loop. An error will be raised if the
    *   shape of a loop variable after an iteration is determined to be more general than or incompatible with its shape
    *   invariant. For example, a shape of `[11, -1]` is more general than a shape of `[11, 17]`, and `[11, 21]` is not
    *   compatible with `[11, 17]`. By default, (if the argument `shapeInvariants` is not specified), it is assumed that
    *   the initial shape of each tensor in `loopVariables` is the same in every iteration. The `shapeInvariants`
    *   argument allows the caller to specify a less specific shape invariant for each loop variable, which is needed if
    *   the shape varies between iterations. The `Output.setShape()` function may also be used in the `bodyFn` function
    *   to indicate that the output loop variable has a particular shape. The shape invariants for indexed slices and
    *   sparse tensors are treated specially as follows:
    *
    *     a) If a loop variable is an indexed slices, the shape invariant must be a shape invariant of the values tensor
    *     of the indexed slices. This means that the shapes of the three tensors of the indexed slices are `[shape(0)]`,
    *     `shape`, and `[shape.rank]`.
    *
    *     b) If a loop variable is a sparse tensor, the shape invariant must be a shape `[r]`, where `r` is the rank of
    *     the dense tensor represented by the sparse tensor. This means that the shapes of the three tensors of the
    *     sparse tensor are `[-1, r]`, `[-1]`, and `[r]`. Note that the shape invariant here is the shape of the sparse
    *     tensor `denseShape` field. It must be the shape of a vector.
    *
    *   `whileLoop()` implements non-strict semantics, enabling multiple iterations to run in parallel. The maximum
    *   number of parallel iterations can be controlled by `parallelIterations`, which gives users some control over
    *   memory consumption and execution order. For correct programs, `whileLoop()` should return the same result for
    *   any value `parallelIterations > 0`.
    *
    *   For training, TensorFlow stores the tensors that are produced in the forward pass and are needed in
    *   back-propagation. These tensors are a main source of memory consumption and often cause out-of-memory errors
    *   when training on GPUs. When the flag `swapMemory` is set to `true`, we swap out these tensors from the GPU to
    *   the CPU. This, for example, allows us to train RNN models with very long sequences and large batch sizes.
    *
    *   For example:
    *   {{{
    *     val i = tf.constant(0)
    *     val p = (i: Output) => tf.less(i, 10)
    *     val b = (i: Output) => tf.add(i, 1)
    *     val r = tf.whileLoop(p, b, i)
    *   }}}
    *
    *   Or, using more involved tensor structures:
    *   {{{
    *     val ijk0 = (tf.constant(0), (tf.constant(1), tf.constant(2)))
    *     val p = (i: Output, (j: Output, k: Output)) => i < 10
    *     val b = (i: Output, (j: Output, k: Output)) => (i + 1, (j + k, j - k))
    *     val r = tf.whileLoop(p, b, ijk0)
    *   }}}
    *
    *   Also, using shape invariants:
    *   {{{
    *     val i0 = tf.constant(0)
    *     val m0 = tf.ones(Shape(2, 2))
    *     val p = (i: Output, m: Output) => i < 10
    *     val b = (i: Output, m: Output) => (i + 1, tf.concatenate(Seq(m, m), axis = 0))
    *     val r = tf.whileLoop(p, b, (i0, m0), (i0.shape, Shape(-1, 2)))
    *   }}}
    */
  private[ops] trait Documentation
}
