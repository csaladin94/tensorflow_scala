package org.platanios.tensorflow.api.ops

import org.platanios.tensorflow.api._
import org.platanios.tensorflow.jni.{Op => NativeOp}
import org.platanios.tensorflow.api.Exception.{GraphMismatchException, IllegalNameException, OpBuilderUsedException}
import java.nio.charset.Charset

import scala.collection.mutable
import scala.util.DynamicVariable

/** Represents a graph node, or as we shall call it, an operation, that performs computation on tensors.
  *
  * An `Op` is a symbolic representation of the computation it performs. It is a node in a TensorFlow [[Graph]] that
  * takes zero or more `Op.Output` objects as input, and produces zero or more `Op.Output` objects as output. `Op`
  * objects are constructed by calling op creation functions, such as [[ArrayOps.constant]] or [[MathOps.matMul]].
  *
  * For example, `val c = MathOps.matMul(a, b)` creates an `Op` of type `"MatMul"` that takes `Op.Output`s `a` and
  * `b` as input, and produces `Op.Output` `c` as output.
  *
  * @note The `Op.Input` class is simply a wrapper around an `Op` meant to represent one of its inputs. Actual op inputs
  *       have type `Op.Output` since they represent outputs of other ops. Currently, `Op.Input` is only useful for
  *       representing consumers of an `Op`'s outputs.
  *
  * After the graph has been launched in a [[Session]], an `Op` can be executed by using [[Session.run]].
  *
  * TODO: Add `Op.run` use example, once that is supported.
  *
  * @author Emmanouil Antonios Platanios
  */
final case class Op private (graph: Graph, private[api] val nativeHandle: Long) {
  graph.opsCache.update(nativeHandle, this) // Update the ops cache of the graph with the current op

  /** Name of the op. */
  lazy val name: String = using(graph.reference) { _ => NativeOp.name(nativeHandle) }

  /** Type of the op (i.e., the name of the computation performed by the operation). */
  lazy val opType: String = using(graph.reference) { _ => NativeOp.opType(nativeHandle) }

  /** Device in which the op tensors are stored and where all computations for this op are performed. */
  lazy val device: String = using(graph.reference) { _ => NativeOp.device(nativeHandle) }

  /** Colocation ops for this op (i.e., ops guaranteed to be placed on the same device). */
  lazy val colocationOps: Set[Op] = using(graph.reference) { _ =>
    Option(NativeOp.getAttrStringList(nativeHandle, COLOCATION_OPS_ATTRIBUTE_NAME))
        .map(_.toSet[String]
                 .filter(_.startsWith(COLOCATION_OPS_ATTRIBUTE_PREFIX))
                 .map(opName => graph.findOp(opName.substring(COLOCATION_OPS_ATTRIBUTE_PREFIX.length)).get))
        .getOrElse(Set.empty[Op])
  }

  /** Number of inputs to this op (i.e., number of tensors fed as input to this op). */
  lazy val numInputs: Int = using(graph.reference) { _ => NativeOp.numInputs(nativeHandle) }

  /** Inputs of this op. Note that these inputs are outputs of other ops and thus have type [[Op.Output]]. */
  lazy val inputs: Array[Op.Output] = (0 until numInputs).map(index => using(graph.reference) { _ =>
    val jniOpOutput = NativeOp.input(nativeHandle, index)
    val op = graph.opsCache.getOrElseUpdate(
      jniOpOutput.opHandle,
      Op(graph, jniOpOutput.opHandle))
    op.outputs(jniOpOutput.outputIndex)
  }).toArray

  /** Number of control inputs to this op. These are ops that are guaranteed to finish executing before this op starts
    * executing). */
  lazy val numControlInputs: Int = using(graph.reference) { _ => NativeOp.numControlInputs(nativeHandle) }

  /** Control inputs of this op. These are ops that are guaranteed to finish executing before this op starts
    * executing). */
  lazy val controlInputs: Set[Op] = {
    val controlInputHandles = using(graph.reference) { _ => NativeOp.controlInputs(nativeHandle) }
    controlInputHandles.map(handle => graph.opsCache.getOrElseUpdate(handle, Op(graph, handle))).toSet
  }

  /** Number of tensors produced by this operation. */
  lazy val numOutputs: Int = using(graph.reference) { _ => NativeOp.numOutputs(nativeHandle) }

  /** Outputs of this op. */
  lazy val outputs: Array[Op.Output] = (0 until numOutputs).map(i => Op.Output(op = this, index = i)).toArray

  /** Gets the (current) number of control outputs of this op. These are ops that are guaranteed to start executing
    * after this op finishes executing.
    *
    * @note A concurrent modification of the graph can change the number of control outputs of this op.
    * @return Current number of control outputs of this op.
    */
  def numControlOutputs: Int = using(graph.reference) { _ => NativeOp.numControlOutputs(nativeHandle) }

  /** Gets the (current) control outputs of this op. These are ops that are guaranteed to start executing after this op
    * finishes executing.
    *
    * @note A concurrent modification of the graph can change the number of control outputs of this op.
    * @return Current control outputs of this op.
    */
  def controlOutputs: Set[Op] = {
    val controlOutputHandles = using(graph.reference) { _ => NativeOp.controlOutputs(nativeHandle) }
    controlOutputHandles.map(handle => graph.opsCache.getOrElseUpdate(handle, Op(graph, handle))).toSet
  }

  /** Gets the data type of the specified input of this op.
    *
    * @param  index Input index.
    * @return Data type of the specified input.
    */
  private def inputDataType(index: Int): DataType =
    using(graph.reference) { r =>
      DataType.fromCValue(NativeOp.inputDataType(r.nativeHandle, nativeHandle, index))
    }

  /** Gets the data type of the specified output of this op.
    *
    * @param  index Output index.
    * @return Data type of the specified output.
    */
  private def outputDataType(index: Int): DataType =
    using(graph.reference) { r =>
      DataType.fromCValue(NativeOp.outputDataType(r.nativeHandle, nativeHandle, index))
    }

  /** Gets the (current) number of consumers of the specified output of this op. These are other ops that use the
    * specified output as one of their inputs.
    *
    * @param  index Output index.
    * @return Current consumers of the specified output.
    */
  private def outputConsumers(index: Int): Array[Op.Input] = using(graph.reference) { _ =>
    NativeOp.consumers(nativeHandle, index).map(jniOpOutput => {
      val op = graph.opsCache.getOrElseUpdate(jniOpOutput.opHandle, Op(graph, jniOpOutput.opHandle))
      Op.Input(op = op, index = index)
    })
  }

  override def toString: String = name
}

