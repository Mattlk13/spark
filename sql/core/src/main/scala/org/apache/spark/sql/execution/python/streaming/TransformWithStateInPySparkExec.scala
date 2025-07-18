/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.python.streaming

import java.util.UUID

import scala.concurrent.duration.NANOSECONDS

import org.apache.hadoop.conf.Configuration

import org.apache.spark.{JobArtifactSet, SparkException}
import org.apache.spark.api.python.{ChainedPythonFunctions, PythonEvalType}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, PythonUDF}
import org.apache.spark.sql.catalyst.plans.logical.TransformWithStateInPySpark
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.{CoGroupedIterator, SparkPlan}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.execution.python.ArrowPythonRunner
import org.apache.spark.sql.execution.python.PandasGroupUtils.{executePython, groupAndProject, resolveArgOffsets}
import org.apache.spark.sql.execution.streaming.{DriverStatefulProcessorHandleImpl, StatefulOperatorStateInfo, StatefulOperatorsUtils, StatefulProcessorHandleImpl, TransformWithStateExecBase, TransformWithStateVariableInfo}
import org.apache.spark.sql.execution.streaming.StreamingSymmetricHashJoinHelper.StateStoreAwareZipPartitionsHelper
import org.apache.spark.sql.execution.streaming.state.{NoPrefixKeyStateEncoderSpec, RocksDBStateStoreProvider, StateSchemaValidationResult, StateStore, StateStoreColFamilySchema, StateStoreConf, StateStoreId, StateStoreOps, StateStoreProvider, StateStoreProviderId}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.streaming.{OutputMode, TimeMode}
import org.apache.spark.sql.types.{BinaryType, StructField, StructType}
import org.apache.spark.util.{CompletionIterator, SerializableConfiguration, Utils}

/**
 * Physical operator for executing
 * [[org.apache.spark.sql.catalyst.plans.logical.TransformWithStateInPySpark]]
 *
 * @param functionExpr function called on each group
 * @param groupingAttributes used to group the data
 * @param output used to define the output rows
 * @param outputMode defines the output mode for the statefulProcessor
 * @param timeMode The time mode semantics of the stateful processor for timers and TTL.
 * @param stateInfo Used to identify the state store for a given operator.
 * @param batchTimestampMs processing timestamp of the current batch.
 * @param eventTimeWatermarkForLateEvents event time watermark for filtering late events
 * @param eventTimeWatermarkForEviction event time watermark for state eviction
 * @param userFacingDataType the user facing data type of the function (both param and return type)
 * @param child the physical plan for the underlying data
 * @param isStreaming defines whether the query is streaming or batch
 * @param hasInitialState defines whether the query has initial state
 * @param initialState the physical plan for the input initial state
 * @param initialStateGroupingAttrs grouping attributes for initial state
 * @param initialStateSchema schema for initial state
 */
