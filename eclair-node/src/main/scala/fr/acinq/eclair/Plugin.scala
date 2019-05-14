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
import java.net.{URL, URLClassLoader}

import org.clapper.classutil.ClassFinder

trait Plugin {

  def onSetup(setup: Setup): Unit

  def onKit(kit: Kit): Unit

}

object Plugin {

  def loadPlugins(jars: Seq[File]): Seq[Plugin] = {
      val finder = ClassFinder(jars)
      val classes = finder.getClasses
      val urls = jars.map(f => new URL(s"file:${f.getCanonicalPath}"))
      val loader = new URLClassLoader(urls.toArray, ClassLoader.getSystemClassLoader)
      classes
        .filter(_.isConcrete)
        .filter(_.implements(classOf[Plugin].getName))
        .map(c => Class.forName(c.name, true, loader).getDeclaredConstructor().newInstance().asInstanceOf[Plugin])
        .toList
    }
}