private[ops] final case class OpSpecification(name: String, opType: String)
private[api] final case class OpCreationContext(
    graph: Graph = Graph(), nameScope: String = "", device: OpSpecification => String = _ => "",
    colocationOps: Set[Op] = Set.empty[Op], controlDependencies: Set[Op] = Set.empty[Op])

object Op {
  /** Convenient implicit conversion function used to convert devices specified as [[String]]s for use with the
    * [[createWith]] function, to the expected device function format taking an [[OpSpecification]] as input and
    * return a device specification string.
    *
    * @param  device Device specification string.
    * @return Function that returns `device` for any [[OpSpecification]] used as input.
    */
  implicit def deviceImplicitConversion(device: String): OpSpecification => String = _ => device

  /** Convenient implicit conversion function used to convert op outputs to their corresponding ops for use with the
    * [[createWith]] function, when specifying control dependencies.
    *
    * @param  opOutput Op output.
    * @return Op corresponding to the provided op output.
    */
  implicit def opOutputToOpImplicitConversion(opOutput: Op.Output): Op = opOutput.op

  /** Creates a context that can be used for creating ops according to the provided options.
    *
    * = General Information =
    *
    * During graph creation, a context is maintained that includes:
    *   - The current graph in which new ops are placed.
    *   - The current name scope used for naming these new ops.
    *   - A device function, used to decide in which device (e.g., CPU) the new ops should be placed and executed.
    *   - A set of colocation ops for the newly constructed ops. This means that the newly created ops will be placed on
    * the same device as these colocation ops.
    *   - A set of ops defining control dependencies for the newly constructed ops. This means that the newly
    * constructed ops are constrained to only execute after the provided set of ops has finished executing.
    *
    * Note that all arguments of this function are optional. If they are not provided, then the corresponding option in
    * current op creation context is left unchanged.
    *
    * Care must be taken if concurrency is used while creating the graph because the op creation context is wrapped
    * inside a [[scala.util.DynamicVariable]]. More information on this general issue can be found at
    * [[http://stevenskelton.ca/threadlocal-variables-scala-futures/]].
    *
    * = Argument Specifics =
    *
    * == Graph ==
    *
    * When `createWith(...)` is used with a graph, then all ops created within its code block will be placed in the
    * provided graph.
    *
    * For example:
    * {{{
    *   val g = Graph()
    *   createWith(graph = g) {
    *     val c = constant(5.0)
    *     assert(c.graph == g)
    *   }
    * }}}
    *
    * == Name Scope ==
    *
    * When `createWith(...)` is used with a name scope, the provided name scope is appended to the context name scope,
    * generating a new op creation context. This new context is used for all ops created within the code block provided
    * in the `createWith(...)` function. The `nameScope` argument will be interpreted as follows:
    *   - A string will create a new name scope, in which `nameScope` is appended to the prefix of all operations
    * created in the provided code block. If `nameScope` has been used before, it will be made unique by calling
    * `uniqueName(graph = context.graph, name = nameScope)`.
    *   - A value of `""` will reset the current name scope to the top-level (i.e., empty) name scope.
    *
    * This function checks the provided `nameScope` for validity by checking whether it matches: (i) the regular
    * expression `[A-Za-z0-9.][A-Za-z0-9_.\\-/]*` if the current context name scope is empty (i.e., at the root), or
    * (ii) the regular expression `[A-Za-z0-9_.\\-/]*`, otherwise.
    *
    * For example:
    * {{{
    *   // No name scope used
    *   val c = constant(1.0, name = "C")
    *   assert(c.op.name == "C")
    *   val c1 = constant(2.0, name = "C_1")
    *   assert(c_1.op.name == "C_1")
    *
    *   // Create a name scope called "Nested"
    *   createWith(nameScope = "Nested") {
    *     val nestedC = constant(3.0, name = "C")
    *     assert(nestedC.op.name == "Nested/C")
    *
    *     // Create a nested name scope called "Inner"
    *     createWith(nameScope = "Inner") {
    *       val nestedInnerC = constant(4.0, name = "C")
    *       assert(nestedInnerC.op.name == "Nested/Inner/C")
    *     }
    *
    *     // Create a nested name scope called "Inner_1"
    *     createWith(nameScope = "Inner_1") {
    *       val nestedInner1C = constant(5.0, name = "C")
    *       assert(nestedInner1C.op.name == "Nested/Inner_1/C")
    *
    *       // Reset the name scope using ""
    *       createWith(nameScope = "") {
    *         val c2 = constant(6.0, name = "C_2")
    *         assert(c2.op.name == "C_2")
    *       }
    *     }
    *   }
    * }}}
    *
    * == Device ==
    *
    * When `createWith(...)` is used with a device, the `device` argument needs to be a function taking an
    * [[OpSpecification]] as input and returning a string representation of the device where the corresponding op should
    * be placed. This function is invoked every time a new op is created within the provided code block. If the function
    * returns `null` for some op, then all subsequent invocations of `createWith(device = ...)` in the provided code
    * block will be ignored. Note that, if the [[deviceImplicitConversion]] implicit conversion function is within
    * scope, then a `String` value (or `null`) can be used directly for the `device` field. In this case, the value
    * provided will be used as the device for all newly create ops in the provided code block. For information about the
    * valid syntax of device name strings, see the documentation in
    * [`DeviceNameUtils`](https://www.tensorflow.org/code/tensorflow/core/util/device_name_utils.h).
    *
    * Note that the device scope may be overridden by op wrappers or other library code. For example, a variable
    * assignment op must be colocated with the corresponding variable. Incompatible device scopes will be ignored.
    *
    * For example:
    * {{{
    *   // Specifying which device to use
    *   createWith(device = "/GPU:0") {
    *     // All ops constructed in this code block will be placed in GPU 0
    *     val gpu0C = constant(7.0)
    *     assert(gpu0C.device == "/device:GPU:0")
    *
    *     // Reset the device being used
    *     createWith(device = null) {
    *       // All ops constructed in this code block will have no assigned device
    *       val c = constant(8.0)
    *       assert(c.device == "")
    *     }
    *   }
    *
    *   // Using a device function
    *   def matMulOnGPU(opSpecification: OpSpecification): String = {
    *     if (opSpecification.opType == "MatMul")
    *       "/GPU:0"
    *     else
    *       "/CPU:0"
    *   }
    *
    *   createWith(device = matMulOnGPU) {
    *     // All ops of type "MatMul" constructed in this code block will be placed on GPU 0. All other operations will
    *     // be placed on CPU 0.
    *     val c = constant(9.0)
    *     assert(c.device == "/device:CPU:0")
    *     val m = matMul(c, constant(10.0))
    *     assert(m.device == "/device:GPU:0")
    *   }
    * }}}
    *
    * == Colocation Ops ==
    *
    * When `createWith(...)` is used with a set of colocation ops, then all ops created within its code block will be
    * placed on the same device as the provided colocation ops. Note that if a set of colocation ops already exist in
    * the current op creation context (e.g., as the result of nesting multiple `createWith(colocationOps = ...)` calls),
    * then the new set of colocation ops will be the union of the two sets.
    *
    * Note that using a non-empty set of colocation ops resets any existing device constraints. In other words,
    * colocation ops override any other device placement specification.
    *
    * For example:
    * {{{
    *   val a = createWith(device = "/CPU:0")(constant(1.0))
    *   val b = createWith(device = "/GPU:0")(constant(1.0))
    *   createWith(colocationOps = Set(a)) {
    *     val c = constant(1.0)
    *     assert(c.device == a.device)
    *   }
    *   createWith(colocationOps = Set(b)) {
    *     val d = constant(1.0)
    *     assert(d.device == b.device)
    *   }
    * }}}
    *
    * == Control Dependencies ==
    *
    * When `createWith(...)` is used with a set of control dependencies, then all ops created within its code block will
    * be dependent on the control dependency ops. This means that they will be guaranteed to execute only after all of
    * the control dependencies ops have finished executing. Note that if a set of control dependencies already exist in
    * the current op creation context (e.g., as the result of nesting multiple `createWith(controlDependencies = ...)`
    * calls), then the new set of control dependencies will be the union of the two sets. Furthermore, if an empty set
    * is provided, then the control dependencies are cleared, instead of taking the union with the current control
    * dependencies.
    *
    * For example:
    * {{{
    *   val a = constant(1.0)
    *   val b = constant(1.0)
    *   createWith(controlDependencies = Set(a)) {
    *     val c = constant(1.0)
    *     assert(c.controlInputs.toSet == Set(a))
    *     createWith(controlDependencies = Set(b, c)) {
    *       val d = constant(1.0)
    *       assert(d.controlInputs.toSet == Set(a, b, c))
    *       createWith(controlDependencies = Set()) {
    *         createWith(controlDependencies = Set(d)) {
    *           val e = constant(1.0)
    *           assert(e.controlInputs.toSet == Set(d))
    *         }
    *       }
    *     }
    *   }
    *   assert(a.controlOutputs.toSet == Set(c, d))
    *   assert(b.controlOutputs.toSet == Set(d))
    *   assert(c.controlOutputs.toSet == Set())
    *   assert(d.controlOutputs.toSet == Set(e))
    *   assert(e.controlOutputs.toSet == Set())
    * }}}
    *
    * Note that transitive dependencies are eliminated (e.g., if `a` depends on `b` and `c`, and `b` depends on `c`,
    * then the dependency of `a` on `c` is ignored) in order not to add redundant control dependencies to the graph.
    *
    * == Combining Arguments ==
    *
    * Multiple arguments can be provided to change several aspects of the current op creation scope.
    *
    * For example:
    * {{{
    *   // Changing graph, name scope, and device to use for new ops.
    *   createWith(graph = g, nameScope = "Nested", device = "/GPU:0") {
    *     val c = constant(11.0, name = "C")
    *     assert(c.graph == g)
    *     assert(c.op.name == "Nested/C")
    *     assert(c.device == "/device:GPU:0")
    *   }
    * }}}
    *
    * @param  graph               Graph to use as default for new ops.
    * @param  nameScope           Name scope to use.
    * @param  device              Device function to use.
    * @param  controlDependencies Control dependencies to use.
    * @param  block               Code block to run using the provided options.
    * @param  context             Current op creation context.
    * @tparam R Return type of the code block.
    * @return Return value of the code block.
    * @throws IllegalNameException If the provided name scope does not pass the regular expression validity checks.
    */
  @throws[IllegalNameException]
  def createWith[R](
      graph: Graph = null, nameScope: String = null, device: OpSpecification => String = _ => "",
      colocationOps: Set[Op] = null, controlDependencies: Set[Op] = null)
      (block: => R)(implicit context: DynamicVariable[OpCreationContext]): R = {
    val newGraph: Graph = mergeGraph(graph, context)
    val newNameScope: String = mergeNameScope(nameScope, context)
    val newDevice: OpSpecification => String = mergeDevice(device, context)
    val newColocationOps: Set[Op] = mergeColocationOps(colocationOps, context)
    val newControlDependencies: Set[Op] = mergeControlDependencies(controlDependencies, context)
    context.withValue(context.copy(
      graph = newGraph, nameScope = newNameScope, device = newDevice, colocationOps = newColocationOps,
      controlDependencies = newControlDependencies))(block)
  }

