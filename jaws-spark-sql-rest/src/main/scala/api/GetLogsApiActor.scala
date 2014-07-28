package api

import akka.actor.Actor
import akka.actor.actorRef2Scala
import messages.GetQueriesMessage
import com.google.common.base.Preconditions
import actors.LogsActor
import akka.actor.ActorLogging
import traits.DAL
import messages.GetLogsMessage
import org.joda.time.DateTime
import java.util.Collection
import actors.Configuration
import com.xpatterns.jaws.data.DTO.Logs
import com.xpatterns.jaws.data.DTO.Log
/**
 * Created by emaorhian
 */
class GetLogsApiActor(dals: DAL) extends Actor {

  override def receive = {

    case message: GetLogsMessage => {
      Configuration.log4j.info("[GetLogsApiActor]: retrieving logs for: " + message.queryID)
      Preconditions.checkArgument(message.queryID != null && !message.queryID.isEmpty(), Configuration.UUID_EXCEPTION_MESSAGE)
      var startDate = message.startDate
      var limit = message.limit

      Option(message.startDate) match {
        case None => {
          startDate = new DateTime(1977, 1, 1, 1, 1, 1, 1).getMillis()
        }
        case _ => Configuration.log4j.debug("[GetLogsApiActor]: Start date = " + startDate)
        
      }

      Option(limit) match {
        case None => limit = 100
        case _ => Configuration.log4j.debug("[GetLogsApiActor]: Limit = " + limit)
      }

      sender ! dals.loggingDal.getLogs(message.queryID, startDate, limit)

    }
  }
}