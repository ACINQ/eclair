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

  val versionRegex = """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)([-SNAPSHOT]*)""".r

  /**
    * The files passed to this function must be jars containing a manifest entry for "Main-Class" with the
    * FQDN of the entry point of the plugin. The entry point is the implementation of the interface "fr.acinq.eclair.Plugin"
    * The manifest must also contain a comma separated list of supported eclair versions.
    *
    * @param jars
    * @return
    */
  def loadPlugins(jars: Seq[File]): Seq[Plugin] = {
    val eclairVersion = Option(getClass.getPackage.getImplementationVersion)
    val urls = jars.flatMap(openJar)
    val loader = new URLClassLoader(urls.map(_.getJarFileURL).toArray, ClassLoader.getSystemClassLoader)
    val (supported, unsupported) = urls.partition { jar =>
      val supportedVersions = Option(jar.getMainAttributes.getValue("X-Eclair-Supported-Versions")).map(_.split(",")).toSeq.flatten
      val versionOk = eclairVersion.isEmpty || supportedVersions.exists(v => versionEquals(v, eclairVersion.get))
      val mainClassOk = Option(jar.getMainAttributes.getValue("Main-Class")).isDefined
      versionOk && mainClassOk
    }

    unsupported.foreach(jar => logger.warn(s"found unsupported plugin ${jar.getJarFile.getName}"))
    supported.foreach(jar => logger.info(s"loading plugin ${jar.getJarFile.getName}"))

    val pluginClasses = supported
      .map(_.getMainAttributes.getValue("Main-Class"))
      .map(loader.loadClass)
      .map(_.getDeclaredConstructor().newInstance().asInstanceOf[Plugin])

    pluginClasses
  }

  def openJar(jar: File): Option[JarURLConnection] =
    Try(new URL(s"jar:file:${jar.getCanonicalPath}!/").openConnection().asInstanceOf[JarURLConnection]) match {
      case Success(url) => Some(url)
      case Failure(t) => logger.error(s"unable to load plugin file:${jar.getAbsolutePath} ", t); None
    }

  /**
    * Compares two versions ignoring the prerelease tag.
    */
  def versionEquals(a: String, b: String): Boolean = {
    val strippedA = a match { case versionRegex(major, minor, patch, _) => s"$major.$minor.$patch" }
    val strippedB = b match { case versionRegex(major, minor, patch, _) => s"$major.$minor.$patch" }
    strippedA == strippedB
  }

}