  /** Creates a context that can be used for creating ops.
    *
    * This function validates that the provided `values` are all defined in the same graph, makes that the graph used
    * by the op creation context it defines, and also "pushes" the provided `nameScope` in the op creation context. More
    * details on the op creation context can be found in the documentation of the public API [[createWith]] function of
    * this library.
    *
    * @param  nameScope Name scope to use.
    * @param  values    Input values to obtain the default graph from.
    * @param  block     Code block to run using the provided options.
    * @param  context   Current op creation context.
    * @tparam R Return type of the code block.
    * @return Return value of the code block.
    * @throws GraphMismatchException If any two of the values provided lie in different graphs.
    */
  @throws[GraphMismatchException]
  private[ops] def createWith[R](nameScope: String, values: Array[Op.Output])(block: => R)
      (implicit context: DynamicVariable[OpCreationContext]): R = {
    val newGraph: Graph = mergeGraph(getGraphFromInputs(values), context)
    val newNameScope: String = mergeNameScope(nameScope, context)
    context.withValue(context.copy(graph = newGraph, nameScope = newNameScope))(block)
  }

  /** Merges a graph to the provided op creation context graph and returns the graph to use when specifying the updated
    * op creation context. The merging rules are specified in the documentation of [[createWith]] function.
    *
    * @param  graph   Graph to merge.
    * @param  context Op creation context whose graph needs to be updated.
    * @return Graph to use for the new op creation context.
    */
  private[this] def mergeGraph(graph: Graph, context: OpCreationContext): Graph = {
    if (graph == null) context.graph else graph
  }