case class TransformWithStateInPySparkExec(
    functionExpr: Expression,
    groupingAttributes: Seq[Attribute],
    output: Seq[Attribute],
    outputMode: OutputMode,
    timeMode: TimeMode,
    stateInfo: Option[StatefulOperatorStateInfo],
    batchTimestampMs: Option[Long],
    eventTimeWatermarkForLateEvents: Option[Long],
    eventTimeWatermarkForEviction: Option[Long],
    userFacingDataType: TransformWithStateInPySpark.UserFacingDataType.Value,
    child: SparkPlan,
    isStreaming: Boolean = true,
    hasInitialState: Boolean,
    initialState: SparkPlan,
    initialStateGroupingAttrs: Seq[Attribute],
    initialStateSchema: StructType)
  extends TransformWithStateExecBase(
    groupingAttributes,
    timeMode,
    outputMode,
    batchTimestampMs,
    eventTimeWatermarkForEviction,
    child,
    initialStateGroupingAttrs,
    initialState) {

  // NOTE: This is needed to comply with existing release of transformWithStateInPandas.
  override def shortName: String = if (
    userFacingDataType == TransformWithStateInPySpark.UserFacingDataType.PANDAS
  ) {
    StatefulOperatorsUtils.TRANSFORM_WITH_STATE_IN_PANDAS_EXEC_OP_NAME
  } else {
    StatefulOperatorsUtils.TRANSFORM_WITH_STATE_IN_PYSPARK_EXEC_OP_NAME
  }

  private val pythonUDF = functionExpr.asInstanceOf[PythonUDF]
  private val pythonFunction = pythonUDF.func
  private val chainedFunc =
    Seq((ChainedPythonFunctions(Seq(pythonFunction)), pythonUDF.resultId.id))

  private val sessionLocalTimeZone = conf.sessionLocalTimeZone
  private val pythonRunnerConf = ArrowPythonRunner.getPythonRunnerConfMap(conf)
  private[this] val jobArtifactUUID = JobArtifactSet.getCurrentJobArtifactState.map(_.uuid)
  private val (dedupAttributes, argOffsets) = resolveArgOffsets(child.output, groupingAttributes)

  private val groupingKeyStructFields = groupingAttributes
    .map(a => StructField(a.name, a.dataType, a.nullable))
  private val groupingKeySchema = StructType(groupingKeyStructFields)
  private val groupingKeyExprEncoder = ExpressionEncoder(groupingKeySchema)
    .resolveAndBind().asInstanceOf[ExpressionEncoder[Any]]

  private val numOutputRows: SQLMetric = longMetric("numOutputRows")

  // Each state variable has its own schema, this is a dummy one.
  protected val schemaForKeyRow: StructType = new StructType().add("key", BinaryType)

  // Each state variable has its own schema, this is a dummy one.
  protected val schemaForValueRow: StructType = new StructType().add("value", BinaryType)

  override def getColFamilySchemas(
      shouldBeNullable: Boolean): Map[String, StateStoreColFamilySchema] = {
    // For Python, the user can explicitly set nullability on schema, so
    // we need to throw an error if the schema is nullable
    driverProcessorHandle.getColumnFamilySchemas(
      shouldCheckNullable = shouldBeNullable,
      shouldSetNullable = shouldBeNullable
    )
  }

  override def getStateVariableInfos(): Map[String, TransformWithStateVariableInfo] = {
    driverProcessorHandle.getStateVariableInfos
  }

  /** Metadata of this stateful operator and its states stores.
   * Written during IncrementalExecution. `validateAndMaybeEvolveStateSchema` will initialize
   * `columnFamilySchemas` and `stateVariableInfos` during `init()` call on driver. */
  private val driverProcessorHandle: DriverStatefulProcessorHandleImpl =
    new DriverStatefulProcessorHandleImpl(timeMode, groupingKeyExprEncoder)

  override def validateAndMaybeEvolveStateSchema(
      hadoopConf: Configuration,
      batchId: Long,
      stateSchemaVersion: Int): List[StateSchemaValidationResult] = {
    // Start a python runner on driver, and execute pre-init UDF on the runner
    val runner = new TransformWithStateInPySparkPythonPreInitRunner(
      pythonFunction,
      "pyspark.sql.streaming.transform_with_state_driver_worker",
      sessionLocalTimeZone,
      groupingKeySchema,
      driverProcessorHandle
    )
    // runner initialization
    runner.init()
    try {
      // execute UDF on the python runner
      runner.process()
    } catch {
      case e: Throwable =>
        throw new SparkException("TransformWithStateInPySpark driver worker " +
          "exited unexpectedly (crashed)", e)
    }
    runner.stop()
    val info = getStateInfo
    val stateSchemaDir = stateSchemaDirPath()

    validateAndWriteStateSchema(hadoopConf, batchId, stateSchemaVersion, info, stateSchemaDir,
      session, operatorStateMetadataVersion, stateStoreEncodingFormat =
        conf.stateStoreEncodingFormat)
  }

  /**
   * Produces the result of the query as an `RDD[InternalRow]`
   */
  override protected def doExecute(): RDD[InternalRow] = {
    metrics

    if (!hasInitialState) {
      if (isStreaming) {
        child.execute().mapPartitionsWithStateStore[InternalRow](
          getStateInfo,
          schemaForKeyRow,
          schemaForValueRow,
          NoPrefixKeyStateEncoderSpec(schemaForKeyRow),
          session.sqlContext.sessionState,
          Some(session.streams.stateStoreCoordinator),
          useColumnFamilies = true,
          useMultipleValuesPerKey = true
        ) {
          case (store: StateStore, dataIterator: Iterator[InternalRow]) =>
            processDataWithPartition(store, dataIterator)
        }
      } else {
        // If the query is running in batch mode, we need to create a new StateStore and instantiate
        // a temp directory on the executors in mapPartitionsWithIndex.
        val hadoopConfBroadcast =
          SerializableConfiguration.broadcast(sparkContext, session.sessionState.newHadoopConf())
        child.execute().mapPartitionsWithIndex[InternalRow](
          (partitionId: Int, dataIterator: Iterator[InternalRow]) => {
            initNewStateStoreAndProcessData(partitionId, hadoopConfBroadcast) { store =>
              processDataWithPartition(store, dataIterator)
            }
          }
        )
      }
    } else {
      val storeConf = new StateStoreConf(session.sqlContext.sessionState.conf)
      val hadoopConfBroadcast =
        SerializableConfiguration.broadcast(sparkContext, session.sessionState.newHadoopConf())

      child.execute().stateStoreAwareZipPartitions(
        initialState.execute(),
        getStateInfo,
        storeNames = Seq(),
        session.streams.stateStoreCoordinator) {
        // The state store aware zip partitions will provide us with two iterators,
        // child data iterator and the initial state iterator per partition.
        case (partitionId, childDataIterator, initStateIterator) =>
          if (isStreaming) {
            val stateStoreId = StateStoreId(stateInfo.get.checkpointLocation,
              stateInfo.get.operatorId, partitionId)
            val storeProviderId = StateStoreProviderId(stateStoreId, stateInfo.get.queryRunId)
            val store = StateStore.get(
              storeProviderId = storeProviderId,
              keySchema = schemaForKeyRow,
              valueSchema = schemaForValueRow,
              NoPrefixKeyStateEncoderSpec(schemaForKeyRow),
              version = stateInfo.get.storeVersion,
              stateStoreCkptId = stateInfo.get.getStateStoreCkptId(partitionId).map(_.head),
              stateSchemaBroadcast = stateInfo.get.stateSchemaMetadata,
              useColumnFamilies = true,
              storeConf = storeConf,
              hadoopConf = hadoopConfBroadcast.value.value
            )
            processDataWithPartition(store, childDataIterator, initStateIterator)
          } else {
            initNewStateStoreAndProcessData(partitionId, hadoopConfBroadcast) { store =>
              processDataWithPartition(store, childDataIterator, initStateIterator)
            }
          }
      }
    }
  }

  /**
   * Create a new StateStore for given partitionId and instantiate a temp directory
   * on the executors. Process data and close the stateStore provider afterwards.
   */
  private def initNewStateStoreAndProcessData(
      partitionId: Int,
      hadoopConfBroadcast: Broadcast[SerializableConfiguration])
      (f: StateStore => Iterator[InternalRow]): Iterator[InternalRow] = {

    val providerId = {
      val tempDirPath = Utils.createTempDir().getAbsolutePath
      new StateStoreProviderId(
        StateStoreId(tempDirPath, 0, partitionId), getStateInfo.queryRunId)
    }

    val sqlConf = new SQLConf()
    sqlConf.setConfString(SQLConf.STATE_STORE_PROVIDER_CLASS.key,
      classOf[RocksDBStateStoreProvider].getName)
    val storeConf = new StateStoreConf(sqlConf)

    // Create StateStoreProvider for this partition
    val stateStoreProvider = StateStoreProvider.createAndInit(
      providerId,
      schemaForKeyRow,
      schemaForValueRow,
      NoPrefixKeyStateEncoderSpec(schemaForKeyRow),
      useColumnFamilies = true,
      storeConf = storeConf,
      hadoopConf = hadoopConfBroadcast.value.value,
      useMultipleValuesPerKey = true,
      stateSchemaProvider = getStateInfo.stateSchemaMetadata)

    val store = stateStoreProvider.getStore(0, None)
    val outputIterator = f(store)
    CompletionIterator[InternalRow, Iterator[InternalRow]](outputIterator.iterator, {
      stateStoreProvider.close()
    }).map { row =>
      row
    }
  }

  private def processDataWithPartition(
      store: StateStore,
      dataIterator: Iterator[InternalRow],
      initStateIterator: Iterator[InternalRow] = Iterator.empty):
  Iterator[InternalRow] = {
    val allUpdatesTimeMs = longMetric("allUpdatesTimeMs")
    // TODO(SPARK-49603) set the metrics in the lazily initialized iterator
    val timeoutLatencyMs = longMetric("allRemovalsTimeMs")
    val commitTimeMs = longMetric("commitTimeMs")
    val currentTimeNs = System.nanoTime
    val updatesStartTimeNs = currentTimeNs

    // If timeout is based on event time, then filter late data based on watermark
    val filteredIter = watermarkPredicateForDataForLateEvents match {
      case Some(predicate) if timeMode == TimeMode.EventTime() =>
        applyRemovingRowsOlderThanWatermark(dataIterator, predicate)
      case _ =>
        dataIterator
    }

    val data = groupAndProject(filteredIter, groupingAttributes, child.output, dedupAttributes)

    val processorHandle = new StatefulProcessorHandleImpl(store, getStateInfo.queryRunId,
      groupingKeyExprEncoder, timeMode, isStreaming, batchTimestampMs, metrics)

    val evalType = {
      if (userFacingDataType == TransformWithStateInPySpark.UserFacingDataType.PANDAS) {
        PythonEvalType.SQL_TRANSFORM_WITH_STATE_PANDAS_UDF
      } else {
        PythonEvalType.SQL_TRANSFORM_WITH_STATE_PYTHON_ROW_UDF
      }
    }

    val outputIterator = if (!hasInitialState) {
      val runner = new TransformWithStateInPySparkPythonRunner(
        chainedFunc,
        evalType,
        Array(argOffsets),
        DataTypeUtils.fromAttributes(dedupAttributes),
        processorHandle,
        sessionLocalTimeZone,
        pythonRunnerConf,
        pythonMetrics,
        jobArtifactUUID,
        groupingKeySchema,
        batchTimestampMs,
        eventTimeWatermarkForEviction
      )
      executePython(data, output, runner)
    } else {
      // dedup attributes here because grouping attributes appear twice (key and value)
      val (initDedupAttributes, initArgOffsets) =
        resolveArgOffsets(initialState.output, initialStateGroupingAttrs)
      val initData =
        groupAndProject(initStateIterator, initialStateGroupingAttrs,
          initialState.output, initDedupAttributes)
      // group input rows and initial state rows by the same grouping key
      val groupedData: Iterator[(InternalRow, Iterator[InternalRow], Iterator[InternalRow])] =
        new CoGroupedIterator(data, initData, groupingAttributes)

      val evalType = {
        if (userFacingDataType == TransformWithStateInPySpark.UserFacingDataType.PANDAS) {
          PythonEvalType.SQL_TRANSFORM_WITH_STATE_PANDAS_INIT_STATE_UDF
        } else {
          PythonEvalType.SQL_TRANSFORM_WITH_STATE_PYTHON_ROW_INIT_STATE_UDF
        }
      }

      val runner = new TransformWithStateInPySparkPythonInitialStateRunner(
        chainedFunc,
        evalType,
        Array(argOffsets ++ initArgOffsets),
        DataTypeUtils.fromAttributes(dedupAttributes),
        DataTypeUtils.fromAttributes(initDedupAttributes),
        processorHandle,
        sessionLocalTimeZone,
        pythonRunnerConf,
        pythonMetrics,
        jobArtifactUUID,
        groupingKeySchema,
        batchTimestampMs,
        eventTimeWatermarkForEviction
      )
      executePython(groupedData, output, runner)
    }

    CompletionIterator[InternalRow, Iterator[InternalRow]](outputIterator, {
      // Note: Due to the iterator lazy execution, this metric also captures the time taken
      // by the upstream (consumer) operators in addition to the processing in this operator.
      allUpdatesTimeMs += NANOSECONDS.toMillis(System.nanoTime - updatesStartTimeNs)
      commitTimeMs += timeTakenMs {
        if (isStreaming) {
          processorHandle.doTtlCleanup()
          store.commit()
        } else {
          store.abort()
        }
      }
      setStoreMetrics(store)
      setOperatorMetrics()
    }).map { row =>
      numOutputRows += 1
      row
    }
  }

  override protected def withNewChildrenInternal(
      newLeft: SparkPlan, newRight: SparkPlan): TransformWithStateInPySparkExec =
    if (hasInitialState) {
      copy(child = newLeft, initialState = newRight)
    } else {
      copy(child = newLeft)
    }
}

