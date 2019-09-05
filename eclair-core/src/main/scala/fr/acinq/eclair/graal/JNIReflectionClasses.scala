package fr.acinq.eclair.graal

import com.oracle.svm.core.annotate.AutomaticFeature
import com.oracle.svm.core.jni.JNIRuntimeAccess
import com.typesafe.scalalogging.LazyLogging
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeReflection
import org.sqlite.BusyHandler
import org.sqlite.Function
import org.sqlite.ProgressHandler
import org.sqlite.core.NativeDB

@AutomaticFeature
class JNIReflectionClasses extends Feature with LazyLogging {

  override def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit = {
    try {
      JNIRuntimeAccess.register(classOf[NativeDB].getDeclaredMethod("_open_utf8", classOf[Array[Byte]], classOf[Int]))
    } catch {
      case e: Exception =>
        e.printStackTrace()
    }
    reflectionClasses.foreach(register)
  }

  val reflectionClasses = List(
    classOf[org.sqlite.core.DB],
    classOf[NativeDB],
    classOf[BusyHandler],
    classOf[Function],
    classOf[ProgressHandler],
    classOf[Function.Aggregate],
    classOf[Function.Window],
    classOf[org.sqlite.core.DB.ProgressObserver],
    classOf[java.lang.Throwable],
    classOf[Array[Boolean]]
  )

  private def register(clazz: Class[_]): Unit = {
    try {
      logger.info(s"Declaring class: ${clazz.getCanonicalName}")
      RuntimeReflection.register(clazz)
      for (method <- clazz.getDeclaredMethods) {
        //        logger.info(s"method: ${method.getName}")
        JNIRuntimeAccess.register(method)
        RuntimeReflection.register(method)
      }
      for (field <- clazz.getDeclaredFields) {
        //        logger.info(s"field: ${field.getName}")
        JNIRuntimeAccess.register(field)
        RuntimeReflection.register(field)
      }
      for (constructor <- clazz.getDeclaredConstructors) {
        //        logger.info(s"constructor: ${constructor.getName}")
        JNIRuntimeAccess.register(constructor)
        RuntimeReflection.register(constructor)
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        System.exit(1)
    }
  }

}