  /** Merges a name scope to the provided op creation context name scope and returns the name scope to use when
    * specifying the updated op creation context. The merging rules are specified in the documentation of [[createWith]]
    * function.
    *
    * @param  nameScope Name scope to merge.
    * @param  context   Op creation context whose name scope needs to be updated.
    * @return Name scope to use for the new op creation context.
    */
  private[this] def mergeNameScope(nameScope: String, context: OpCreationContext): String = {
    if (nameScope == null) {
      context.nameScope
    } else {
      // Check whether the provided name scope is valid.
      // If the root name scope is being set, then stricter checks are performed on it (i.e., op naming checks). This
      // makes sure the name scope does not start with any illegal characters (e.g., '_', '-', '\', and '/').
      if ((context.nameScope == "" && !checkName(nameScope))
          || (context.nameScope != "" && !checkNameScope(nameScope)))
        throw IllegalNameException(s"Illegal name scope '$nameScope'.")
      if (nameScope == "")
        ""
      else if (context.nameScope == "")
        uniqueName(graph = context.graph, name = s"${convertNameScopeToName(nameScope)}")
      else
        uniqueName(graph = context.graph, name = s"${context.nameScope}/${convertNameScopeToName(nameScope)}")
    }
  }

  /** Merges a device to the provided op creation context device and returns the device to use when specifying the
    * updated op creation context. The merging rules are specified in the documentation of [[createWith]] function.
    *
    * @param  device  Device to merge.
    * @param  context Op creation context whose device needs to be updated.
    * @return Device to use for the new op creation context.
    */
  private[this] def mergeDevice(
      device: OpSpecification => String = _ => "", context: OpCreationContext): OpSpecification => String = {
    val oldContextDevice = context.device
    opSpecification => {
      val oldDeviceSpecString = oldContextDevice(opSpecification)
      val newDeviceSpecString = if (device != null) device(opSpecification) else null
      // Check if the device has been reset or has to be reset for all subsequent nested scopes
      if (oldDeviceSpecString == null || newDeviceSpecString == null) {
        null
      } else {
        val oldDeviceSpec = DeviceSpecification.fromString(oldDeviceSpecString)
        val newDeviceSpec = DeviceSpecification.fromString(newDeviceSpecString)
        DeviceSpecification.merge(oldDeviceSpec, newDeviceSpec).toString
      }
    }
  }

