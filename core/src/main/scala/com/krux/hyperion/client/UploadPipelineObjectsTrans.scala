package com.krux.hyperion.client

import scala.collection.JavaConverters._

import com.amazonaws.services.datapipeline.DataPipelineClient
import com.amazonaws.services.datapipeline.model.{ PipelineObject, ListPipelinesRequest,
  ParameterObject, CreatePipelineRequest, Tag, PutPipelineDefinitionRequest }
import org.slf4j.LoggerFactory

import com.krux.hyperion.DataPipelineDefGroup


case class UploadPipelineObjectsTrans(
  client: DataPipelineClient,
  pipelineDef: DataPipelineDefGroup,
  maxRetry: Int
) extends Transaction[Option[Unit], AwsClientForId] with Retry {

  val log = LoggerFactory.getLogger(getClass)

  val parameterObjects = pipelineDef.toAwsParameters

  val keyObjectsMap = pipelineDef.toAwsPipelineObjects

  private def createAndUploadObjects(name: String, objects: Seq[PipelineObject]): Option[String] = {

    val pipelineId = client
      .createPipeline(
        new CreatePipelineRequest()
          .withUniqueId(name)
          .withName(name)
          .withTags(
            pipelineDef.tags.toSeq
              .map { case (k, v) => new Tag().withKey(k).withValue(v.getOrElse("")) }
              .asJava
          )
      )
      .retry()
      .getPipelineId

    log.info(s"Created pipeline $pipelineId ($name)")
    log.info(s"Uploading pipeline definition to $pipelineId")

    val putDefinitionResult = client
      .putPipelineDefinition(
        new PutPipelineDefinitionRequest()
          .withPipelineId(pipelineId)
          .withPipelineObjects(objects.asJava)
          .withParameterObjects(parameterObjects.asJava)
      )
      .retry()

    putDefinitionResult.getValidationErrors.asScala
      .flatMap(err => err.getErrors.asScala.map(detail => s"${err.getId}: $detail"))
      .foreach(log.error)
    putDefinitionResult.getValidationWarnings.asScala
      .flatMap(err => err.getWarnings.asScala.map(detail => s"${err.getId}: $detail"))
      .foreach(log.warn)

    if (putDefinitionResult.getErrored) {
      log.error("Failed to create pipeline")
      log.error("Deleting the just created pipeline")
      AwsClientForId(client, Set(pipelineId), maxRetry).deletePipelines()
      None
    } else if (putDefinitionResult.getValidationErrors.isEmpty
      && putDefinitionResult.getValidationWarnings.isEmpty) {
      log.info("Successfully created pipeline")
      Option(pipelineId)
    } else {
      log.warn("Successful with warnings")
      Option(pipelineId)
    }
  }

  def action() = AwsClientForId(
    client,
    keyObjectsMap
      .flatMap { case (key, objects) =>
        createAndUploadObjects(pipelineDef.nameForKey(key), objects)
      }
      .toSet,
    maxRetry
  )

  def validate(result: AwsClientForId) = result.pipelineIds.size == keyObjectsMap.size

  def rollback(result: AwsClientForId) = result.deletePipelines()

}
