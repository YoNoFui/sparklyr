package sparklyr

import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._

import scala.reflect.ClassTag

import sparklyr.Backend
import sparklyr.Logger
import sparklyr.JVMObjectTracker

class WorkerRDD[T: ClassTag](
  parent: RDD[T],
  closure: Array[Byte],
  columns: Array[String]
  ) extends RDD[T](parent) {

  private[this] var port: Int = 8880
  private[this] var exception: Option[Exception] = None

  override def getPartitions = parent.partitions

  override def compute(split: Partition, task: TaskContext): Iterator[T] = {

    val sessionId: Int = scala.util.Random.nextInt(10000)
    val logger = new Logger("Worker", sessionId)
    val lock: AnyRef = new Object()

    val workerContext = new WorkerContext[T](
      parent,
      split,
      task,
      lock,
      closure,
      columns
    )

    val contextId = JVMObjectTracker.put(workerContext)
    logger.log("Tracking worker context under " + contextId)

    new Thread("starting backend thread") {
      override def run(): Unit = {
        try {
          logger.log("Backend starting")
          val backend: Backend = new Backend()

          /*
           * initialize backend as worker and service, since exceptions and
           * closing terminating the r session should not shutdown the process
           */
          backend.setType(
            true,   /* isService */
            false,  /* isRemote */
            true    /* isWorker */
          )

          backend.setHostContext(
            contextId
          )

          backend.init(
            port,
            sessionId
          )
        } catch {
          case e: Exception =>
            logger.logError("Failed to start backend: ", e)
            exception = Some(e)
            lock.synchronized {
              lock.notify
            }
        }
      }
    }.start()

    new Thread("starting rscript thread") {
      override def run(): Unit = {
        try {
          logger.log("RScript starting")

          val rscript = new Rscript(logger)
          rscript.init(sessionId)
        } catch {
          case e: Exception =>
            logger.logError("Failed to start rscript: ", e)
            exception = Some(e)
            lock.synchronized {
              lock.notify
            }
        }
      }
    }.start()

    logger.log("Waiting using lock for RScript to complete")
    lock.synchronized {
      lock.wait()
    }

    if (exception.isDefined) {
      throw exception.get
    }

    logger.log("Wait using lock for RScript completed")

    return workerContext.getResultArray().iterator
  }
}