  /** Merges a set of colocation ops to the provided op creation context set of colocation ops and returns the
    * set of colocation ops to use when specifying the updated op creation context. The merging rules are
    * specified in the documentation of [[createWith]] function.
    *
    * @param  colocationOps Set of colocation ops to merge.
    * @param  context       Op creation context whose colocation ops need to be updated.
    * @return Set of colocation ops to use for the new op creation context.
    */
  private[this] def mergeColocationOps(colocationOps: Set[Op], context: OpCreationContext): Set[Op] = {
    if (colocationOps == null)
      context.colocationOps
    else
      context.colocationOps ++ colocationOps
  }

  /** Merges a set of control dependencies to the provided op creation context set of control dependencies and returns
    * the set of control dependencies to use when specifying the updated op creation context. The merging rules are
    * specified in the documentation of [[createWith]] function.
    *
    * @param  controlDependencies Set of control dependencies to merge.
    * @param  context             Op creation context whose control dependencies needs to be updated.
    * @return Set of control dependencies to use for the new op creation context.
    */
  private[this] def mergeControlDependencies(controlDependencies: Set[Op], context: OpCreationContext): Set[Op] = {
    if (controlDependencies == null)
      context.controlDependencies
    else if (controlDependencies == Set.empty[Op])
      controlDependencies
    else
      context.controlDependencies ++ controlDependencies
  }

  /** Checks whether the provided string is a valid op name.
    *
    * @param  name String to check.
    * @return Boolean value indicating whether the check was successful.
    */
  private[this] def checkName(name: String): Boolean =
    VALID_OP_NAME_REGEX.pattern.matcher(name).matches

  /** Returns a unique operation name in a graph, based on the provided `name`.
    *
    * `uniqueName` first checks if an op named `name` exists in `graph`. If it doesn't, then `name` is returned.
    * Otherwise, `{name}_{i}` is returned, where `i` is the first non-zero integer for which no op with that name exists
    * in the `graph`.
    *
    * @note If this function is called while creating a new op, the graph needs to be locked while generating a unique
    *       name and adding the new op to the graph, so that no other op with the same name is added to the graph in the
    *       meantime. You rarely need to call `uniqueName` directly. Most of the time you just need to create
    *       `usingNameScope(...)` (which is also thread-safe) blocks to generate structured names.
    * @note Operation names are displayed in error messages reported by the TensorFlow runtime, and in various
    *       visualization tools such as TensorBoard.
    * @param  graph   Graph for which the unique name is generated.
    * @param  name    Name in which to base the generated unique name.
    * @param  counter Current counter value `i`.
    * @return Unique name.
    */
  private[this] def uniqueName(graph: Graph, name: String, counter: Int = 1): String = {
    if (graph.findOp(name).isEmpty)
      name
    else if (graph.findOp(s"${name}_$counter").isEmpty)
      s"${name}_$counter"
    else
      uniqueName(graph = graph, name = name, counter = counter + 1)
  }

  /** Checks whether the provided string is a valid name scope for creating ops.
    *
    * @param  nameScope String to check.
    * @return Boolean value indicating whether the check was successful.
    */
  private[this] def checkNameScope(nameScope: String): Boolean =
    VALID_NAME_SCOPE_REGEX.pattern.matcher(nameScope).matches

  /** Converts the provided name scope to a valid op name, by removing a trailing `"/"` if there exists one.
    *
    * @param  nameScope Name scope to convert.
    * @return Name obtained from the provided name scope.
    */
  private[this] def convertNameScopeToName(nameScope: String): String = {
    if (nameScope.endsWith("/"))
      nameScope.substring(0, nameScope.length - 1)
    else
      nameScope
  }

  /** Asserts that two ops are defined in the same graph. If they are not, a [[GraphMismatchException]] is thrown.
    *
    * @param  op1 First op.
    * @param  op2 Second op.
    * @throws GraphMismatchException If the two ops lie in different graphs.
    */
  @throws[GraphMismatchException]
  private[this] def assertSameGraph(op1: Op, op2: Op): Unit = {
    if (op1.graph != op2.graph)
      throw GraphMismatchException(s"'$op1' and '$op2' must be defined in the same graph.")
  }

  /** Returns the appropriate graph to use for the given inputs.
    *
    * This function provides a consistent algorithm for choosing the graph in which an op should be constructed in:
    *
    *   1. If the argument `graph` is provided and is not set to `null`, the function validates that all `inputs` are
    * defined in that graph.
    *   2. Otherwise, we attempt to select a graph from the first op in `inputs` and validate that all other `inputs`
    * are also defined in the same graph.
    *
    * @param  inputs Inputs.
    * @param  graph  Graph to use. If `null`, the graph is inferred from `inputs`.
    * @return The appropriate graph to use for the given inputs.
    * @throws GraphMismatchException If any two of the inputs lie in different graphs, or if `graph` is not `null` and
    *                                at least one of the `inputs` is not defined in it.
    */
  @throws[GraphMismatchException]
  private[this] def getGraphFromInputs(inputs: Array[Op.Output], graph: Graph = null): Graph = {
    val returnGraph = if (graph == null) inputs.head.graph else graph
    inputs.foreach(i => {
      if (graph == null)
        assertSameGraph(inputs.head, i)
      else if (i.graph != returnGraph)
        throw GraphMismatchException(s"'${i.op}' is not defined in the passed-in graph.")
    })
    returnGraph
  }

  //region ProtoBuf Helper Functions

  private[ops] def stripNameScope(nameScope: String, name: String): String =
    name.replaceFirst(s"([\\^]|loc:@|^)$nameScope[\\/]+(.*)", "$1$2")

