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

import akka.http.scaladsl.server.Route
import fr.acinq.eclair.api.directives.EclairDirectives
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

trait Plugin {

  def params: PluginParams

  def onSetup(setup: Setup): Unit

  def onKit(kit: Kit): Unit
}

trait RouteProvider {
  def route(directives: EclairDirectives): Route
}

object Plugin extends Logging {

  /**
    * The files passed to this function must be jars containing a manifest entry for "Main-Class" with the
    * FQDN of the entry point of the plugin. The entry point is the implementation of the interface "fr.acinq.eclair.Plugin"
    * @param jars
    * @return
    */
  def loadPlugins(jars: Seq[File]): Seq[Plugin] = {
    val urls = jars.flatMap(openJar)
    val loader = new URLClassLoader(urls.map(_.getJarFileURL).toArray, ClassLoader.getSystemClassLoader)
    val pluginClasses = urls
        .flatMap(j => Option(j.getMainAttributes.getValue("Main-Class")))
        .flatMap(classFQDN => Try[Class[_]](loader.loadClass(classFQDN)).toOption)
        .flatMap(c => Try(c.getDeclaredConstructor().newInstance().asInstanceOf[Plugin]).toOption)

    logger.info(s"loading ${pluginClasses.size} plugins")
    pluginClasses
  }

  def openJar(jar: File): Option[JarURLConnection] =
    Try(new URL(s"jar:file:${jar.getCanonicalPath}!/").openConnection().asInstanceOf[JarURLConnection]) match {
      case Success(url) => Some(url)
      case Failure(t) => logger.error(s"unable to load plugin file:${jar.getAbsolutePath} ", t); None
    }

}
