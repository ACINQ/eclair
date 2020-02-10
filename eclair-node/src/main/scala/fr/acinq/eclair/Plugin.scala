/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import java.io.File
import java.net.{JarURLConnection, URL, URLClassLoader}

import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

trait Plugin {

  def onSetup(setup: Setup): Unit

  def onKit(kit: Kit): Unit

}

object Plugin extends Logging {

  /**
    * The files passed to this function must be jars containing a manifest entry for "Main-Class" with the
    * FQDN of the entry point of the plugin. The entry point is the implementation of the interface "fr.acinq.eclair.Plugin"
    * @param jars
    * @return
    */
  def loadPlugins(jars: Seq[File]): Seq[Plugin] = {
    val urls = jars.flatMap(f =>
      getOrNone(new URL(s"jar:file:${f.getCanonicalPath}!/").openConnection().asInstanceOf[JarURLConnection], s"unable to load plugin file:${f.getAbsolutePath} ")
    )
    val loader = new URLClassLoader(urls.map(_.getJarFileURL).toArray, ClassLoader.getSystemClassLoader)
    val pluginClasses = urls
        .map(_.getMainAttributes.getValue("Main-Class"))
        .flatMap(classFQDN => getOrNone[Class[_]](loader.loadClass(classFQDN), s"error loading $classFQDN "))
        .flatMap(c => getOrNone[Plugin](c.getDeclaredConstructor().newInstance().asInstanceOf[Plugin], s"${c.getCanonicalName} does not implement $Plugin "))

    logger.info(s"loading ${pluginClasses.size} plugins")
    pluginClasses
  }

  def getOrNone[T](f: => T, errMsg: String): Option[T] = Try(f) match {
    case Failure(exception) =>
      logger.error(errMsg, exception)
      None
    case Success(value) => Some(value)
  }

}