  private[ops] def prependNameScope(nameScope: String, name: String): String =
    name.replaceFirst("([\\^]|loc:@|^)(.*)", "$1" + nameScope + "/$2")

  //endregion ProtoBuf Helper Functions

  /** Wrapper around an `Op` meant to represent one of its inputs. Actual op inputs have type `Op.Output` since they
    * represent outputs of other ops. Currently, `Op.Input` is only useful for representing consumers of an `Op`'s
    * outputs.
    *
    * @param  op    Op whose input this class represents.
    * @param  index Input index.
    */
  final case class Input private(op: Op, index: Int) {
    /** Name of this op input. This is simply set to `"<op.name>:<index>"`. */
    lazy val name: String = s"${op.name}:$index"

    /** Data type of this op input. */
    lazy val dataType: DataType = op.inputDataType(index)

    /** Graph where the op belongs. */
    def graph: Graph = op.graph

    override def toString: String = s"Op.Input(name = $name, dataType = $dataType)"
  }

  /** Trait representing outputs of an `Op`'s computation. */
  private[api] sealed trait OutputLike {
    /** Returns an [[Op.Output]] that this [[Op.OutputLike]] object represents. */
    private[api] def toOpOutput(dataType: DataType = null): Op.Output
  }

  /** Representation of one of the outputs of an `Op`'s computation.
    *
    * An `Op.Output` is a symbolic handle to one of the outputs of an `Op`. It does not hold the values of that op's
    * output, but instead provides a means of computing those values in a TensorFlow [[Session]].
    *
    * This class has two primary purposes:
    *
    *   1. An `Op.Output` can be passed as input to another `Op`. This builds a dataflow connection between ops, which
    * enables TensorFlow to execute an entire [[Graph]] that represents a large, multi-step computation.
    *   2. After the graph has been launched in a [[Session]], the value of an [[Op.Output]] can be computed by passing
    * it to `Session.run`.
    *   3. `Op.Output.evaluate` can also be used to compute the value of an [[Op.Output]] If no session is provided,
    * then the default session is used.
    *
    * In the following example, `c`, `d`, and `e` are symbolic [[Op.Output]] objects, whereas `result` is a Scala array
    * that stores a concrete value:
    * {{{
    *   val c = constant(Array(Array(1.0, 2.0), Array(3.0, 4.0)))
    *   val d = constant(Array(Array(1.0, 1.0), Array(0.0, 1.0)))
    *   val e = matMul(c, d)
    *   val result = e.evaluate() // 'result' now holds the result of the matrix multiplication.
    * }}}
    *
    * @param  op    Op whose output this class represents.
    * @param  index Output index.
    */
  final case class Output private(op: Op, index: Int) extends OutputLike {
    /** Name of this op output. This is simply set to `"<op.name>:<index>"`. */
    lazy val name: String = s"${op.name}:$index"

    /** Data type of this op output. */
    lazy val dataType: DataType = op.outputDataType(index)

    /** Consumers of this op output (i.e., ops that use this op output as one of their inputs). */
    lazy val consumers: Array[Op.Input] = op.outputConsumers(index)

    /** Graph where the op belongs. */
    def graph: Graph = op.graph

    /** Device on which this op output will be placed. */
    def device: String = op.device

    /** Shape of the tensor that this op output represents. */
    def shape: Shape = Shape.fromSeq(using(op.graph.reference) { r =>
      NativeOp.shape(r.nativeHandle, op.nativeHandle, index)
    })

    /** Evaluates this op output.
      *
      * If `feeds` is non-empty, then the provided feed values are fed into the session for computing the value of this
      * op output.
      *
      * If `session` is `null` (i.e., not provided), then the default session is used. Otherwise, `session` is used for
      * the evaluation.
      *
      * @param  feeds   Tensors to feed into the session for this evaluation.
      * @param  session Optional session to use for the evaluation.
      * @return Value of this op output, for this evaluation.
      */
    def value(feeds: Map[Op.Output, Tensor] = Map.empty, session: Session = null): Tensor = {
      val effectiveSession = if (session == null) defaultSession else session
      effectiveSession.run(feeds, Array(this))(0)
    }

    //region Slicing

    // TODO: Maybe add support for a name argument for the constructed op?
    /** Creates an op that slices this op according to the provided indexers.
      *
      * More details into how to construct and use indexers are provided in the [[Indexer]] documentation.
      *
      * @param  indexers Sequence of indexers to use.
      * @return Created op.
      */
    def slice(indexers: Indexer*): Op.Output = Indexer.toStridedSlice(indexers: _*)(this)

    //endregion Slicing

    //region Ops

    def +(other: Output): Output = MathOps.add(x = this, y = other)
    def -(other: Output): Output = MathOps.subtract(x = this, y = other)
    def *(other: Output): Output = MathOps.multiply(x = this, y = other)
    def /(other: Output): Output = MathOps.divide(x = this, y = other)

    //endregion Ops

    private[api] def toOpOutput(dataType: DataType = null): Op.Output = {
      throw new IllegalArgumentException(
        s"Op output conversion requested data type '$dataType' for op output, '$this', with data type: " +
            s"'${this.dataType}.")
      this
    }

    /** Creates an op that slices this op according to the provided indexers.
      *
      * More details into how to construct and use indexers are provided in the [[Indexer]] documentation.
      *
      * @param  indexers Sequence of indexers to use.
      * @return Created op.
      */
    def apply(indexers: Indexer*): Op.Output = slice(indexers: _*)

    override def toString: String = s"Op.Output(name = $name, shape = $shape, dataType = $dataType, device = $device)"
  }

