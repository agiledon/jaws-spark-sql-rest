package apiactors

import akka.actor.Actor
import akka.actor.actorRef2Scala
import com.google.common.base.Preconditions
import com.xpatterns.jaws.data.contracts.DAL
import messages._
import java.util.UUID
import server.Configuration
import com.google.common.cache.CacheBuilder
import java.util.concurrent.TimeUnit
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import server.MainActors
import com.google.common.cache.Cache
import org.apache.spark.scheduler.RunScriptTask
import implementation.HiveContextWrapper
import org.apache.spark.sql.parquet.ParquetSparkUtility._
import apiactors.ActorsPaths._
import scala.util.Try
import apiactors.ActorOperations._
import org.apache.spark.scheduler.RunParquetScriptTask
import org.apache.spark.scheduler.HiveUtils
import com.xpatterns.jaws.data.utils.QueryState
import org.apache.hadoop.conf.{Configuration => HadoopConfiguration}
/**
 * Created by emaorhian
 */
class RunScriptApiActor(hdfsConf: HadoopConfiguration, hiveContext: HiveContextWrapper, dals: DAL) extends Actor {
  var taskCache: Cache[String, RunScriptTask] = _
  var threadPool: ThreadPoolTaskExecutor = _
 

  override def preStart() {
    taskCache = {
      CacheBuilder
        .newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build[String, RunScriptTask]
    }

    threadPool = new ThreadPoolTaskExecutor()
    threadPool.setCorePoolSize(Configuration.nrOfThreads.getOrElse("10").toInt)
    threadPool.initialize()
  }

  override def receive = {
    case message: RunScriptMessage => {

      val uuid = System.currentTimeMillis() + UUID.randomUUID().toString()
      val tryRun = Try {
        Configuration.log4j.info("[RunScriptApiActor -run]: running the following script: " + message.script)
        Configuration.log4j.info("[RunScriptApiActor -run]: The script will be executed with the limited flag set on " + message.limited + ". The maximum number of results is " + message.maxNumberOfResults)

        val task = new RunScriptTask(dals, hiveContext, uuid, hdfsConf, message)
        taskCache.put(uuid, task)
        writeLaunchStatus(uuid, message.script)
        threadPool.execute(task)
      }
      returnResult(tryRun, uuid, "run query failed with the following message: ", sender)
    }

    case message: RunParquetMessage => {
      val uuid = System.currentTimeMillis() + UUID.randomUUID().toString()
      val tryRunParquet = Try {

        Configuration.log4j.info(s"[RunScriptApiActor -runParquet]: running the following sql: ${message.script}")
        Configuration.log4j.info(s"[RunScriptApiActor -runParquet]: The script will be executed over the ${message.tablePath} file with the ${message.table} table name")
     
        val task = new RunParquetScriptTask(dals, hiveContext, uuid, hdfsConf, message)
        taskCache.put(uuid, task)
        writeLaunchStatus(uuid, message.script)
        threadPool.execute(task)
      }
      returnResult(tryRunParquet, uuid, "run parquet query failed with the following message: ", sender)
    }

    case message: CancelMessage => {
      Configuration.log4j.info("[RunScriptApiActor]: Canceling the jobs for the following uuid: " + message.queryID)

      val task = taskCache.getIfPresent(message.queryID)

      Option(task) match {
        case None => {
          Configuration.log4j.info("No job to be canceled")
        }
        case _ => {

          task.setCanceled(true)
          taskCache.invalidate(message.queryID)

          if (Option(hiveContext.sparkContext.getConf.get("spark.mesos.coarse")).getOrElse("true").equalsIgnoreCase("true")) {
            Configuration.log4j.info("[RunScriptApiActor]: Jaws is running in coarse grained mode!")
            hiveContext.sparkContext.cancelJobGroup(message.queryID)
          } else {
            Configuration.log4j.info("[RunScriptApiActor]: Jaws is running in fine grained mode!")
          }

        }
      }

    }
  }

  private def writeLaunchStatus(uuid: String, script: String) {
    HiveUtils.logMessage(uuid, s"Launching task for $uuid", "hql", dals.loggingDal)
    dals.loggingDal.setState(uuid, QueryState.IN_PROGRESS)
    dals.loggingDal.setScriptDetails(uuid, script)
  }

 
}