// scalastyle:off argcount
object TransformWithStateInPySparkExec {

  // Plan logical transformWithStateInPySpark for batch queries
  def generateSparkPlanForBatchQueries(
      functionExpr: Expression,
      groupingAttributes: Seq[Attribute],
      output: Seq[Attribute],
      outputMode: OutputMode,
      timeMode: TimeMode,
      userFacingDataType: TransformWithStateInPySpark.UserFacingDataType.Value,
      child: SparkPlan,
      hasInitialState: Boolean = false,
      initialState: SparkPlan,
      initialStateGroupingAttrs: Seq[Attribute],
      initialStateSchema: StructType): SparkPlan = {
    val shufflePartitions = child.session.sessionState.conf.numShufflePartitions
    val statefulOperatorStateInfo = StatefulOperatorStateInfo(
      checkpointLocation = "", // empty checkpointLocation will be populated in doExecute
      queryRunId = UUID.randomUUID(),
      operatorId = 0,
      storeVersion = 0,
      numPartitions = shufflePartitions,
      stateStoreCkptIds = None
    )

    new TransformWithStateInPySparkExec(
      functionExpr,
      groupingAttributes,
      output,
      outputMode,
      timeMode,
      Some(statefulOperatorStateInfo),
      Some(System.currentTimeMillis),
      None,
      None,
      userFacingDataType,
      child,
      isStreaming = false,
      hasInitialState,
      initialState,
      initialStateGroupingAttrs,
      initialStateSchema)
  }
}
// scalastyle:on argcount