  /** Sparse representation of one of the outputs of an `Op`'s computation. of a set of tensor slices at given indices.
    *
    * This class if a simple wrapper for a pair (or a set of three) of [[Op.Output]] objects:
    *   - `values`: An [[Op.Output]] of any data type, with shape `[D0, D1, ..., Dn]`.
    *   - `indices`: A one-dimensional integer [[Op.Output]] with shape `[D0]`.
    *   - `denseShape`: Optionally, an integer [[Op.Output]] with shape `[LARGE0, D1, ..., Dn]`.
    *
    * An [[Op.OutputIndexedSlices]] is typically used to represent a subset of a larger [[Op.Output]], `dense`, of shape
    * `[LARGE0, D1, ..., Dn]`, where `LARGE0 >> D0`. The values in `indices` are the indices in the first dimension of
    * the slices that have been extracted from the larger tensor.
    *
    * The dense [[Op.Output]], `dense`, represented by [[Op.OutputIndexedSlices]], `slices`, has:
    * {{{
    *   dense(slices.indices(i), ::, ::, ...) = slices.values(i, ::, ::, ...)
    * }}}
    *
    * The [[Op.OutputIndexedSlices]] class is used primarily in the definition of gradients for operations that have
    * sparse gradients, such as `gather`.
    *
    * Note that this is different than [[Op.SparseOutput]] which uses multi-dimensional indices and scalar values.
    *
    * @param  values     Values corresponding to the provided indices.
    * @param  indices    Indices along the first dimension of the corresponding dense [[Op.Output]].
    * @param  denseShape Shape of the corresponding dense [[Op.Output]].
    */
  final case class OutputIndexedSlices private(values: Op.Output, indices: Op.Output, denseShape: Op.Output = null)
      extends OutputLike {
    /** Graph that contains `values`, `indices`, and `denseShape`. */
    val graph: Graph = getGraphFromInputs(Array(values, indices, denseShape))

    /** Name of this op output indexed slices. */
    lazy val name: String = s"${values.name}[${indices.name}]" +
        (if (denseShape != null) s"(shape = ${denseShape.name})" else "")

    /** Data type of this op output indexed slices. */
    lazy val dataType: DataType = values.dataType

    /** Op that outputs these indexed slices. */
    def op: Op = values.op

    /** Device on which these op output indexed slices will be placed. */
    def device: String = values.device

    private[api] def toOpOutput(dataType: DataType = null): Op.Output = {
      if (dataType != null && !dataType.isCompatibleWith(this.dataType))
        throw new IllegalArgumentException(
          s"Op output conversion requested data type '$dataType' for op output, '$this', with data type: " +
              s"'${this.dataType}.")
      if (denseShape == null)
        throw new IllegalStateException(
          s"Op output conversion requested the conversion of 'Op.OutputIndexedSlices', '$this', which has no dense " +
              s"shape information available.")
      // TODO: Add check for large number of elements (e.g., > 100000000).
      createWith(nameScope = "IndexedSlicesToOutput") {
        MathOps.unsortedSegmentSum(data = values, segmentIndices = indices, segmentsNumber = denseShape(0))
      }
    }

    override def toString: String = {
      s"Op.OutputIndexedSlices(values = ${values.name}, indices = ${indices.name}, denseShape = ${denseShape.name}, " +
          s"device = $device)}"
    }
  }

  final case class SparseOutput private() // TODO: Add SparseOutput support.

