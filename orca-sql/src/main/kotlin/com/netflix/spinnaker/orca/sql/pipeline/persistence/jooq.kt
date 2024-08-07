/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.orca.sql.pipeline.persistence

import com.netflix.spinnaker.config.ExecutionCompressionProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType
import de.huxhorn.sulky.ulid.ULID
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectForUpdateStep
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.DSL.field
import java.sql.ResultSet
import java.time.Duration

/**
 * Run the provided [fn] in a transaction.
 */
internal fun DSLContext.transactional(
  retrySupport: RetrySupport,
  fn: (DSLContext) -> Unit
) {
  retrySupport.retry(
    {
      transaction { ctx ->
        fn(DSL.using(ctx))
      }
    },
    5, Duration.ofMillis(100), false
  )
}

/**
 * Converts a String id to a jooq where condition, either using the legacy
 * UUID scheme or modern ULID.
 */
internal fun String.toWhereCondition() =
  if (isULID(this)) {
    field("id").eq(this)
  } else {
    field("legacy_id").eq(this)
  }

/**
 * Determines if the given [id] is ULID format or not.
 */
internal fun isULID(id: String): Boolean {
  try {
    if (id.length == 26) {
      ULID.parseULID(id)
      return true
    }
  } catch (ignored: Exception) {}

  return false
}

/**
 * Convert an execution type to its jooq table object.
 */
internal val ExecutionType.tableName: Table<Record>
  get() = when (this) {
    ExecutionType.PIPELINE -> DSL.table("pipelines")
    ExecutionType.ORCHESTRATION -> DSL.table("orchestrations")
  }

/**
 * Convert an execution type to its jooq stages table object.
 */
internal val ExecutionType.stagesTableName: Table<Record>
  get() = when (this) {
    ExecutionType.PIPELINE -> DSL.table("pipeline_stages")
    ExecutionType.ORCHESTRATION -> DSL.table("orchestration_stages")
  }

/**
 * Converts a provided table to it's equivalent compressed executions table
 */
internal val Table<Record>.compressedExecTable: Table<Record>
  get() = DSL.table("${this.name}_compressed_executions")

/**
 * Selects all stages for an [executionType] and List [executionIds].
 */
internal fun DSLContext.selectExecutionStages(executionType: ExecutionType, executionIds: Collection<String>, compressionProperties: ExecutionCompressionProperties): ResultSet {
  val selectFrom = select(selectStageFields(compressionProperties)).from(executionType.stagesTableName)

  if (compressionProperties.enabled) {
    selectFrom.leftJoin(executionType.stagesTableName.compressedExecTable).using(field("id"))
  }

  return selectFrom
    .where(field("execution_id").`in`(*executionIds.toTypedArray()))
    .fetch()
    .intoResultSet()
}

/**
 * The fields used in a SELECT executions query.
 */
internal fun selectExecutionFields(compressionProperties: ExecutionCompressionProperties): List<Field<Any>> {
  if (compressionProperties.enabled) {
    return listOf(field("id"),
      field("body"),
      field("compressed_body"),
      field("compression_type"),
      field(DSL.name("partition"))
    )
  }

  return listOf(field("id"),
    field("body"),
    field(DSL.name("partition"))
  )
}

/**
 * Fetch and map executions into [PipelineExecution] objects.
 */
internal fun SelectForUpdateStep<out Record>.fetchExecutions(
  mapper: ObjectMapper,
  stageReadSize: Int,
  compressionProperties: ExecutionCompressionProperties,
  jooq: DSLContext,
  pipelineRefEnabled: Boolean
) =
  ExecutionMapper(mapper, stageReadSize, compressionProperties, pipelineRefEnabled).map(fetch().intoResultSet(), jooq)


private fun selectStageFields(compressionProperties: ExecutionCompressionProperties): List<Field<Any>> {
  if (compressionProperties.enabled) {
    return listOf(field("execution_id"),
      field("body"),
      field("compressed_body"),
      field("compression_type")
    )
  }

  return listOf(field("execution_id"),
    field("body")
  )
}

internal fun DSLContext.selectExecution(type: ExecutionType, compressionProperties: ExecutionCompressionProperties, fields: List<Field<Any>> = selectExecutionFields(compressionProperties)): SelectJoinStep<Record> {
  val selectFrom = select(fields).from(type.tableName)

  if (compressionProperties.enabled) {
    selectFrom.leftJoin(type.tableName.compressedExecTable).using(field("id"))
  }

  return selectFrom
}