  private[ops] final case class Builder(opType: String, name: String)
      (implicit context: DynamicVariable[OpCreationContext]) {
    private val graph: Graph = context.graph
    private val opName: String = if (context.nameScope == "") name else s"${context.nameScope}/$name"
    if (!checkName(name = opName))
      throw IllegalNameException(s"Illegal op name '$opName'.")

    private var built: Boolean = false
    private var inputs: Seq[Output] = Seq[Output]()
    private var inputLists: Seq[Array[Output]] = Seq[Array[Output]]()
    private var device: Option[String] = None
    private var byteArrayAttributes: Map[String, Array[Byte]] = Map[String, Array[Byte]]()
    private var longAttributes: Map[String, Long] = Map[String, Long]()
    private var longArrayAttributes: Map[String, Array[Long]] = Map[String, Array[Long]]()
    private var floatAttributes: Map[String, Float] = Map[String, Float]()
    private var floatArrayAttributes: Map[String, Array[Float]] = Map[String, Array[Float]]()
    private var booleanAttributes: Map[String, Boolean] = Map[String, Boolean]()
    private var booleanArrayAttributes: Map[String, Array[Boolean]] = Map[String, Array[Boolean]]()
    private var dataTypeAttributes: Map[String, Int] = Map[String, Int]()
    private var dataTypeArrayAttributes: Map[String, Array[Int]] = Map[String, Array[Int]]()
    private var tensorAttributes: Map[String, Long] = Map[String, Long]()
    private var tensorArrayAttributes: Map[String, Array[Long]] = Map[String, Array[Long]]()
    private var shapeAttributes: Map[String, Shape] = Map[String, Shape]()

    /** Prunes control dependencies from the provided set, given that the op for which these control dependencies are
      * specified uses `op` as direct or indirect (through other ops) input or control input. This eliminates redundant
      * control dependencies due to transitive dependencies (e.g., if `a` depends on `b` and `c`, and `b` depends on
      * `c`, then the dependency of `a` on `c` is pruned).
      *
      * @param  controlDeps Current set of control dependencies for the op that is being built.
      * @param  op          Op that is a direct or indirect (through other ops) input or control input, for the op that
      *                     is being built.
      */
    private[this] def pruneControlDependencies(controlDeps: mutable.Set[Op], op: Op): Unit = {
      // TODO: Check if this is too expensive for large graphs.
      // Prune op that is already used as input to the dependant op
      controlDeps -= op
      // Prune transitive control dependencies
      op.inputs.foreach(input => pruneControlDependencies(controlDeps, input.op))
      op.controlInputs.foreach(pruneControlDependencies(controlDeps, _))
    }

    def build(): Op = using(graph.reference) { _ =>
      if (built)
        throw OpBuilderUsedException("This op builder has already been used to built an op and cannot be re-used.")
      device = Option(context.device(OpSpecification(name = name, opType = opType)))
      graph.synchronized {
        using(graph.reference) { r =>
          val nativeHandle: Long = NativeOp.allocate(
            r.nativeHandle, opType, uniqueName(graph = graph, name = opName))
          inputs.foreach(input => NativeOp.addInput(nativeHandle, input.op.nativeHandle, input.index))
          inputLists.foreach(inputList => NativeOp.addInputList(
            nativeHandle, inputList.map(_.nativeHandle), inputList.map(_.index)))
          val controlDependencies: mutable.Set[Op] = mutable.Set(context.controlDependencies.toSeq: _*)
          inputs.foreach(input => pruneControlDependencies(controlDependencies, input.op))
          inputLists.foreach(_.foreach(input => pruneControlDependencies(controlDependencies, input.op)))
          controlDependencies.foreach(op => NativeOp.addControlInput(nativeHandle, op.nativeHandle))
          device.foreach(NativeOp.setDevice(nativeHandle, _))
          context.colocationOps.foreach(op => NativeOp.colocateWith(nativeHandle, op.nativeHandle))
          byteArrayAttributes.foreach(a => NativeOp.setAttrString(nativeHandle, a._1, a._2))
          longAttributes.foreach(a => NativeOp.setAttrInt(nativeHandle, a._1, a._2))
          longArrayAttributes.foreach(a => NativeOp.setAttrIntList(nativeHandle, a._1, a._2))
          floatAttributes.foreach(a => NativeOp.setAttrFloat(nativeHandle, a._1, a._2))
          floatArrayAttributes.foreach(a => NativeOp.setAttrFloatList(nativeHandle, a._1, a._2))
          booleanAttributes.foreach(a => NativeOp.setAttrBool(nativeHandle, a._1, a._2))
          booleanArrayAttributes.foreach(a => NativeOp.setAttrBoolList(nativeHandle, a._1, a._2))
          dataTypeAttributes.foreach(a => NativeOp.setAttrType(nativeHandle, a._1, a._2))
          dataTypeArrayAttributes.foreach(a => NativeOp.setAttrTypeList(nativeHandle, a._1, a._2))
          tensorAttributes.foreach(a => NativeOp.setAttrTensor(nativeHandle, a._1, a._2))
          tensorArrayAttributes.foreach(a => NativeOp.setAttrTensorList(nativeHandle, a._1, a._2))
          shapeAttributes.foreach(a => NativeOp.setAttrShape(nativeHandle, a._1, a._2.asArray, a._2.rank))
          val operation = Op(graph, NativeOp.finish(nativeHandle))
          built = true
          operation
        }
      }
    }

    def addInput(input: Output): Builder = {
      inputs :+= input
      this
    }

    def addInputs(inputs: Seq[Output]): Builder = {
      this.inputs ++= inputs
      this
    }

    def addInputList(inputs: Seq[Output]): Builder = {
      this.inputLists :+= inputs.toArray
      this
    }

    def setDevice(device: String): Builder = {
      this.device = Some(device)
      this
    }

    def setAttribute(name: String, value: String): Builder = {
      byteArrayAttributes += name -> value.getBytes(Charset.forName("UTF-8"))
      this
    }

    def setAttribute(name: String, value: Array[Byte]): Builder = {
      byteArrayAttributes += name -> value
      this
    }

    def setAttribute(name: String, value: Long): Builder = {
      longAttributes += name -> value
      this
    }

    def setAttribute(name: String, value: Array[Long]): Builder = {
      longArrayAttributes += name -> value
      this
    }

    def setAttribute(name: String, value: Float): Builder = {
      floatAttributes += name -> value
      this
    }

    def setAttribute(name: String, value: Array[Float]): Builder = {
      floatArrayAttributes += name -> value
      this
    }

    def setAttribute(name: String, value: Boolean): Builder = {
      booleanAttributes += name -> value
      this
    }

    def setAttribute(name: String, value: Array[Boolean]): Builder = {
      booleanArrayAttributes += name -> value
      this
    }

    def setAttribute(name: String, value: DataType): Builder = {
      dataTypeAttributes += name -> value.cValue
      this
    }

    def setAttribute(name: String, value: Array[DataType]): Builder = {
      dataTypeArrayAttributes += name -> value.map(_.cValue)
      this
    }

    def setAttribute(name: String, value: Tensor): Builder = {
      tensorAttributes += name -> value.nativeHandle
      this
    }

    def setAttribute(name: String, value: Array[Tensor]): Builder = {
      tensorArrayAttributes += name -> value.map(_.nativeHandle)
      this
    }

    def setAttribute(name: String, value: Shape): Builder = {
      shapeAttributes += name -> value
      this
    }
  }
}
